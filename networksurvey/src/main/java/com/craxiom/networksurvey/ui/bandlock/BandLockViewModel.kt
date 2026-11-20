package com.craxiom.networksurvey.ui.bandlock

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal enum class LockAvailability { Unchecked, Cached, Checking, Ready, Unavailable }
internal data class BandLockState(
    val availability: LockAvailability = LockAvailability.Unchecked,
    val busy: Boolean = false,
    val channel: String = "",
    val caps: List<Pair<String, Boolean>> = emptyList(),
    val profile: BandLockProfile = BandLockProfile(LockSupportLevel.Unsupported, "unknown", reason = "unchecked"),
    val lteBackup: Boolean = false,
    val nrBackup: Boolean = false,
    val lteBackupHint: Boolean = false,
    val nrBackupHint: Boolean = false,
    val appliedMode: LockNetworkMode? = null,
    val result: Int? = null,
    val log: String = "",
)

internal class BandLockViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(BandLockState())
    val state = mutableState.asStateFlow()
    val device = "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MODEL}"
    private val identity = DeviceIdentity(Build.MODEL, Build.SOC_MODEL, Build.FINGERPRINT)
    private val probeCache = app.getSharedPreferences("band_lock_probe", 0)
    private val probeCacheKey = BandLockVerification.cacheKey(
        BuildConfig.VERSION_CODE, Build.FINGERPRINT, Build.SOC_MODEL,
    )
    @Volatile private var process: Process? = null

    init {
        restoreProbe()?.let { mutableState.value = it }
        refresh()
    }

    private fun backupDirectory(): File = File(app.noBackupFilesDir, "band-lock")

    private fun restoreProbe(): BandLockState? {
        val stored = probeCache.getString(probeCacheKey, null) ?: return null
        val cached = BandLockVerification.parseCachedProbe(stored) ?: return null
        return displayCached(cached)
    }

    private fun displayCached(cached: CachedProbe): BandLockState {
        val profile = classify(cached.channel.takeIf { cached.readyHint }, cached.caps)
        return BandLockState(
            availability = LockAvailability.Cached,
            channel = cached.channel,
            caps = cached.caps,
            profile = profile,
            lteBackup = false,
            nrBackup = false,
            lteBackupHint = cached.lteBackupHint,
            nrBackupHint = cached.nrBackupHint,
        )
    }

    private fun saveProbe(state: BandLockState) {
        if (state.availability != LockAvailability.Ready) return
        val probe = CachedProbe(state.channel, state.caps, true, state.lteBackup, state.nrBackup)
        val encoded = BandLockVerification.encodeCache(probe, true, state.lteBackup, state.nrBackup) ?: return
        probeCache.edit().putString(probeCacheKey, encoded).apply()
    }

    private fun classify(channel: String?, caps: List<Pair<String, Boolean>>) = BandLockProfiles.classify(
        Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL, Build.HARDWARE, channel, caps.count { it.second },
    )

    fun refresh() = run(BandLockCommand.probe(Build.MODEL, Build.SOC_MODEL))
    fun clearLog() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(log = "") }

    fun run(command: BandLockCommand, fromConsole: Boolean = false) {
        val current = mutableState.value
        val kind = BandLockVerification.writeKind(command)
        val lteBackupState = BandLockVerification.inspectBackup(
            File(backupDirectory(), BandLockVerification.LTE_BACKUP), BandLockVerification.LTE_BACKUP_BYTES,
        )
        val decision = BandLockVerification.decideWrite(
            current.availability, current.busy, kind, lteBackupState,
            BandLockVerification.readOrigin(backupDirectory()), identity,
        )
        if (decision == WriteDecision.Busy) return
        if (decision != WriteDecision.Allowed) {
            mutableState.update {
                it.copy(
                    result = BandLockVerification.resultMessage(decision),
                    lteBackup = if (kind == LockWriteKind.RestoreLteBands) false else it.lteBackup,
                )
            }
            return
        }
        val probe = kind == LockWriteKind.Probe
        mutableState.update {
            it.copy(busy = true, result = null, availability = if (probe) LockAvailability.Checking else it.availability)
        }
        viewModelScope.launch {
            try {
                val execution = withContext(Dispatchers.IO) { command.executeWithMode(execute = ::execute) }
                val outcome = execution.outcome
                val snapshot = mutableState.value
                val ready = outcome.exit == 0 && outcome.output.lineSequence().any { it == "RESULT ready" }
                val accepted = outcome.accepted
                val revoked = BandLockVerification.rootRevoked(outcome.output) || outcome.channelUnavailable
                val probedChannel = if (probe) outcome.output.lineSequence()
                    .firstOrNull { it.startsWith("CHANNEL ") }
                    ?.removePrefix("CHANNEL ")?.trim().orEmpty() else snapshot.channel
                val probedCaps = if (probe) parseCaps(outcome.output) else snapshot.caps
                val profile = if (probe) classify(probedChannel.takeIf { ready }, probedCaps) else snapshot.profile
                val directory = backupDirectory()
                val lteFile = BandLockVerification.inspectBackup(
                    File(directory, BandLockVerification.LTE_BACKUP), BandLockVerification.LTE_BACKUP_BYTES,
                )
                val nrFile = BandLockVerification.inspectBackup(
                    File(directory, BandLockVerification.NR_BACKUP), BandLockVerification.NR_BACKUP_BYTES,
                )
                val lteConfirmed = lteFile == BackupFileState.Usable &&
                    if (probe && ready) outcome.output.contains("BACKUP lte 1") else
                        snapshot.lteBackup || (accepted && command.arguments.first() == "lte-band")
                val nrConfirmed = nrFile == BackupFileState.Usable &&
                    if (probe && ready) outcome.output.contains("BACKUP nr 1") else
                        snapshot.nrBackup || (accepted && command.arguments.first() == "nr-band")
                val availability = when {
                    probe && revoked -> LockAvailability.Unavailable
                    probe && ready && profile.canLock -> LockAvailability.Ready
                    probe -> LockAvailability.Unavailable
                    revoked -> LockAvailability.Unavailable
                    else -> snapshot.availability
                }
                mutableState.value = snapshot.copy(
                    busy = false,
                    availability = availability,
                    channel = if (probe) probedChannel else snapshot.channel,
                    caps = if (probe) probedCaps else snapshot.caps,
                    profile = profile,
                    lteBackup = lteConfirmed && availability == LockAvailability.Ready,
                    nrBackup = nrConfirmed && availability == LockAvailability.Ready,
                    lteBackupHint = if (probe) lteConfirmed else snapshot.lteBackupHint || lteConfirmed,
                    nrBackupHint = if (probe) nrConfirmed else snapshot.nrBackupHint || nrConfirmed,
                    appliedMode = when {
                        execution.modeConfirmed == true -> command.modeAfterLock
                        accepted && command.arguments.first() == "mode" ->
                            LockNetworkMode.entries.firstOrNull { it.argument == command.arguments.getOrNull(1) }
                        else -> snapshot.appliedMode
                    },
                    result = when {
                        fromConsole || probe -> null
                        execution.modeConfirmed == false -> R.string.lock_mode_unconfirmed
                        execution.modeConfirmed == true -> R.string.lock_and_mode_accepted
                        outcome.restartRequested -> R.string.lock_restart_sent
                        accepted -> R.string.lock_accepted
                        else -> R.string.lock_failed
                    },
                )
                if (availability == LockAvailability.Ready) saveProbe(mutableState.value)
                else if (probe) probeCache.edit().remove(probeCacheKey).apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog(error.message ?: error.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    availability = if (BandLockVerification.writeKind(command) == LockWriteKind.Probe)
                        LockAvailability.Unavailable else mutableState.value.availability,
                    profile = if (BandLockVerification.writeKind(command) == LockWriteKind.Probe)
                        BandLockProfiles.classify(
                            Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL, Build.HARDWARE, null, 0,
                        ) else mutableState.value.profile,
                    result = if (fromConsole || BandLockVerification.writeKind(command) == LockWriteKind.Probe) null
                    else R.string.lock_failed,
                )
                if (BandLockVerification.writeKind(command) == LockWriteKind.Probe) {
                    probeCache.edit().remove(probeCacheKey).apply()
                }
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

    private suspend fun execute(command: BandLockCommand): BandLockOutcome = try {
        runHelper(command)
    } catch (error: IOException) {
        appendLog(error.message ?: error.javaClass.simpleName)
        BandLockOutcome(1, "")
    }

    private suspend fun runHelper(command: BandLockCommand): BandLockOutcome {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libwireless_band_lock.so")
        if (!helper.isFile) throw IOException("The lock helper is unavailable for this ABI.")
        val directory = backupDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot open the private backup directory.")
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val session = File(directory, "session-identity")
        BandLockVerification.encodeOrigin(identity)?.let { session.writeText(it) }
        val arguments = listOf(helper.absolutePath, "--state-dir", directory.absolutePath) + command.arguments
        appendLog("${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} > ${command.arguments.joinToString(" ")}")
        val running = ProcessBuilder("su", "-c", "exec " + arguments.joinToString(" ", transform = ::quote))
            .redirectErrorStream(true).start()
        process = running
        try {
            running.outputStream.close()
            val output = StringBuilder()
            val pending = StringBuilder()
            val input = running.inputStream
            val bytes = ByteArray(4096)
            val started = SystemClock.elapsedRealtime()
            while (running.isAlive || input.available() > 0) {
                if (SystemClock.elapsedRealtime() - started > 110_000) {
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
                    appendLog(pending.substring(0, end).trimEnd('\r'))
                    pending.delete(0, end + 1)
                    end = pending.indexOf("\n")
                }
            }
            if (pending.isNotEmpty()) appendLog(pending.toString())
            return BandLockOutcome(running.waitFor(), output.toString())
        } finally {
            session.delete()
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
