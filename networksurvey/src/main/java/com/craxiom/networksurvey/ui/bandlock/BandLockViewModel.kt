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
/* QMI instant lock: a separate backend from the NV/EFS route, so its own state. */
internal data class QmiLockState(
    val availability: LockAvailability = LockAvailability.Unchecked,
    val busy: Boolean = false,
    val log: String = "",
    val result: Int? = null,
)
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
    private var collectorProved = false
    private val qmiMutable = MutableStateFlow(QmiLockState())
    val qmi = qmiMutable.asStateFlow()
    internal var qmiRunner: QmiLockRunner = QmiLockRunner {
        File(app.applicationInfo.nativeLibraryDir, "libwireless_qmi_probe.so")
    }
    internal var commandRunner: BandLockCommandRunner = BandLockHelperSession(
        helper = { File(app.applicationInfo.nativeLibraryDir, "libwireless_band_lock.so") },
        directory = ::backupDirectory,
        identityText = { BandLockVerification.encodeOrigin(identity) },
    )

    init {
        restoreProbe()?.let { mutableState.value = it }
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

    fun refresh(collectorRunning: Boolean = false) =
        run(BandLockCommand.probe(Build.MODEL, Build.SOC_MODEL), collectorRunning = collectorRunning)

    fun clearLog() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(log = "") }

    /* ---- QMI instant lock (independent of the NV/EFS backend) ---- */

    fun qmiCheck() = qmiRun(QmiLockCommand.probe())

    fun qmiApplyBands(radio: LockRadio, bands: String) {
        val arguments = runCatching { QmiLockCommand.applyBands(radio, bands) }.getOrNull()
        if (arguments == null) {
            qmiMutable.value = qmiMutable.value.copy(result = R.string.lock_qmi_failed)
            return
        }
        qmiRun(arguments)
    }

    fun qmiRestoreBands() = qmiRun(QmiLockCommand.restoreBands())

    fun qmiLockCell(radio: LockRadio, channel: String, pci: String, scs: Int) {
        val arguments = runCatching { QmiLockCommand.lockCell(radio, channel, pci, scs) }.getOrNull()
        if (arguments == null) {
            qmiMutable.value = qmiMutable.value.copy(result = R.string.lock_qmi_failed)
            return
        }
        qmiRun(arguments)
    }

    fun qmiClearCell(radio: LockRadio) = qmiRun(QmiLockCommand.clearCell(radio))

    fun qmiClearLog() { if (!qmiMutable.value.busy) qmiMutable.value = qmiMutable.value.copy(log = "") }

    private fun qmiRun(arguments: List<String>) {
        if (qmiMutable.value.busy) return
        qmiMutable.value = qmiMutable.value.copy(busy = true, result = null)
        viewModelScope.launch {
            val outcome = qmiRunner.run(arguments)
            AppLogger.i(AppLogger.BAND_LOCK_TAG,
                "qmi ${arguments.firstOrNull() ?: "?"} exit=${outcome.exit} verdict=${outcome.verdict}")
            val current = qmiMutable.value
            qmiMutable.value = current.copy(
                busy = false,
                availability = if (outcome.reachable) LockAvailability.Ready else LockAvailability.Unavailable,
                log = (current.log + outcome.output).takeLast(8000),
                result = if (outcome.accepted) null else R.string.lock_qmi_failed,
            )
        }
    }

    internal fun restoreForTest(state: BandLockState) {
        mutableState.value = state
    }

    fun run(
        command: BandLockCommand,
        fromConsole: Boolean = false,
        collectorRunning: Boolean = false,
        onModemRestartRequested: (() -> Unit)? = null,
    ) {
        val current = mutableState.value
        if (current.busy) return
        val kind = BandLockVerification.writeKind(command)
        val confirmCached = current.availability == LockAvailability.Cached && kind != LockWriteKind.Probe
        if (!confirmCached) {
            val decision = writeDecision(command, current)
            if (decision != WriteDecision.Allowed) {
                rejectWrite(kind, decision)
                return
            }
        }
        val probe = kind == LockWriteKind.Probe
        collectorProved = collectorRunning
        val proof = BandLockSessionPolicy.plan(
            collectorRunning, commandRunner.helperConnected, current.channel,
            if (probe || confirmCached) LockWriteKind.Probe else kind,
        )
        mutableState.update {
            it.copy(
                busy = true, result = null,
                availability = if ((probe || confirmCached) && proof.announceRootDiagCheck)
                    LockAvailability.Checking else it.availability,
            )
        }
        viewModelScope.launch {
            try {
                BandLockVerification.executeWhenConfirmed(
                    current.availability, kind,
                    confirm = {
                        val check = BandLockCommand.probe(Build.MODEL, Build.SOC_MODEL)
                        val checked = BandLockExecution(execute(check, recordDiagnostics = !fromConsole))
                        updateExecution(check, checked, fromConsole, keepBusy = true)
                        (mutableState.value.availability == LockAvailability.Ready).also { confirmed ->
                            if (!confirmed && !fromConsole) {
                                mutableState.update { it.copy(result = R.string.lock_write_blocked_unconfirmed) }
                            }
                        }
                    },
                    execute = {
                        val decision = writeDecision(command, mutableState.value.copy(busy = false))
                        if (decision == WriteDecision.Allowed) {
                            val execution = command.executeWithMode(onModemRestartRequested = {
                                withContext(Dispatchers.Main) { onModemRestartRequested?.invoke() }
                            }) { step -> execute(step, recordDiagnostics = !fromConsole) }
                            updateExecution(command, execution, fromConsole)
                        } else {
                            rejectWrite(kind, decision)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog(error.message ?: error.javaClass.simpleName)
                val checking = probe || mutableState.value.availability == LockAvailability.Checking
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    availability = if (checking)
                        LockAvailability.Unavailable else mutableState.value.availability,
                    profile = if (checking)
                        BandLockProfiles.classify(
                            Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL, Build.HARDWARE, null, 0,
                        ) else mutableState.value.profile,
                    result = if (fromConsole || probe) null else R.string.lock_failed,
                )
                if (checking) probeCache.edit().remove(probeCacheKey).apply()
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun writeDecision(command: BandLockCommand, snapshot: BandLockState): WriteDecision =
        BandLockVerification.decideWrite(
            snapshot.availability, snapshot.busy, BandLockVerification.writeKind(command),
            BandLockVerification.inspectBackup(
                File(backupDirectory(), BandLockVerification.LTE_BACKUP),
                BandLockVerification.LTE_BACKUP_BYTES,
                nativeConfirmed = snapshot.availability == LockAvailability.Ready && snapshot.lteBackup,
            ),
            BandLockVerification.readOrigin(backupDirectory()), identity,
        )

    private fun rejectWrite(kind: LockWriteKind, decision: WriteDecision) {
        mutableState.update {
            it.copy(
                result = BandLockVerification.resultMessage(decision),
                lteBackup = if (kind == LockWriteKind.RestoreLteBands) false else it.lteBackup,
            )
        }
    }

    private fun updateExecution(
        command: BandLockCommand,
        execution: BandLockExecution,
        fromConsole: Boolean,
        keepBusy: Boolean = false,
    ) {
        val outcome = execution.outcome
        val snapshot = mutableState.value
        val probe = BandLockVerification.writeKind(command) == LockWriteKind.Probe
        val ready = outcome.exit == 0 && outcome.output.lineSequence().any { it == "RESULT ready" }
        val accepted = outcome.accepted
        val revoked = BandLockVerification.rootRevoked(outcome.output) || outcome.channelUnavailable
        val probedChannel = if (probe) outcome.output.lineSequence()
            .firstOrNull { it.startsWith("CHANNEL ") }
            ?.removePrefix("CHANNEL ")?.trim().orEmpty() else snapshot.channel
        val probedCaps = if (probe) parseCaps(outcome.output) else snapshot.caps
        val profile = if (probe) classify(probedChannel.takeIf { ready }, probedCaps) else snapshot.profile
        val directory = backupDirectory()
        val lteNativeConfirmed = if (probe && ready) outcome.output.contains("BACKUP lte 1")
        else snapshot.lteBackup || (accepted && command.arguments.first() == "lte-band")
        val nrNativeConfirmed = when {
            outcome.baselineUnverified -> false
            probe && ready -> outcome.output.contains("BACKUP nr 1")
            else -> snapshot.nrBackup || (accepted && command.arguments.first() == "nr-band")
        }
        val lteConfirmed = lteNativeConfirmed && BandLockVerification.inspectBackup(
            File(directory, BandLockVerification.LTE_BACKUP), BandLockVerification.LTE_BACKUP_BYTES,
            nativeConfirmed = lteNativeConfirmed,
        ) == BackupFileState.Usable
        val nrConfirmed = nrNativeConfirmed && BandLockVerification.inspectBackup(
            File(directory, BandLockVerification.NR_BACKUP), BandLockVerification.NR_BACKUP_BYTES,
            nativeConfirmed = nrNativeConfirmed,
        ) == BackupFileState.Usable
        val availability = when {
            probe && ready && profile.canLock && !revoked -> LockAvailability.Ready
            probe || revoked -> LockAvailability.Unavailable
            else -> snapshot.availability
        }
        mutableState.value = snapshot.copy(
            busy = keepBusy,
            availability = availability,
            channel = if (probe) probedChannel else snapshot.channel,
            caps = if (probe) probedCaps else snapshot.caps,
            profile = profile,
            lteBackup = lteConfirmed && availability == LockAvailability.Ready,
            nrBackup = nrConfirmed && availability == LockAvailability.Ready,
            lteBackupHint = if (probe) lteConfirmed else snapshot.lteBackupHint || lteConfirmed,
            nrBackupHint = if (probe || outcome.baselineUnverified) nrConfirmed else snapshot.nrBackupHint || nrConfirmed,
            appliedMode = when {
                execution.modeConfirmed == true -> command.modeAfterLock
                accepted && command.arguments.first() == "mode" ->
                    LockNetworkMode.entries.firstOrNull { it.argument == command.arguments.getOrNull(1) }
                else -> snapshot.appliedMode
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
        if (availability == LockAvailability.Ready) saveProbe(mutableState.value)
        else if (probe || revoked) probeCache.edit().remove(probeCacheKey).apply()
    }

    /** Command console entry: run a raw native command line and stream its
     * output to the log without overwriting the standard result line. */
    fun runRaw(text: String) {
        run(BandLockCommand.console(text), fromConsole = true)
    }

    private suspend fun execute(command: BandLockCommand, recordDiagnostics: Boolean): BandLockOutcome = try {
        runHelper(command, recordDiagnostics).also { outcome ->
            if (BandLockVerification.rootRevoked(outcome.output)) {
                withContext(Dispatchers.Main) { invalidateSession() }
            }
        }
    } catch (error: IOException) {
        withContext(Dispatchers.Main) { invalidateSession() }
        appendLog(error.message ?: error.javaClass.simpleName)
        BandLockOutcome(1, "")
    }

    private fun invalidateSession() {
        commandRunner.close()
        mutableState.update {
            it.copy(availability = LockAvailability.Unavailable, lteBackup = false, nrBackup = false)
        }
        probeCache.edit().remove(probeCacheKey).apply()
    }

    private suspend fun runHelper(command: BandLockCommand, recordDiagnostics: Boolean): BandLockOutcome {
        val started = SystemClock.elapsedRealtime()
        val name = command.arguments.first()
        val plan = BandLockSessionPolicy.plan(
            collectorProved, commandRunner.helperConnected, mutableState.value.channel,
            BandLockVerification.writeKind(command),
        )
        fun diagnostic(message: String) {
            if (recordDiagnostics) AppLogger.i(AppLogger.BAND_LOCK_TAG, "run=$started cmd=$name $message")
        }
        fun helperLine(line: String) {
            if (line.startsWith("NR_BAND ") || line.startsWith("SSR ") || line.startsWith("LOCK_DIAG ")) {
                diagnostic(line)
                return
            }
            appendLog(line)
            if (listOf("ACK ", "CHANNEL ", "RESULT ", "BACKUP ", "CAP ")
                    .any(line::startsWith) ||
                line.startsWith("No recognized modem acknowledgement.")) diagnostic(line)
        }
        diagnostic("start model=${Build.MODEL} soc=${Build.SOC_MODEL} baseband=${Build.getRadioVersion().orEmpty()} " +
            "transport=${plan.transport} discover=${plan.discoverChannel} spawn=${plan.spawnRootShell} " +
            "collector=$collectorProved args=${command.arguments.drop(1).joinToString(",")}")
        appendLog("${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} > ${command.arguments.joinToString(" ")}")
        return commandRunner.run(command, plan, ::helperLine, ::diagnostic)
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
        commandRunner.close()
        super.onCleared()
    }

}
