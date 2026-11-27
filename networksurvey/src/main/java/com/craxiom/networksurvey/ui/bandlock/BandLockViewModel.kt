package com.craxiom.networksurvey.ui.bandlock

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.util.AppLogger
import com.craxiom.networksurvey.util.DiagJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

internal enum class LockAvailability { Unchecked, Cached, Checking, Ready, Unavailable }
internal data class QmiLockState(
    val availability: LockAvailability = LockAvailability.Unchecked,
    val busy: Boolean = false,
    val log: String = "",
    val result: String? = null,
    val backends: Map<QmiLockSlot, LockBackend> = emptyMap(),
    val pendingDiagFallback: BandLockCommand? = null,
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
    private val backendPreferences = app.getSharedPreferences("band_lock_backend", 0)
    private val qmiMutable = MutableStateFlow(QmiLockState(backends = loadBackends()))
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

    private fun loadBackends(): Map<QmiLockSlot, LockBackend> = buildMap {
        for (radio in LockRadio.entries) for (target in LockTarget.entries) {
            val slot = QmiLockSlot(radio, target)
            val backend = backendPreferences.getString(slot.preferenceKey, null)
            LockBackend.entries.firstOrNull { it.name == backend }?.let { put(slot, it) }
        }
    }

    private suspend fun rememberBackend(slot: QmiLockSlot?, backend: LockBackend?) {
        if (slot == null || qmiMutable.value.backends[slot] == backend) return
        withContext(Dispatchers.IO) {
            val editor = backendPreferences.edit()
            if (backend == null) editor.remove(slot.preferenceKey)
            else editor.putString(slot.preferenceKey, backend.name)
            // Persist before sending so that an unconfirmed write can still be cleared after a crash.
            check(editor.commit()) { "Could not save lock backend" }
        }
        qmiMutable.update {
            val backends = if (backend == null) it.backends - slot else it.backends + (slot to backend)
            it.copy(backends = backends)
        }
    }

    fun checkPreferred(preferDiag: Boolean, collectorRunning: Boolean) {
        if (preferDiag) {
            refresh(collectorRunning)
        } else {
            qmiRun("probe", onUnavailable = { refresh(collectorRunning) }) { step ->
                QmiLockExecution(step(QmiLockCommand.probe()))
            }
        }
    }

    fun runPreferred(
        command: BandLockCommand,
        preferDiag: Boolean,
        collectorRunning: Boolean,
        onModemRestartRequested: () -> Unit,
    ) {
        val plan = command.qmi
        val backends = qmiMutable.value.backends
        val legacy = {
            run(
                command,
                collectorRunning = collectorRunning,
                onModemRestartRequested = onModemRestartRequested,
                confirmOnDemand = true,
                preferDiag = preferDiag,
            )
        }
        if (plan == null || preferredLockBackend(plan, preferDiag) == LockBackend.Diag) {
            legacy()
            return
        }
        val previous = plan.slot?.let(backends::get)
        val fallback = legacy.takeIf { plan.allowsDiagFallback() }
        qmiRun(
            command.arguments.first(),
            plan.mode,
            R.string.lock_accepted,
            onUnavailable = fallback,
            rejectedFallback = command.takeIf { plan.allowsDiagFallback() },
        ) { step ->
            val result = plan.execute(beforeWrite = { rememberBackend(plan.slot, LockBackend.Qmi) }, run = step)
            if (!result.outcome.accepted) {
                rememberBackend(plan.slot, previous)
            } else if (result.modeConfirmed && plan.restoring) {
                rememberBackend(plan.slot, null)
                clearRelatedQmiSlots(plan.slot)
            }
            result
        }
    }

    private suspend fun clearRelatedQmiSlots(slot: QmiLockSlot?) {
        if (slot == null) return
        // bands-restore applies the modem's full hardware masks for both technologies.
        val related = if (slot.target == LockTarget.Bands) {
            QmiLockSlot(if (slot.radio == LockRadio.Nr) LockRadio.Lte else LockRadio.Nr, LockTarget.Bands)
        } else slot.relatedQmiSlot()
        if (related != null && qmiMutable.value.backends[related] == LockBackend.Qmi) {
            rememberBackend(related, null)
        }
    }

    fun qmiClearLog() { if (!qmiMutable.value.busy) qmiMutable.value = qmiMutable.value.copy(log = "") }

    internal fun dismissDiagFallback() {
        qmiMutable.update { it.copy(pendingDiagFallback = null) }
    }

    internal fun retryPendingWithDiag(
        collectorRunning: Boolean,
        onModemRestartRequested: () -> Unit,
    ) {
        val command = qmiMutable.value.pendingDiagFallback ?: return
        qmiMutable.update { it.copy(pendingDiagFallback = null, result = null) }
        run(
            command = command,
            collectorRunning = collectorRunning,
            onModemRestartRequested = onModemRestartRequested,
            confirmOnDemand = true,
            preferDiag = true,
        )
    }

    private fun qmiRun(
        operation: String,
        mode: LockNetworkMode? = null,
        @StringRes success: Int? = null,
        onUnavailable: (() -> Unit)? = null,
        rejectedFallback: BandLockCommand? = null,
        action: suspend (suspend (List<String>) -> QmiLockOutcome) -> QmiLockExecution,
    ) {
        if (qmiMutable.value.busy || mutableState.value.busy) return
        val started = SystemClock.elapsedRealtime()
        fun report(stage: String, detail: String) {
            DiagJournal.append(app, JSONObject().put("type", "band_lock").put("source", "qmi")
                .put("run_id", started).put("operation", operation).put("stage", stage)
                .put("detail", detail).put("t", System.currentTimeMillis()))
        }
        report("start", "model=${Build.MODEL} soc=${Build.SOC_MODEL} api=${Build.VERSION.SDK_INT}")
        mutableState.update { it.copy(result = null) }
        qmiMutable.update { it.copy(busy = true, result = null, pendingDiagFallback = null) }
        viewModelScope.launch {
            var fallback = false
            try {
                val execution = action { args ->
                    val name = args.first()
                    val requestedMode = if (name == "mode" || "--mode" in args) args.last() else null
                    report("qmi_command", "command=$name mode=$requestedMode")
                    qmiRunner.run(args).also { step ->
                        report("qmi_result", "command=$name exit=${step.exit} verdict=${step.verdict} error=${step.errorCode}")
                        step.output.lineSequence().filter { it.startsWith("QMI $name ") || it.startsWith("QMI readback ") }
                            .take(16).forEach { report("native", it.take(200)) }
                        qmiMutable.update { it.copy(log = (it.log + step.output).takeLast(8000)) }
                    }
                }
                val outcome = execution.outcome
                fallback = onUnavailable != null && execution.mayFallbackToDiag
                if (fallback) report("fallback", "QMI unavailable before write")
                report("finish", "exit=${outcome.exit} verdict=${outcome.verdict} error=${outcome.errorCode} " +
                    "reachable=${outcome.reachable} mode=${mode?.argument} " +
                    "mode_confirmed=${execution.modeConfirmed.takeIf { mode != null }} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                if (mode != null && execution.modeConfirmed) mutableState.update { it.copy(appliedMode = mode) }
                qmiMutable.update {
                    it.copy(
                        availability = when {
                            outcome.reachable -> LockAvailability.Ready
                            execution.mayFallbackToDiag -> LockAvailability.Unavailable
                            else -> it.availability
                        },
                        result = when {
                            fallback -> null
                            execution.modeOutcome?.accepted == false -> app.getString(R.string.lock_mode_unconfirmed)
                            mode != null && execution.modeConfirmed && operation != "mode" ->
                                app.getString(R.string.lock_and_mode_accepted)
                            outcome.accepted -> success?.let(app::getString)
                            else -> qmiResultMessage(outcome)
                        },
                        pendingDiagFallback = rejectedFallback?.takeIf {
                            execution.requiresDiagFallbackConfirmation
                        } ?: it.pendingDiagFallback,
                    )
                }
            } catch (cancelled: CancellationException) {
                report("cancelled", "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                throw cancelled
            } catch (error: Exception) {
                report("exception", "class=${error.javaClass.simpleName}")
                qmiMutable.update { it.copy(result = app.getString(R.string.lock_qmi_failed)) }
            } finally {
                qmiMutable.update { it.copy(busy = false) }
            }
            if (fallback) onUnavailable?.invoke()
        }
    }

    /* Map a rejected QMI run to a readable message. The raw output stays in the
       log pane, so the code is explained rather than hidden. */
    private fun qmiResultMessage(outcome: QmiLockOutcome): String? = when {
        outcome.accepted -> null
        outcome.verdict == "unavailable" -> app.getString(R.string.lock_qmi_unavailable)
        outcome.verdict == "rejected" -> outcome.errorCode?.let { code ->
            if (code == 3) app.getString(R.string.lock_qmi_error_internal)
            else app.getString(R.string.lock_qmi_rejected_code, code)
        } ?: app.getString(R.string.lock_qmi_failed)
        else -> app.getString(R.string.lock_qmi_failed)
    }

    internal fun restoreForTest(state: BandLockState) {
        mutableState.value = state
    }

    fun run(
        command: BandLockCommand,
        fromConsole: Boolean = false,
        collectorRunning: Boolean = false,
        onModemRestartRequested: (() -> Unit)? = null,
        confirmOnDemand: Boolean = false,
        preferDiag: Boolean = false,
    ) {
        val current = mutableState.value
        if (current.busy || qmiMutable.value.busy) return
        val kind = BandLockVerification.writeKind(command)
        val needsConfirmation = kind != LockWriteKind.Probe && current.availability != LockAvailability.Ready &&
            (current.availability == LockAvailability.Cached || confirmOnDemand)
        if (!needsConfirmation) {
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
            if (probe || needsConfirmation) LockWriteKind.Probe else kind,
        )
        qmiMutable.update { it.copy(result = null) }
        mutableState.update {
            it.copy(
                busy = true, result = null,
                availability = if ((probe || needsConfirmation) && proof.announceRootDiagCheck)
                    LockAvailability.Checking else it.availability,
            )
        }
        viewModelScope.launch {
            try {
                BandLockVerification.executeWhenConfirmed(
                    if (needsConfirmation) LockAvailability.Cached else current.availability, kind,
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
                            val slot = command.qmi?.slot
                            val previous = slot?.let { qmiMutable.value.backends[it] }
                            rememberBackend(slot, LockBackend.Diag)
                            val execution = command.executeWithMode(
                                onModemRestartRequested = {
                                    withContext(Dispatchers.Main) { onModemRestartRequested?.invoke() }
                                },
                                executeQmiMode = if (fromConsole || preferDiag) null else ::runQmiMode,
                            ) { step -> execute(step, recordDiagnostics = !fromConsole) }
                            when {
                                !execution.outcome.accepted || execution.outcome.channelUnavailable || execution.outcome.baselineUnverified ->
                                    rememberBackend(slot, previous)
                                execution.outcome.accepted && execution.modeConfirmed == true &&
                                    command.qmi?.restoring == true -> rememberBackend(slot, null)
                            }
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

    private suspend fun runQmiMode(mode: LockNetworkMode): QmiLockOutcome {
        val arguments = QmiLockCommand.mode(mode)
        val started = SystemClock.elapsedRealtime()
        AppLogger.i(AppLogger.BAND_LOCK_TAG,
            "run=$started backend=qmi cmd=mode stage=start model=${Build.MODEL} soc=${Build.SOC_MODEL} api=${Build.VERSION.SDK_INT} args=${mode.argument}")
        appendLog("${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} > qmi ${arguments.joinToString(" ")}")
        val outcome = qmiRunner.run(arguments)
        outcome.output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                appendLog(line)
                if (line.startsWith("QMI ") || line.startsWith("VERDICT ")) {
                    AppLogger.i(AppLogger.BAND_LOCK_TAG, "run=$started backend=qmi cmd=mode stage=native $line")
                }
            }
        AppLogger.i(AppLogger.BAND_LOCK_TAG,
            "run=$started backend=qmi cmd=mode stage=finish exit=${outcome.exit} verdict=${outcome.verdict} " +
                "error=${outcome.errorCode} reachable=${outcome.reachable} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        if (outcome.reachable) {
            qmiMutable.update { it.copy(availability = LockAvailability.Ready) }
        }
        if (!outcome.accepted) {
            appendLog("QMI mode ${outcome.verdict}; falling back to DIAG")
            AppLogger.i(AppLogger.BAND_LOCK_TAG, "run=$started backend=qmi cmd=mode stage=fallback detail=diag")
        }
        return outcome
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
