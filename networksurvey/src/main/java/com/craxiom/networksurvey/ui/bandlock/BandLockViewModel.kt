package com.craxiom.networksurvey.ui.bandlock

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal enum class LockAvailability { Unchecked, Checking, Ready, Unavailable }
internal data class BandLockState(
    val availability: LockAvailability = LockAvailability.Unchecked,
    val busy: Boolean = false,
    val channel: String = "",
    val caps: List<Pair<String, Boolean>> = emptyList(),
    val profile: BandLockProfile = BandLockProfile(LockSupportLevel.Unsupported, "unknown", reason = "unchecked"),
    val lteBackup: Boolean = false,
    val nrBackup: Boolean = false,
    val appliedMode: LockNetworkMode? = null,
    val result: Int? = null,
    val log: String = "",
)

internal class BandLockViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(BandLockState())
    val state = mutableState.asStateFlow()
    val device = "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MODEL}"
    private val probeCache = app.getSharedPreferences("band_lock_probe", 0)
    private val probeCacheKey = "probe:${BuildConfig.VERSION_CODE}:${Build.FINGERPRINT}:${Build.SOC_MODEL}"
    @Volatile private var process: Process? = null

    init {
        restoreProbe()?.let { mutableState.value = it }
    }

    private fun restoreProbe(): BandLockState? {
        val stored = probeCache.getString(probeCacheKey, null) ?: return null
        return runCatching {
            val cached = JSONObject(stored)
            val channel = cached.getString("channel")
            val rawCaps = cached.getJSONObject("caps")
            val caps = rawCaps.keys().asSequence().map { it to rawCaps.getBoolean(it) }.toList()
            val profile = classify(channel.takeIf { cached.getBoolean("ready") }, caps)
            BandLockState(
                availability = if (profile.canLock) LockAvailability.Ready else LockAvailability.Unavailable,
                channel = channel,
                caps = caps,
                profile = profile,
                lteBackup = cached.getBoolean("lte_backup"),
                nrBackup = cached.getBoolean("nr_backup"),
            )
        }.getOrNull()
    }

    private fun saveProbe(state: BandLockState) {
        val value = JSONObject()
            .put("ready", state.availability == LockAvailability.Ready)
            .put("channel", state.channel)
            .put("caps", JSONObject(state.caps.toMap()))
            .put("lte_backup", state.lteBackup)
            .put("nr_backup", state.nrBackup)
        probeCache.edit().putString(probeCacheKey, value.toString()).apply()
    }

    private fun classify(channel: String?, caps: List<Pair<String, Boolean>>) = BandLockProfiles.classify(
        Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL, Build.HARDWARE, channel, caps.count { it.second },
    )

    fun refresh() = run(BandLockCommand.probe(Build.MODEL, Build.SOC_MODEL))
    fun clearLog() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(log = "") }

    fun run(
        command: BandLockCommand,
        fromConsole: Boolean = false,
        onModemRestartRequested: (() -> Unit)? = null,
    ) {
        if (mutableState.value.busy) return
        val probe = command.arguments.firstOrNull() == "probe"
        if (!probe && mutableState.value.availability != LockAvailability.Ready) return
        mutableState.update {
            it.copy(busy = true, result = null, availability = if (probe) LockAvailability.Checking else it.availability)
        }
        viewModelScope.launch {
            try {
                val execution = withContext(Dispatchers.IO) {
                    command.executeWithMode(onModemRestartRequested = {
                        withContext(Dispatchers.Main) { onModemRestartRequested?.invoke() }
                    }) { step -> execute(step, recordDiagnostics = !fromConsole) }
                }
                val outcome = execution.outcome
                val current = mutableState.value
                val ready = outcome.exit == 0 && outcome.output.lineSequence().any { it == "RESULT ready" }
                val accepted = outcome.accepted
                val probedChannel = if (probe) outcome.output.lineSequence()
                    .firstOrNull { it.startsWith("CHANNEL ") }
                    ?.removePrefix("CHANNEL ")?.trim().orEmpty() else current.channel
                val probedCaps = if (probe) parseCaps(outcome.output) else current.caps
                val profile = if (probe) classify(probedChannel.takeIf { ready }, probedCaps) else current.profile
                mutableState.value = current.copy(
                    busy = false,
                    availability = if (probe) {
                        if (ready && profile.canLock) LockAvailability.Ready else LockAvailability.Unavailable
                    } else current.availability,
                    channel = if (probe) probedChannel else current.channel,
                    caps = if (probe) probedCaps else current.caps,
                    profile = profile,
                    lteBackup = if (probe && ready) outcome.output.contains("BACKUP lte 1") else
                        current.lteBackup || (accepted && command.arguments.first() == "lte-band"),
                    nrBackup = when {
                        probe && ready -> outcome.output.contains("BACKUP nr 1")
                        outcome.baselineUnverified -> false
                        else -> current.nrBackup || (accepted && command.arguments.first() == "nr-band")
                    },
                    appliedMode = when {
                        execution.modeConfirmed == true -> command.modeAfterLock
                        accepted && command.arguments.first() == "mode" ->
                            LockNetworkMode.entries.firstOrNull { it.argument == command.arguments.getOrNull(1) }
                        else -> current.appliedMode
                    },
                    result = when {
                        fromConsole || probe -> null
                        outcome.baselineUnverified -> R.string.lock_nr_baseline_unverified
                        execution.modeConfirmed == false -> R.string.lock_mode_unconfirmed
                        execution.modeConfirmed == true -> R.string.lock_and_mode_accepted
                        outcome.restartRequested -> R.string.lock_restart_sent
                        accepted -> R.string.lock_accepted
                        else -> R.string.lock_failed
                    },
                )
                if (probe || accepted || outcome.baselineUnverified) saveProbe(mutableState.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog(error.message ?: error.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    availability = if (probe) LockAvailability.Unavailable else mutableState.value.availability,
                    profile = if (probe) BandLockProfiles.classify(
                        Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL, Build.HARDWARE, null, 0,
                    ) else mutableState.value.profile,
                    result = if (fromConsole || probe) null else R.string.lock_failed,
                )
                if (probe) probeCache.edit().remove(probeCacheKey).apply()
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    /** Command console entry: run a raw native command line and stream its
     * output to the log without overwriting the standard result line. */
    fun runRaw(text: String) {
        run(BandLockCommand.console(text), fromConsole = true)
    }

    private suspend fun execute(command: BandLockCommand, recordDiagnostics: Boolean): BandLockOutcome = try {
        runHelper(command, recordDiagnostics)
    } catch (error: IOException) {
        appendLog(error.message ?: error.javaClass.simpleName)
        BandLockOutcome(1, "")
    }

    private suspend fun runHelper(command: BandLockCommand, recordDiagnostics: Boolean): BandLockOutcome {
        val started = SystemClock.elapsedRealtime()
        val name = command.arguments.first()
        fun diagnostic(message: String) {
            if (recordDiagnostics) AppLogger.i(AppLogger.BAND_LOCK_TAG, "run=$started cmd=$name $message")
        }
        fun helperLine(line: String) {
            if (line.startsWith("NR_BAND ") || line.startsWith("SSR ")) {
                diagnostic(line)
                return
            }
            appendLog(line)
            if (listOf("ACK ", "CHANNEL ", "RESULT ", "BACKUP ", "CAP ")
                    .any(line::startsWith) ||
                line.startsWith("No recognized modem acknowledgement.")) diagnostic(line)
        }
        diagnostic("start model=${Build.MODEL} soc=${Build.SOC_MODEL} baseband=${Build.getRadioVersion().orEmpty()} " +
            "args=${command.arguments.drop(1).joinToString(",")}")
        val helper = File(app.applicationInfo.nativeLibraryDir, "libwireless_band_lock.so")
        if (!helper.isFile) {
            diagnostic("helper_missing")
            throw IOException("The lock helper is unavailable for this ABI.")
        }
        val directory = File(app.noBackupFilesDir, "band-lock")
        if (!directory.isDirectory && !directory.mkdirs()) {
            diagnostic("state_dir_unavailable")
            throw IOException("Cannot open the private backup directory.")
        }
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val arguments = listOf(helper.absolutePath, "--state-dir", directory.absolutePath) + command.arguments
        appendLog("${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} > ${command.arguments.joinToString(" ")}")
        val running = try {
            ProcessBuilder("su", "-c", "exec " + arguments.joinToString(" ", transform = ::quote))
                .redirectErrorStream(true).start()
        } catch (error: IOException) {
            diagnostic("helper_launch_failed")
            throw error
        }
        process = running
        try {
            running.outputStream.close()
            val output = StringBuilder()
            val pending = StringBuilder()
            val input = running.inputStream
            val bytes = ByteArray(4096)
            while (running.isAlive || input.available() > 0) {
                if (SystemClock.elapsedRealtime() - started > 110_000) {
                    diagnostic("helper_timeout")
                    running.destroyForcibly()
                    throw IOException("Command timed out. Changes may be partial; refresh the device status.")
                }
                val available = input.available()
                if (available == 0) { delay(80); continue }
                val count = input.read(bytes, 0, minOf(bytes.size, available))
                if (count <= 0) break
                val chunk = String(bytes, 0, count, Charsets.UTF_8)
                output.append(chunk)
                pending.append(chunk)
                if (output.length > 48_000) throw IOException("Unexpected helper output size.")
                var end = pending.indexOf("\n")
                while (end >= 0) {
                    helperLine(pending.substring(0, end).trimEnd('\r'))
                    pending.delete(0, end + 1)
                    end = pending.indexOf("\n")
                }
            }
            if (pending.isNotEmpty()) helperLine(pending.toString())
            val exit = running.waitFor()
            diagnostic("exit=$exit elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            return BandLockOutcome(exit, output.toString())
        } catch (error: IOException) {
            diagnostic("helper_io_error")
            throw error
        } finally {
            running.destroy()
            process = null
            running.inputStream.close()
        }
    }

    private fun parseCaps(output: String): List<Pair<String, Boolean>> =
        output.lineSequence().filter { it.startsWith("CAP ") }.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val key = parts.getOrNull(1) ?: return@mapNotNull null
            key to (parts.getOrNull(2) == "1")
        }.toList()

    private fun appendLog(line: String) {
        mutableState.update { it.copy(log = (it.log + line + "\n").takeLast(24_000)) }
    }

    override fun onCleared() {
        process?.destroy()
        super.onCleared()
    }
}
