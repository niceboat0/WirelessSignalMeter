package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import android.os.SystemClock
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.db.SurveyTaskStore
import com.craxiom.networksurvey.db.recallSurveyLocationMode
import com.craxiom.networksurvey.db.recallSurveyRecordingFormat
import com.craxiom.networksurvey.db.rememberSurveyRecordingFormat
import com.craxiom.networksurvey.db.toJson
import com.craxiom.networksurvey.model.SurveyEvent
import com.craxiom.networksurvey.model.SurveyTask
import com.craxiom.networksurvey.model.ownsDiagnostic
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.fragments.model.CellularViewModel
import com.craxiom.networksurvey.model.CellularProtocol
import com.craxiom.networksurvey.model.AndroidNrCarriers
import com.craxiom.networksurvey.model.NrCarrier
import com.craxiom.networksurvey.ui.cellular.CellularChartViewModel
import com.craxiom.networksurvey.util.AndroidLteSinrReader
import com.craxiom.networksurvey.util.AppLogger
import com.craxiom.networksurvey.util.DiagJournal
import com.craxiom.networksurvey.util.FramePerformanceDiagnostics
import com.craxiom.networksurvey.util.LteSinrUtils
import com.craxiom.networksurvey.services.CaptureServiceNotification
import com.craxiom.networksurvey.services.RootDiagCollectorService
import com.craxiom.networksurvey.ui.bandlock.BandLockProfile
import com.craxiom.networksurvey.ui.bandlock.LockSupportLevel
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class RootDiagStatus {
    Checking, Unavailable, Denied, Starting, Unsupported, Running, Stopped,
}

internal data class DiagRecord(
    val id: Long, val receivedAt: Long, val data: JSONObject,
    val taskId: String? = if (data.isNull("task_id")) null else data.optString("task_id").takeIf(String::isNotBlank),
    val clockId: String? = if (data.isNull("clock_id")) null else data.optString("clock_id").takeIf(String::isNotBlank),
) {
    val type = data.optString("type")
    val source = data.optString("src")
    val time = data.optLong("t")
    val decodedTree = data.optString("decoded_tree")
}

internal data class RootSignalState(
    val status: RootDiagStatus = RootDiagStatus.Checking,
    val records: List<DiagRecord> = emptyList(),
    val errorCode: Int? = null,
    val errorDetails: List<String> = emptyList(),
    val cachedMeasureIds: Set<Long> = emptySet(),
    val failureReason: String? = null,
    val nrCarriers: List<NrCarrier> = emptyList(),
    val hasModemPackets: Boolean = false,
    val modemWaitTimedOut: Boolean = false,
    val lastNeighborMeasurementAt: Map<String, Long> = emptyMap(),
) {
    fun hasLiveServingCell(protocol: String?, now: Long): Boolean =
        status == RootDiagStatus.Running && records.any { record ->
            if (record.type != "serving" || record.id in cachedMeasureIds) return@any false
            val rat = record.data.optString("rat")
            val age = record.data.optLong("seen_age_ms", -1)
            (protocol == null || protocol == rat) &&
                (rat == "LTE" || rat == "NR") && record.data.optInt("pci", -1) >= 0 &&
                record.data.optInt("arfcn", -1) >= 0 && age >= 0 &&
                now - record.receivedAt + age in 0..15_000L
        }
}

internal enum class QmdlPhase { Idle, Starting, Recording, Stopping }
internal enum class QmdlResult { Saved, Limited, Interrupted, Empty, Error }
internal const val NR_RX_SAMPLE_TIMEOUT = 4_000L
internal const val DIAG_MEASUREMENT_TIMEOUT_MS = 30_000L

internal data class QmdlState(
    val phase: QmdlPhase = QmdlPhase.Idle,
    val packets: Long = 0,
    val bytes: Long = 0,
    val hasRecording: Boolean = false,
    val segments: Int = 0,
    val format: DiagLogFormat = DiagLogFormat.Qmdl,
    val result: QmdlResult? = null,
    val recovering: Boolean = false,
    val errorCode: Int? = null,
)

internal data class RecordingExportPlan(
    val files: List<File>,
    val suggestedName: String,
    val zip: Boolean,
)

class RootDiagViewModel internal constructor(
    application: Application,
    private val captureIo: CoroutineDispatcher,
    private val launchCollector: (() -> Process)? = null,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.IO)

    private val app = application
    private val signalState = MutableStateFlow(RootSignalState())
    internal val signal = signalState.asStateFlow()
    private val signalingState = MutableStateFlow<List<DiagRecord>>(emptyList())
    internal val signaling = signalingState.asStateFlow()
    private val recordingSession by lazy { DiagRecordingSession(File(app.noBackupFilesDir, "qmdl")) }
    private val locationRecording by lazy {
        RecordingLocationCoordinator(
            sidecar = RecordingLocationSidecar(File(app.noBackupFilesDir, "qmdl")),
            feed = RecordingLocationFeed(app),
            scope = viewModelScope,
            recallMode = { recallSurveyLocationMode(app, it) },
            clockId = { SurveyTaskStore.CLOCK_ID },
        )
    }
    private var qmdlTaskId: String? = null
    private var qmdlAutoStartBlockedTask: String? = null
    private var recordingFormat = DiagLogFormat.Qmdl
    private var resumeRecordingTask: String? = null
    private var resumeRecordingFormat = DiagLogFormat.Qmdl
    private var pendingExport: RecordingExportPlan? = null
    private val qmdlState = MutableStateFlow(QmdlState())
    internal val qmdl = qmdlState.asStateFlow()
    private val qmdlExport = MutableStateFlow<Int?>(null)
    private var lastQmdlStatus: JSONObject? = null
    internal val qmdlExportProgress = qmdlExport.asStateFlow()
    private val nrChart = CellularChartViewModel()
    private val lteChart = CellularChartViewModel()
    internal fun chartFor(rat: String) = if (rat == "NR") nrChart else lteChart
    // NAS/IMS configuration changes infrequently. Screen lock must not restart a collector that is still alive.
    private val lastConfiguration = linkedMapOf<String, DiagRecord>()
    private val lastMeasurements = mutableMapOf<String, DiagRecord>()
    private val diagnosticRecords = ArrayDeque<JSONObject>()
    private val measurementSamples = ArrayDeque<JSONObject>()
    private val neighborProbeSamples = linkedMapOf<String, ArrayDeque<JSONObject>>()
    private val layoutNotices = linkedMapOf<String, JSONObject>()
    private val nrCaObservations = ArrayDeque<JSONObject>()
    private val collectorRuns = ArrayDeque<JSONObject>()
    private val collectorRecovery = ArrayDeque<JSONObject>()
    private var nextCollectorRun = 1L
    private var invalidLineCount = 0L
    private var lastCollectorStatus: JSONObject? = null
    private var lastCollectorStatusAtElapsed = 0L
    private var lastSignalMode: JSONObject? = null
    private var lastAndroidNrBandwidth: JSONObject? = null
    private var overlayImsReg = "unknown"
    private var overlayImsSource = ""
    private var overlayImsAvailable: Boolean? = null
    private var overlayImsCall = "idle"
    private var overlayDropped = 0L
    private var hadBeenRunning = false
    private var restartAttempt = 0
    private var simCount = 0
    private var selectedSubscriptionId: Int? = null
    private var androidImsRefresh: Job? = null
    private val imsProviders = linkedMapOf<String, ImsProviderStatus>()
    private var lastImsEvidence: ImsEvidence? = null
    private var taskStore: SurveyTaskStore? = null
    private val processLedger = ProcessEventLedger()
    private var activeTask: SurveyTask? = null
    private var bandLockProfile = BandLockProfile(LockSupportLevel.Unsupported, "unknown", reason = "unchecked")
    private val capabilityState = MutableStateFlow(currentCapability())
    internal val capability = capabilityState.asStateFlow()
    private val collectionModeState = MutableStateFlow(DiagRecordingPolicy.Mode.Idle)
    internal val collectionMode = collectionModeState.asStateFlow()

    internal fun recordSignalMode(root: Boolean, androidProtocol: String?, simCount: Int) {
        val mode = if (root) "root_diag" else "android"
        val previous = lastSignalMode
        if (previous?.optString("mode") == mode && previous.optInt("sim_count") == simCount &&
            previous.opt("android_rat") == (androidProtocol ?: JSONObject.NULL)) return
        val state = signalState.value
        val entry = JSONObject().put("type", "signal_mode").put("t", System.currentTimeMillis())
            .put("mode", mode).put("sim_count", simCount)
            .put("android_rat", androidProtocol ?: JSONObject.NULL)
            .put("clock_id", SurveyTaskStore.CLOCK_ID)
            .put("collector_run", collectorRuns.lastOrNull()?.optLong("id") ?: JSONObject.NULL)
            .put("collector_state", state.status.name)
            .put("has_modem_packets", state.hasModemPackets)
        lastSignalMode = entry
        DiagJournal.append(app, JSONObject(entry.toString()).put("type", "signal_transition"))
    }

    internal fun recordAndroidNrBandwidth(sample: CellularViewModel.NrBandwidthDiagnostic?) {
        lastAndroidNrBandwidth = sample?.let {
            JSONObject().put("type", "android_nr_bandwidth").put("t", System.currentTimeMillis())
                .put("reported_khz", JSONArray(it.reportedKhz()))
                .put("registered_rat", it.registeredRat()).put("display_rat", it.displayRat())
                .put("single_carrier", it.singleCarrier()).put("cell_matches", it.cellMatches())
                .put("displayed_khz", it.displayedKhz())
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            DiagJournal.append(app, JSONObject()
                .put("type", "session_start")
                .put("clock_id", SurveyTaskStore.CLOCK_ID)
                .put("t", System.currentTimeMillis())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("android", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL)
                .put("baseband", Build.getRadioVersion().orEmpty()))
        }
        viewModelScope.launch {
            val segments = withContext(captureIo) {
                runCatching { recordingSession.recoverLeftoverActive() }
                recordingSession.exportableRecordings().size
            }
            if (segments > 0) qmdlState.value = qmdlState.value.copy(hasRecording = true, segments = segments)
        }
        viewModelScope.launch {
            RootDiagCollectorService.stopRequested.collect { requested ->
                if (requested) {
                    RootDiagCollectorService.stopRequested.value = false
                    pause()
                }
            }
        }
        viewModelScope.launch {
            RootDiagCollectorService.state.collect {
                if (!RootDiagCollectorService.isMaintained && !foreground && capture != null) applyCollectionPolicy()
            }
        }
        viewModelScope.launch {
            val store = withContext(captureIo) {
                runCatching { SurveyTaskStore.get(app) }.onFailure {
                    timber.log.Timber.w(it, "Could not open diagnostic task store")
                }.getOrNull()
            } ?: return@launch
            taskStore = store
            store.active.collect { task ->
                activeTask = task
                if (qmdlAutoStartBlockedTask != task?.id) qmdlAutoStartBlockedTask = null
                applyCollectionPolicy()
            }
        }
    }

    internal fun startQmdl(format: DiagLogFormat = DiagLogFormat.Qmdl) {
        recordingFormat = format
        activeTask?.id?.let { id ->
            runCatching { rememberSurveyRecordingFormat(app, id, format.name) }
        }
        requestQmdlStart(manual = true)
    }

    private fun eligibleQmdlTask(): String? =
        DiagRecordingPolicy.recordingTaskId(currentMode(), activeTask, manual = true, blockedTaskId = null)

    private fun requestQmdlStart(manual: Boolean) {
        if (!manual) {
            val task = activeTask
            if (task == null || !task.needLog) return
            val chosen = DiagRecordingPolicy.automaticRecordingFormat(
                true,
                recallSurveyRecordingFormat(app, task.id),
            ) ?: return
            recordingFormat = chosen
        }
        val taskId = DiagRecordingPolicy.recordingTaskId(currentMode(), activeTask, manual, qmdlAutoStartBlockedTask)
            ?: return
        if (capture?.isStopping != false || signalState.value.status != RootDiagStatus.Running ||
            qmdlState.value.phase != QmdlPhase.Idle || qmdlState.value.recovering || qmdlExport.value != null) return
        qmdlTaskId = taskId
        lastQmdlStatus = null
        qmdlState.value = qmdlState.value.copy(phase = QmdlPhase.Starting, packets = 0, bytes = 0,
            result = null, errorCode = null, format = recordingFormat)
    }

    internal fun stopQmdl() {
        if (qmdlState.value.phase != QmdlPhase.Recording && qmdlState.value.phase != QmdlPhase.Starting) return
        blockQmdlAutoStart()
        requestQmdlStop()
    }

    private fun blockQmdlAutoStart() {
        if (qmdlTaskId != null && qmdlTaskId == activeTask?.id) qmdlAutoStartBlockedTask = qmdlTaskId
    }

    private fun requestQmdlStop() {
        if (qmdlState.value.phase != QmdlPhase.Recording && qmdlState.value.phase != QmdlPhase.Starting) return
        qmdlState.value = qmdlState.value.copy(phase = QmdlPhase.Stopping)
    }

    private suspend fun finishQmdl(result: QmdlResult) {
        val saved = withContext(captureIo) {
            // Saved, empty, error, interrupt, and the 128 MB limit all end here, so the sidecar stops too.
            try {
                locationRecording.onSessionFinished()
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not stop location sidecar")
            }
            val bytes = if (recordingSession.isOpen()) recordingSession.activeFile.length() else 0L
            val retained = runCatching { recordingSession.finish() }.onFailure {
                timber.log.Timber.w(it, "Could not archive recording file")
            }
            val files = recordingSession.exportableRecordings()
            val outcome = when {
                retained.isFailure -> QmdlResult.Error
                bytes == 0L && result == QmdlResult.Saved -> QmdlResult.Empty
                else -> result
            }
            Triple(outcome, files, bytes)
        }
        if (saved.first in setOf(QmdlResult.Empty, QmdlResult.Limited, QmdlResult.Error)) blockQmdlAutoStart()
        qmdlTaskId = null
        qmdlState.value = qmdlState.value.copy(
            phase = QmdlPhase.Idle, result = saved.first, hasRecording = saved.second.isNotEmpty(),
            segments = saved.second.size, recovering = false, bytes = saved.third,
        )
    }

    internal fun prepareRecordingExport(stamp: String, onReady: (String) -> Unit) {
        if (qmdlExport.value != null || qmdlState.value.phase != QmdlPhase.Idle || qmdlState.value.recovering) return
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) {
                runCatching { recordingSession.recoverLeftoverActive() }
                val files = recordingSession.exportableRecordings()
                if (files.isEmpty()) null
                else if (files.size == 1) RecordingExportPlan(files, "wireless-$stamp.${files[0].extension}", false)
                else RecordingExportPlan(files, "wireless-$stamp-logs.zip", true)
            } ?: return@launch
            pendingExport = plan
            qmdlState.value = qmdlState.value.copy(hasRecording = true, segments = plan.files.size)
            onReady(plan.suggestedName)
        }
    }

    internal fun exportRecordings(uri: Uri) {
        val plan = pendingExport ?: return
        if (qmdlExport.value != null || qmdlState.value.phase != QmdlPhase.Idle || qmdlState.value.recovering) return
        pendingExport = null
        qmdlExport.value = 0
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                    val buffer = ByteArray(256 * 1024)
                    if (!plan.zip) {
                        output.use { copyRecording(plan.files.single(), it, buffer, plan.files.single().length()) }
                    } else {
                        ZipOutputStream(output).use { zip ->
                            for (file in plan.files) {
                                zip.putNextEntry(ZipEntry(file.name))
                                file.inputStream().use { input ->
                                    while (true) {
                                        ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        zip.write(buffer, 0, count)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                Toast.makeText(app, R.string.diag_qmdl_exported, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not export recording")
                Toast.makeText(app, R.string.diag_qmdl_export_failed, Toast.LENGTH_SHORT).show()
            } finally {
                qmdlExport.value = null
            }
        }
    }

    private fun copyRecording(file: File, output: java.io.OutputStream, buffer: ByteArray, total: Long) {
        file.inputStream().use { input ->
            var written = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
                if (total > 0) qmdlExport.value = (100 * written / total).toInt().coerceIn(0, 99)
            }
        }
    }

    internal suspend fun exportDiagnosticLog(uri: Uri, includeMeasurementSamples: Boolean) {
        val snapshot = signalState.value
        val records = diagnosticRecords.toList()
        val layouts = layoutNotices.values.toList()
        val samples = if (includeMeasurementSamples) measurementSamples.toList() else emptyList()
        val probeSamples = if (includeMeasurementSamples) neighborProbeSamples.values.flatten() else emptyList()
        val collectorStatus = lastCollectorStatus
        val qmdlStatus = lastQmdlStatus
        capture?.updatePerformanceSnapshot()
        val runs = collectorRuns.map { JSONObject(it.toString()) }
        val recovery = collectorRecovery.toList()
        val notifications = CaptureServiceNotification.diagnostics(app)
        val signalMode = lastSignalMode
        val androidNrBandwidth = lastAndroidNrBandwidth
        val androidLteSinr = AndroidLteSinrReader.snapshot()
        val androidNrBatches = AndroidNrCarriers.diagnostics()
        val rootNrCa = nrCaObservations.toList()
        val neighborTables = neighborTableDiagnostics(snapshot)
        val invalidLines = invalidLineCount
        val ims = currentCapability().imsStatus
        val rawJournal = withContext(Dispatchers.IO) {
            DiagJournal.flush(app)
            DiagJournal.read(app)
        }
        val journal = DiagJournal.filterExportLines(rawJournal, SurveyTaskStore.CLOCK_ID)
        withContext(Dispatchers.IO) {
            val tasks = SurveyTaskStore.get(app)
            val taskIds = (snapshot.records.map { it.data } + records + samples + probeSamples + rootNrCa)
                .mapNotNull { if (it.isNull("task_id")) null else it.optString("task_id").takeIf(String::isNotBlank) }.toMutableSet()
            snapshot.records.mapNotNullTo(taskIds) { it.taskId }
            androidLteSinr.mapNotNullTo(taskIds) { it.taskId() }
            tasks.active.value?.id?.let(taskIds::add)
            val header = JSONObject()
                .put("type", "export")
                .put("schema", 4)
                .put("clock_id", SurveyTaskStore.CLOCK_ID)
                .put("active_task_id", tasks.active.value?.id ?: JSONObject.NULL)
                .put("tasks", JSONArray(taskIds.map { id -> tasks.taskMetadata(id).apply {
                    put("mission_count", optJSONArray("mission_ids")?.length() ?: 0)
                    remove("mission_ids")
                } }))
                .put("task_association", "captured_at_sample; missing task_id remains unassigned")
                .put("time_ms", System.currentTimeMillis())
                .put("ims_status", JSONObject()
                    .put("headline", ims.headlineKey)
                    .put("registration", ims.registration.name)
                    .put("source", ims.evidenceSource ?: JSONObject.NULL)
                    .put("technical", ims.technical))
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_package", app.packageName)
                .put("diag_recovery_policy", "post_modem_watch_v2")
                .put("android", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("hardware", Build.HARDWARE)
                .put("soc_manufacturer", Build.SOC_MANUFACTURER)
                .put("soc_model", Build.SOC_MODEL)
                .put("baseband", Build.getRadioVersion().orEmpty())
                .put("collector_state", snapshot.status.name)
                .put("frame_performance", FramePerformanceDiagnostics.snapshot())
                .put("error_code", snapshot.errorCode ?: JSONObject.NULL)
                .put("invalid_json_lines", invalidLines)
                .put("journal_lines", journal.size)
                .put("journal_stale_heartbeats_filtered", rawJournal.size - journal.size)
                .put("journal_dropped", DiagJournal.droppedLines())
                .put("measurement_samples_included", includeMeasurementSamples)
                .put("neighbor_probe_samples", probeSamples.size)
                .put("android_lte_sinr_samples", androidLteSinr.size)
                .put("android_lte_sinr_policy", AndroidLteSinrReader.POLICY)
                .put("qmdl_contains_raw_signaling", true)
                .put("qmdl_privacy", "Raw QMDL includes complete RRC, NAS and possibly SIP payloads with identifiers. Do not share it.")
                .put("measurement_samples_redacted", includeMeasurementSamples)
            val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(header.toString())
                writer.appendLine(notifications.toString())
                for (entry in AppLogger.readBandLockDiagnostics()) {
                    writer.appendLine(JSONObject().put("type", "band_lock").put("log", entry).toString())
                }
                for (entry in AppLogger.readFailureDiagnostics()) writer.appendLine(entry.toString())
                for (taskId in taskIds) {
                    val events = tasks.events(taskId)
                    writer.appendLine(JSONObject().put("type", "task_event_window").put("task_id", taskId)
                        .put("limit", SurveyTaskStore.EVENT_LIMIT).put("limited", events.size > SurveyTaskStore.EVENT_LIMIT).toString())
                    for (event in events.takeLast(SurveyTaskStore.EVENT_LIMIT)) {
                        // Diagnostic exports retain the existing identity-redaction boundary.
                        writer.appendLine(event.toJson().apply { remove("detail") }.toString())
                    }
                }
                collectorStatus?.let { writer.appendLine(redactDiagnostic(it).toString()) }
                qmdlStatus?.let { writer.appendLine(redactDiagnostic(it).toString()) }
                for (entry in runs) writer.appendLine(redactDiagnostic(entry).toString())
                signalMode?.let { writer.appendLine(it.toString()) }
                androidNrBandwidth?.let { writer.appendLine(it.toString()) }
                for (entry in androidLteSinr) writer.appendLine(entry.toDiagnosticJson().toString())
                for (entry in androidNrBatches) {
                    writer.appendLine(JSONObject().put("type", "android_nr_carriers")
                        .put("t", entry.timeMs()).put("elapsed_ms", entry.elapsedMs())
                        .put("subscription_id", entry.subscriptionId()).put("rat", entry.anchorRat())
                        .put("nr_mode", entry.nrMode()).put("nr_rows", entry.nrRows())
                        .put("serving_nr_rows", entry.servingNrRows()).put("fresh_rows", entry.freshRows())
                        .put("retained_rows", entry.retainedRows()).put("oldest_fresh_age_ms", entry.oldestFreshAgeMs())
                        .toString())
                }
                for (entry in rootNrCa) writer.appendLine(entry.toString())
                for (entry in neighborTables) writer.appendLine(entry.toString())
                for (entry in layouts) writer.appendLine(redactDiagnostic(entry).toString())
                for (entry in snapshot.records) writer.appendLine(redactDiagnostic(entry.data)
                    .put("task_id", entry.taskId ?: JSONObject.NULL).put("clock_id", entry.clockId ?: JSONObject.NULL).toString())
                /* The journal outlives this process and already contains every
                   record mirrored from recordDiagnostic (redacted). */
                if (journal.isNotEmpty()) {
                    for (line in journal) writer.appendLine(line)
                } else {
                    for (entry in recovery) writer.appendLine(entry.toString())
                    for (entry in records) writer.appendLine(redactDiagnostic(entry).toString())
                }
                for (entry in samples) writer.appendLine(redactDiagnostic(entry).toString())
                for (entry in probeSamples) writer.appendLine(redactDiagnostic(entry).toString())
            }
        }
    }

    private fun recordNrCaObservation(entry: JSONObject) {
        if (nrCaObservations.size == 96) nrCaObservations.removeFirst()
        nrCaObservations.addLast(entry)
    }

    private fun recordDiagnostic(value: JSONObject) {
        val type = value.optString("type")
        when (type) {
            "status" -> {
                lastCollectorStatus = value
                DiagJournal.append(app, redactDiagnostic(value))
            }
            "qmdl" -> {
                lastQmdlStatus = value
                DiagJournal.append(app, redactDiagnostic(value))
            }
            "unknown_layout", "layout_seen" -> {
                val key = type + ":" + value.optString("code") + ":" + value.optString("ver")
                if (key !in layoutNotices && layoutNotices.size == 128) layoutNotices.remove(layoutNotices.keys.first())
                layoutNotices[key] = value
            }
            "mr_sample" -> {
                if (measurementSamples.size == 128) measurementSamples.removeFirst()
                measurementSamples.addLast(value)
            }
            "neighbor_probe_sample" -> {
                val code = value.optString("code")
                if (code !in neighborProbeSampleCodes) return
                val samples = neighborProbeSamples.getOrPut(code) { ArrayDeque() }
                if (samples.size == MAX_NEIGHBOR_SAMPLES_PER_CODE) samples.removeFirst()
                samples.addLast(value)
            }
            "ims_provider", in signalingTypes -> {
                val redacted = redactDiagnostic(value)
                if (diagnosticRecords.size == MAX_DIAGNOSTIC_MESSAGES) diagnosticRecords.removeFirst()
                diagnosticRecords.addLast(if (type == "ims_provider" || value.has("decoded_tree")) redacted else value)
                DiagJournal.append(app, redacted)
            }
        }
    }

    private var nextId = 0L
    private var firstVisibleMessageId = 0L
    private var capture: CaptureSession? = null
    private var lingeringProcess: Process? = null
    private var lingeringSession: CaptureSession? = null
    private var lingeringReaper: Job? = null
    private var retryJob: Job? = null
    private var modemRecovery: Job? = null
    private var foreground = false
    private var pausedByUser = false

    private enum class CaptureStop(val trace: String, val restart: Boolean) {
        Paused("paused", false),
        NoListener("no_listener", false),
        SilentReconnect("silent_reconnect", true),
        ModemRestart("modem_restart", true),
        QmdlTimeout("qmdl_deadline", true),
    }

    internal fun enterForeground() {
        foreground = true
        applyCollectionPolicy()
    }

    internal fun leaveForeground() {
        foreground = false
        applyCollectionPolicy()
    }

    internal fun onCollectionPreferenceChanged() = applyCollectionPolicy()

    internal fun retry() {
        pausedByUser = false
        restartAttempt = 0
        applyCollectionPolicy(explicitRetry = true)
    }

    internal fun reconnectSilentCollector() {
        if (!signalState.value.modemWaitTimedOut || qmdlState.value.phase != QmdlPhase.Idle ||
            qmdlState.value.recovering) return
        val session = capture?.takeUnless { it.isStopping } ?: return
        restartAttempt = 0
        session.requestStop(CaptureStop.SilentReconnect)
    }

    internal fun recoverAfterModemRestart() {
        modemRecovery?.cancel()
        val requestedAt = SystemClock.elapsedRealtime()
        recordCollectorRecovery("requested")
        modemRecovery = viewModelScope.launch {
            var restarts = 0
            for (check in 1..CollectorRestartPolicy.MODEM_RECOVERY_CHECKS) {
                delay(CollectorRestartPolicy.MODEM_RECOVERY_INTERVAL_MS)
                if (!DiagRecordingPolicy.shouldListen(currentMode())) {
                    recordCollectorRecovery("skipped_not_listening", check = check)
                    return@launch
                }
                val signal = signalState.value
                if (signal.status in setOf(RootDiagStatus.Denied, RootDiagStatus.Unavailable) ||
                    signal.errorCode == 2 || signal.errorCode == 3) {
                    recordCollectorRecovery("skipped_unavailable", check = check)
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                val statusAge = (now - lastCollectorStatusAtElapsed).takeIf { lastCollectorStatusAtElapsed != 0L }
                val packetAge = modemPacketAge(now)
                val stale = CollectorRestartPolicy.needsModemRecovery(
                    signal.status == RootDiagStatus.Running, statusAge ?: Long.MAX_VALUE, packetAge, now - requestedAt,
                )
                val phase = when {
                    !stale -> "packets_resumed"
                    qmdlState.value.phase != QmdlPhase.Idle || qmdlState.value.recovering -> "deferred_qmdl_active"
                    capture?.isStopping == true || lingeringProcess?.isAlive == true -> "waiting_for_collector_exit"
                    else -> CollectorRestartPolicy.modemRecoveryPhase(stale, check, restarts)
                }
                recordCollectorRecovery(phase, packetAge, statusAge, check)
                if (phase == "restarting_collector") {
                    restarts++
                    restartAttempt = 0
                    val session = capture
                    if (session == null) start()
                    else {
                        if (qmdlState.value.phase == QmdlPhase.Recording ||
                            qmdlState.value.phase == QmdlPhase.Starting) {
                            resumeRecordingTask = qmdlTaskId
                            resumeRecordingFormat = recordingFormat
                        }
                        session.requestStop(CaptureStop.ModemRestart)
                    }
                }
            }
        }
    }

    private fun modemPacketAge(now: Long): Long? {
        val statusAt = lastCollectorStatusAtElapsed.takeIf { it != 0L } ?: return null
        val packetAgeSeconds = lastCollectorStatus?.optLong("last_pkt_age_s", -1)?.takeIf { it >= 0 }
            ?: return null
        return packetAgeSeconds * 1_000L + (now - statusAt)
    }

    private fun recordCollectorRecovery(
        stage: String, packetAgeMs: Long? = null, statusAgeMs: Long? = null, check: Int = 0,
    ) {
        val entry = JSONObject().put("type", "collector_recovery")
            .put("t", System.currentTimeMillis()).put("stage", stage)
            .put("check", check).put("collector_run", collectorRuns.lastOrNull()?.optLong("id") ?: JSONObject.NULL)
            .put("clock_id", SurveyTaskStore.CLOCK_ID)
            .put("packet_age_ms", packetAgeMs ?: JSONObject.NULL)
            .put("status_age_ms", statusAgeMs ?: JSONObject.NULL)
            .put("live_serving", signalState.value.hasLiveServingCell(null, SystemClock.elapsedRealtime()))
            .put("collector_state", signalState.value.status.name)
            .put("qmdl_phase", qmdlState.value.phase.name)
        if (collectorRecovery.size == 24) collectorRecovery.removeFirst()
        collectorRecovery.addLast(entry)
        DiagJournal.append(app, entry)
    }

    internal fun pause() {
        pausedByUser = true
        modemRecovery?.cancel()
        modemRecovery = null
        applyCollectionPolicy()
    }

    private fun collectorProcessAlive(): Boolean =
        capture?.processAlive == true || lingeringProcess?.isAlive == true

    private fun currentMode(): DiagRecordingPolicy.Mode = DiagRecordingPolicy.mode(
        taskRunning = activeTask != null,
        foreground = foreground,
        serviceMaintained = RootDiagCollectorService.isMaintained,
        paused = pausedByUser,
        collectorAlive = collectorProcessAlive(),
    )

    private fun applyCollectionPolicy(explicitRetry: Boolean = false) {
        val mode = currentMode()
        collectionModeState.value = mode
        if (qmdlTaskId != eligibleQmdlTask()) requestQmdlStop()
        if (DiagRecordingPolicy.shouldListen(mode)) {
            capture?.resumeAfterStop()
            lingeringSession?.resumeAfterStop()
            if (capture == null && (explicitRetry || retryJob == null && signalState.value.status !in setOf(
                    RootDiagStatus.Denied, RootDiagStatus.Unavailable, RootDiagStatus.Unsupported,
                ))) start()
        } else {
            retryJob?.cancel()
            retryJob = null
            val reason = if (pausedByUser) CaptureStop.Paused else CaptureStop.NoListener
            capture?.requestStop(reason)
            lingeringSession?.requestStop(reason)
        }
        publishOverlay(signalState.value.status, signalState.value.records)
        syncCollectorService(signalState.value.status)
    }

    internal fun clearSignaling() {
        firstVisibleMessageId = nextId
        signalingState.value = emptyList()
        DiagJournal.clear(app)
    }

    private fun start() {
        if (capture != null || collectorProcessAlive()) return
        if (!DiagRecordingPolicy.shouldListen(currentMode())) return
        retryJob?.cancel()
        retryJob = null
        val session = CaptureSession()
        capture = session
        signalState.value = RootSignalState(
            RootDiagStatus.Checking, lastMeasurements.values.toList() + lastConfiguration.values,
            cachedMeasureIds = lastMeasurements.values.mapTo(mutableSetOf()) { it.id },
            lastNeighborMeasurementAt = signalState.value.lastNeighborMeasurementAt,
        )
        syncCollectorService(RootDiagStatus.Checking)
        viewModelScope.launch {
            try {
                session.collect()
            } finally {
                capture = null
            }
            if (isActive) onCaptureFinished(session)
        }
    }

    private fun onCaptureFinished(session: CaptureSession) {
        val mode = currentMode()
        collectionModeState.value = mode
        if (lingeringSession === session) {
            if (lingeringReaper?.isActive != true) {
                lingeringProcess?.let { watchLingeringProcess(session, it) }
            }
            syncCollectorService(session.status)
            return
        }
        val canContinue = DiagRecordingPolicy.shouldListen(mode) && !collectorProcessAlive()
        when {
            canContinue && session.restartRequested -> start()
            canContinue && !session.wasAskedToStop && CollectorRestartPolicy.shouldRetry(
                session.status, session.errorCode, hadBeenRunning, restartAttempt,
            ) -> {
                val wait = CollectorRestartPolicy.delayMs(restartAttempt++)
                retryJob = viewModelScope.launch {
                    delay(wait)
                    retryJob = null
                    start()
                }
            }
            else -> syncCollectorService(session.status)
        }
    }

    /**
     * A collector can ignore the initial close/destroy sequence (for example
     * while the diag-router is blocked in a modem call). Keep watching that
     * exact process after the capture coroutine has returned. Once it really
     * exits, release the reference, finalize any recording that could not
     * receive a stop ACK, and run the normal restart/retry decision again.
     */
    private fun watchLingeringProcess(session: CaptureSession, process: Process) {
        lingeringReaper?.cancel()
        lingeringReaper = viewModelScope.launch {
            try {
                while (process.isAlive) delay(LINGERING_PROCESS_POLL_MS)
                if (lingeringProcess !== process || lingeringSession !== session) return@launch
                lingeringProcess = null
                lingeringSession = null
                session.finishLingeringQmdl()
                recordCollectorRecovery("lingering_process_exited")
                onCaptureFinished(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (lingeringReaper === coroutineContext[Job]) lingeringReaper = null
            }
        }
    }

    internal fun updateSimContext(count: Int, subscriptionId: Int?) {
        simCount = count
        selectedSubscriptionId = subscriptionId
        publishCapability()
        androidImsRefresh?.cancel()
        androidImsRefresh = viewModelScope.launch {
            val provider = withContext(Dispatchers.IO) { readAndroidIms(app) }
            imsProviders[provider.name] = provider
            publishCapability()
        }
    }

    internal fun updateBandLockProfile(profile: BandLockProfile) {
        bandLockProfile = profile
        publishCapability()
    }

    private fun syncCollectorService(status: RootDiagStatus) {
        val active = status == RootDiagStatus.Checking || status == RootDiagStatus.Starting ||
            status == RootDiagStatus.Running
        val mode = currentMode()
        val hold = DiagRecordingPolicy.shouldHoldService(mode)
        if ((active || retryJob != null || capture?.restartRequested == true ||
                lingeringSession?.restartRequested == true) && hold) {
            if (!RootDiagCollectorService.isMaintained && foreground) RootDiagCollectorService.start(app)
        } else {
            RootDiagCollectorService.stop(app)
        }
    }

    private fun currentCapability(): DeviceCapabilitySnapshot {
        val permission = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return DeviceCapabilities.snapshot(
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = Build.SOC_MODEL,
            hardware = Build.HARDWARE,
            simCount = simCount,
            selectedSubscriptionId = selectedSubscriptionId,
            telephonyPermission = permission,
            collectorStatus = signalState.value.status,
            collectorError = signalState.value.errorCode,
            imsProviders = imsProviders.values.toList(),
            bandLock = bandLockProfile,
            imsEvidence = lastImsEvidence,
        )
    }

    private fun publishCapability() {
        capabilityState.value = currentCapability()
    }

    private fun publishOverlay(status: RootDiagStatus, records: List<DiagRecord>) {
        val serving = records.filter { it.type == "serving" }.maxByOrNull { it.receivedAt }?.data
        val ims = currentCapability().imsStatus
        val mode = if (status == RootDiagStatus.Running) currentMode() else DiagRecordingPolicy.Mode.Idle
        val recording = qmdlState.value.phase == QmdlPhase.Recording || qmdlState.value.phase == QmdlPhase.Starting
        DiagOverlayState.publish(
            DiagOverlaySnapshot(
                collecting = recording,
                recording = recording,
                status = DiagRecordingPolicy.overlayStatus(mode, recording),
                rat = serving?.optString("rat").orEmpty(),
                pci = serving?.optInt("pci", -1)?.takeIf { it >= 0 },
                rsrp = serving?.optDouble("rsrp")?.takeIf { it.isFinite() },
                sinr = serving?.optDouble("sinr")?.takeIf { it.isFinite() },
                imsReg = ims.headlineKey,
                imsRegSource = ims.evidenceSource.orEmpty(),
                imsAvailable = if (ims.registration == ImsRegistrationUi.Unavailable) false else null,
                imsCall = ims.call ?: overlayImsCall,
                decodeDropped = overlayDropped,
            )
        )
    }

    private inner class CaptureSession {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libwireless_root_diag.so")
        val run = JSONObject().put("type", "collector_run").put("id", nextCollectorRun++)
            .put("started_ms", System.currentTimeMillis()).put("clock_id", SurveyTaskStore.CLOCK_ID)
        // Save the handle on the IO thread even if scope cancellation prevents the result crossing back.
        @Volatile private var process: Process? = null
        val processAlive get() = process?.isAlive == true
        private var stopRequest: CaptureStop? = null
        private var closing = false
        val isStopping get() = stopRequest != null || closing
        val wasAskedToStop get() = stopRequest != null
        var restartRequested = false
            private set
        var status = RootDiagStatus.Checking
            private set
        var errorCode: Int? = null
            private set
        var hasModemPackets = false
        val metrics = mutableMapOf<String, DiagRecord>()
        val cached = lastMeasurements.toMutableMap()
        val lastNeighborMeasurementAt = signalState.value.lastNeighborMeasurementAt.toMutableMap()
        val nrCarriers = RootNrCarriers()
        val measurementConfigs = mutableMapOf<String, RrcMeasurementConfig>()
        var readyAt: Long? = null
        val pending = ArrayDeque<DiagRecord>()
        val startupLog = ArrayDeque<String>()
        val outputFramer = DiagOutputFramer(MAX_LINE_BYTES)
        var lastFlush = 0L
        var lastHeartbeat = SystemClock.elapsedRealtime()
        /* Liveness diagnostics. A 2026-11-27 export showed a run that stayed
           "running" for 223 s while its last status was at 29 s: the loop ran
           but the heartbeat path never fired. These markers make the stall
           point identifiable offline instead of guessed. */
        var loopBeatAt = SystemClock.elapsedRealtime()
        var phaseMarker = "start"
        var linesSinceStatus = 0L
        var lastLineType = ""
        var slowLines = 0
        var slowestLineMs = 0L
        var slowestLineType = ""
        var slowPublishes = 0
        var slowestPublishMs = 0L
        var jsonLines = 0L
        var jsonChars = 0L
        var jsonParseDispatched = 0L
        var jsonParseMs = 0L
        var jsonParseMaxMs = 0L
        var jsonParseOverBudget = 0L
        var receivedRecords = 0L
        var receiveWallMs = 0L
        var receiveWallMaxMs = 0L
        var receiveWallOverBudget = 0L
        var publishCalls = 0L
        var publishWallMs = 0L
        var publishWallMaxMs = 0L
        var publishWallOverBudget = 0L
        var exitReason = "exception"
        val lastChartRecords = mutableMapOf<String, Long>()
        val lastEventStates = mutableMapOf<String, String>()
        val recentRrc = mutableMapOf<String, JSONObject>()
        val runTaskIds = linkedSetOf<String>()
        val buffer = ByteArray(8192)
        var qmdlCommand: Char? = null
        var qmdlDeadline: Long? = null
        var previousCaPacket = ""
        var previousCaPacketAt = 0L
        var previousCaCounts = 0 to 0

        init {
            processLedger.reset()
            if (collectorRuns.size == MAX_COLLECTOR_RUNS) collectorRuns.removeFirst()
            collectorRuns.addLast(run)
            lastCollectorStatus = null
            lastCollectorStatusAtElapsed = 0L
        }

        fun requestStop(reason: CaptureStop) {
            if (stopRequest == null) {
                stopRequest = reason
                recordCollectorRecovery("stop_requested_${reason.trace}")
            }
            restartRequested = reason.restart && !pausedByUser
            requestQmdlStop()
        }

        fun resumeAfterStop() {
            restartRequested = !pausedByUser
        }

        private fun performanceSnapshot() = JSONObject()
            .put("json_lines", jsonLines)
            .put("json_chars", jsonChars)
            .put("json_parse_dispatched", jsonParseDispatched)
            .put("json_parse_wall_ms", jsonParseMs)
            .put("json_parse_max_ms", jsonParseMaxMs)
            .put("json_parse_over_16ms", jsonParseOverBudget)
            .put("records_received", receivedRecords)
            .put("receive_wall_ms", receiveWallMs)
            .put("receive_wall_max_ms", receiveWallMaxMs)
            .put("receive_wall_over_16ms", receiveWallOverBudget)
            .put("publish_calls", publishCalls)
            .put("publish_wall_ms", publishWallMs)
            .put("publish_wall_max_ms", publishWallMaxMs)
            .put("publish_wall_over_16ms", publishWallOverBudget)

        fun updatePerformanceSnapshot() {
            run.put("performance", performanceSnapshot())
        }

        private fun publish(now: Long) {
            metrics.entries.removeAll { (_, record) ->
                when (record.type) {
                    "nr_rach" -> false
                    "nr_ssb_rx" -> now - record.receivedAt > NR_RX_SAMPLE_TIMEOUT
                    else -> now - record.receivedAt > DIAG_MEASUREMENT_TIMEOUT_MS
                }
            }
            if (readyAt?.let { now - it > DIAG_MEASUREMENT_TIMEOUT_MS } == true) cached.clear()
            lastMeasurements.clear()
            lastMeasurements.putAll(cached)
            lastMeasurements.putAll(metrics)
            val errorDetails = if (status == RootDiagStatus.Unsupported) startupLog.toList() else emptyList()
            val cachedIds = (if (status == RootDiagStatus.Stopped) lastMeasurements else cached)
                .values.mapTo(mutableSetOf()) { it.id }
            if (status == RootDiagStatus.Running) {
                hadBeenRunning = true
                restartAttempt = 0
            }
            val carriers = if (status == RootDiagStatus.Running) nrCarriers.snapshot(now) else emptyList()
            val retained = carriers.count { it.lastObservation() }
            val caCounts = carriers.size - retained to retained
            if (caCounts != previousCaCounts) {
                recordNrCaObservation(JSONObject().put("type", "root_nr_carriers")
                    .put("t", System.currentTimeMillis()).put("elapsed_ms", now)
                    .put("collector_run", run.optLong("id")).put("current_rows", caCounts.first)
                    .put("retained_rows", caCounts.second))
                previousCaCounts = caCounts
            }
            signalState.value = RootSignalState(
                status, lastMeasurements.values.toList() + lastConfiguration.values,
                errorCode, errorDetails, cachedIds,
                failureReason = CollectorRestartPolicy.collectorReason(status, errorCode).takeIf {
                    status == RootDiagStatus.Unsupported || status == RootDiagStatus.Denied ||
                        status == RootDiagStatus.Unavailable
                },
                nrCarriers = carriers,
                hasModemPackets = hasModemPackets,
                modemWaitTimedOut = status == RootDiagStatus.Running && !hasModemPackets &&
                    readyAt?.let { now - it >= MODEM_WAIT_TIMEOUT } == true,
                lastNeighborMeasurementAt = lastNeighborMeasurementAt.toMap(),
            )
            publishOverlay(status, signalState.value.records)
            publishCapability()
            if (status == RootDiagStatus.Running) {
                for (rat in listOf("NR", "LTE")) {
                    val record = metrics["serving:$rat"] ?: continue
                    if (lastChartRecords.put(rat, record.id) != record.id) {
                        val protocol = if (rat == "NR") CellularProtocol.NR else CellularProtocol.LTE
                        chartFor(rat).recordSignal(protocol, record.data.optDouble("rsrp").takeIf { it.isFinite() }?.toInt())
                    }
                }
            }
            if (pending.isNotEmpty()) {
                while (pending.isNotEmpty() && pending.first().id < firstVisibleMessageId) pending.removeFirst()
                if (pending.isNotEmpty()) {
                    var detailChars = 0
                    signalingState.value = (pending.reversed() + signalingState.value).asSequence()
                        .take(MAX_MESSAGES)
                        .takeWhile { record ->
                            detailChars += record.decodedTree.length
                            detailChars <= MAX_DETAIL_CHARS
                        }.toList()
                }
                pending.clear()
            }
        }

        fun storeMeasurement(key: String, record: DiagRecord) {
            cached.remove(key)
            metrics[key] = record
            val rat = when (record.type) {
                "lte_meas" -> "LTE"
                "nr_meas", "nr_neighbor_sinr" -> "NR"
                else -> return
            }
            lastNeighborMeasurementAt[rat] = record.receivedAt
        }

        fun removeMeasurements(prefix: String) {
            metrics.keys.removeAll { it.startsWith(prefix) }
            cached.keys.removeAll { it.startsWith(prefix) }
        }

        fun storeCellConfiguration(key: String, record: DiagRecord, data: JSONObject) {
            lastConfiguration.remove(key)
            lastConfiguration[key] = record.copy(data = data)
            val prefix = key.substringBefore(':') + ":"
            val keys = lastConfiguration.keys.filter { it.startsWith(prefix) }
            if (keys.size > MAX_BROADCAST_CELLS) lastConfiguration.remove(keys.first())
        }

        fun storeSchedulingSamples(record: DiagRecord) {
            val tech = if (record.type.startsWith("nr_")) "NR" else "LTE"
            val data = record.data
            val serving = metrics["serving:$tech"]?.data
            val channel = if (tech == "NR") "arfcn" else "earfcn"
            val pci = data.optInt("pci", serving?.optInt("pci", -1) ?: -1)
            val arfcn = data.optInt(channel, serving?.optInt("arfcn", -1) ?: -1)
            if (pci < 0 || arfcn < (if (tech == "NR") 1 else 0)) return
            fun keep(row: JSONObject, group: String) {
                val cc = row.optInt("cc", -1)
                if (cc !in 0..15) return
                if (record.type == "nr_dl_sched" && cc == 0 &&
                    (row.optInt("pci", pci) != pci || row.optInt("arfcn", arfcn) != arfcn)) return
                val sample = JSONObject(row.toString()).put("type", "schedule_sample")
                    .put("rat", tech).put("group", group).put("serving_pci", pci).put("serving_arfcn", arfcn)
                    .put("dt_ms", data.optLong("dt_ms", -1)).put("tentative", if (record.isEstimated()) 1 else 0)
                storeMeasurement("schedule_sample:$tech:$pci:$arfcn:$group:$cc", record.copy(data = sample))
            }
            if (record.type.endsWith("_csf")) {
                keep(data, record.type)
                return
            }
            val rows = data.optJSONArray(if (record.type == "nr_pdsch") "recs" else "cc") ?: return
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                when {
                    record.type.endsWith("_ul_pwr") || record.type.startsWith("lte_pucch_") -> {
                        if (row.has("pusch_dbm")) keep(row, "${record.type}:pusch")
                        if (row.has("pucch_dbm")) keep(row, "${record.type}:pucch")
                    }
                    record.type == "lte_ul_tx" -> {
                        if (row.has("mcs_avg") || row.has("retx_pct")) keep(row, record.type)
                        if (row.has("pucch_dbm")) keep(row, "${record.type}:pucch")
                    }
                    record.type == "nr_pdsch" -> if (row.has("bler_pct") || row.has("retx_pct") || row.has("phy_tb_mbps")) keep(row, record.type)
                    record.type == "lte_rank" -> if (row.has("rank_avg") || row.has("tx") || row.has("rx")) keep(row, record.type)
                    row.optInt("tb") > 0 -> keep(row, record.type)
                }
            }
        }

        private suspend fun receive(value: JSONObject) {
            val now = SystemClock.elapsedRealtime()
            val receivedTime = System.currentTimeMillis()
            val type = value.optString("type")
            // Task ownership is metadata for replay; it never gates the live stream.
            val tasks = taskStore
            val fileTask = qmdlTaskId?.takeIf { qmdlState.value.phase != QmdlPhase.Idle }
            val task = if (fileTask != null) null else tasks?.active?.value?.takeIf {
                it.ownsDiagnostic(value.optLong("t"), receivedTime, now, SurveyTaskStore.CLOCK_ID)
            }
            val taskId = fileTask ?: task?.id
            value.put("task_id", taskId ?: JSONObject.NULL).put("received_time_ms", receivedTime)
                .put("received_elapsed_ms", now).put("clock_id", SurveyTaskStore.CLOCK_ID)
                .put("collector_run", run.optLong("id"))
            if (taskId != null && runTaskIds.add(taskId)) run.put("task_ids", JSONArray(runTaskIds.toList()))
            val messageRat = value.optString("rat")
            associateRrcEvent(value, type, messageRat, now)
            if (type in processObservationTypes && !value.has("message_id")) value.put("message_id", nextId)
            val stateKey = "$type:${value.optString("rat")}"
            val state = listOf("state", "reg", "call", "status").joinToString(":") { value.optString(it) }
            val isStateChange = type in setOf("nas_mm", "ims_reg", "ims_call", "ims_state") &&
                state.replace(":", "").isNotBlank() && lastEventStates.put(stateKey, state) != state
            val completion = type == "rrc" && value.optString("msg") in setOf(
                "rrcSetupComplete", "rrcConnectionSetupComplete", "rrcResumeComplete", "rrcConnectionResumeComplete",
                "rrcReconfigurationComplete", "rrcConnectionReconfigurationComplete",
                "rrcReestablishmentComplete", "rrcConnectionReestablishmentComplete", "securityModeFailure",
            )
            val timelineEvent = type in setOf("event", "cell_change", "nas_release", "nr_rach") || isStateChange || completion
            if (timelineEvent) value.put("timeline_event", true)
            if (tasks != null && task != null && timelineEvent && type != "nr_rach") {
                appendTaskEvent(tasks, task, value, type, messageRat, state, receivedTime, now)
            }
            recordDiagnostic(value)
            when (type) {
                "status" -> receiveStatus(value, now)
                "ims_provider" -> receiveImsProvider(value)
                "ims_state", "ims_reg", "ims_call" -> receiveImsEvidence(value, type, isStateChange, now)
                "qmdl" -> receiveQmdl(value)
                "mr_sample", "neighbor_probe_sample", "unknown_layout", "layout_seen" -> Unit
                "nr_ca_state" -> receiveCaState(value, now)
                else -> receiveRadioRecord(value, messageRat, now, task, tasks)
            }
        }

        private fun associateRrcEvent(value: JSONObject, type: String, rat: String, now: Long) {
            if (type == "rrc") {
                value.put("message_id", nextId)
                recentRrc[rat] = value
            }
            if (type != "event") return
            val parent = recentRrc[rat]?.takeIf {
                it.optString("msg") == value.optString("msg") &&
                    it.optString("task_id") == value.optString("task_id") &&
                    now - it.optLong("received_elapsed_ms") in 0..1_000
            } ?: return
            for (key in listOf("pci", "arfcn", "src", "rrc_transaction_id", "cause", "tentative", "guess", "decode_incomplete")) {
                if (!value.has(key) && parent.has(key)) value.put(key, parent.get(key))
            }
            value.put("related_message_id", parent.optLong("message_id"))
        }

        private suspend fun appendTaskEvent(
            tasks: SurveyTaskStore,
            task: SurveyTask,
            value: JSONObject,
            type: String,
            messageRat: String,
            state: String,
            receivedTime: Long,
            now: Long,
        ) {
            val rat = messageRat.takeIf { it == "NR" || it == "LTE" }
            // A private DIAG stream has no reliable Android subscription ID.
            val estimated = value.optInt("tentative") == 1 || value.optInt("guess") == 1
            val sourceTime = value.optLong("t")
            val sourceAge = receivedTime - sourceTime
            val fresh = sourceTime > 0 && sourceAge in 0..10_000
            val pci = value.optInt("to_pci", value.optInt("pci", -1)).takeIf { !estimated && fresh && it in 0..1007 }
            val arfcn = value.optInt("to_arfcn", value.optInt("arfcn", -1)).takeIf { !estimated && fresh && it >= 0 }
            val title = formatDiagnosticEventTitle(
                type = type,
                rat = rat,
                messageRat = messageRat,
                msg = value.optString("msg"),
                event = value.optString("event"),
                psi = value.optInt("psi", -1),
                ebi = value.optInt("ebi", -1),
                state = state,
                estimated = estimated,
            )
            val safeValue = redactDiagnostic(value)
            val details = listOf("msg", "cause", "why", "from_pci", "from_arfcn", "to_pci",
                "to_arfcn", "rrc_transaction_id", "related_message_id", "collector_run", "src", "tentative", "guess", "decode_incomplete")
                .filter { safeValue.has(it) }.joinToString(" · ") { "$it=${safeValue.opt(it)}" }
            try {
                withContext(Dispatchers.IO) {
                    tasks.append(SurveyEvent(java.util.UUID.randomUUID().toString(), task.id,
                        if (fresh) sourceTime else receivedTime, if (fresh) now - sourceAge else now,
                        SurveyTaskStore.CLOCK_ID, "root_diag", type,
                        title, details, rat = rat, pci = pci, arfcn = arfcn,
                        sourceTimeMs = sourceTime.takeIf { it > 0 },
                        timeBasis = if (fresh) "source_age_adjusted" else "received"))
                }
            } catch (error: android.database.SQLException) {
                timber.log.Timber.w(error, "Could not save DIAG event")
            }
        }

        private fun receiveStatus(value: JSONObject, now: Long) {
            val state = value.optString("state")
            run.put("last_status", value)
            if (state == "running") run.put("last_running_status", value)
            val next = when (state) {
                "authorized" -> RootDiagStatus.Starting
                "running" -> RootDiagStatus.Running
                "start_error", "error" -> RootDiagStatus.Unsupported
                "denied" -> RootDiagStatus.Denied
                else -> return
            }
            if (next == RootDiagStatus.Running && readyAt == null) readyAt = now
            status = next
            errorCode = value.optInt("rc").takeIf { it != 0 }
            lastCollectorStatusAtElapsed = now
            linesSinceStatus = 0L
            hasModemPackets = value.optLong("pkts") > 0
            lastHeartbeat = now
            overlayDropped = value.optLong("decode_dropped")
        }

        private fun receiveImsProvider(value: JSONObject) {
            val name = value.optString("name")
            if (name.isBlank()) return
            val statusName = value.optString("status")
            val available = if (value.has("available")) value.optBoolean("available") else null
            imsProviders[name] = ImsProviderStatus(name, statusName, available, value.optString("detail"))
            publishCapability()
        }

        private fun receiveImsEvidence(value: JSONObject, type: String, isStateChange: Boolean, now: Long) {
            val registration = value.optString("reg").ifBlank { value.optString("result") }
            val inferred = value.optInt("reg_inferred") == 1 || value.optBoolean("reg_inferred")
            val source = when {
                inferred -> "inferred"
                value.optString("why").isNotBlank() -> value.optString("why")
                value.optString("call_src").isNotBlank() -> value.optString("call_src")
                value.optString("src").isNotBlank() -> value.optString("src")
                else -> type
            }
            val call = value.optString("call").takeIf { it.isNotBlank() } ?: lastImsEvidence?.call
            lastImsEvidence = ImsEvidence(
                registration = registration.takeIf { it.isNotBlank() && it != "unknown" },
                inferred = inferred,
                source = source,
                call = call,
                why = value.optString("why"),
            )
            overlayImsCall = call ?: overlayImsCall
            publishCapability()
            if (isStateChange) queueSignaling(pending, DiagRecord(nextId++, now, value))
        }

        private suspend fun receiveQmdl(value: JSONObject) {
            if (qmdlCommand == null) return
            val current = qmdlState.value
            if (current.recovering) return
            qmdlState.value = current.copy(
                packets = value.optLong("packets"), bytes = value.optLong("bytes"),
                phase = if (current.phase == QmdlPhase.Stopping) QmdlPhase.Stopping else QmdlPhase.Recording,
                errorCode = value.optInt("rc").takeIf { it != 0 },
            )
            val result = when (value.optString("state")) {
                "stopped" -> QmdlResult.Saved
                "limit" -> QmdlResult.Limited
                "error" -> QmdlResult.Error
                else -> null
            }
            if (qmdlCommand == 'Q' || qmdlCommand == 'D' || result != null) qmdlDeadline = null
            if (result != null) {
                qmdlCommand = null
                withContext(NonCancellable) { finishQmdl(result) }
            }
        }

        private fun receiveCaState(value: JSONObject, now: Long) {
            val result = nrCarriers.acceptCaState(value, now)
            val cc = value.optJSONArray("cc")
            val states = JSONArray()
            if (cc != null) for (i in 0 until cc.length()) {
                val row = cc.optJSONObject(i) ?: continue
                states.put(JSONObject().put("cc", row.optInt("cc"))
                    .put("dl", row.optInt("dl")).put("ul", row.optInt("ul")))
            }
            val version = value.optString("ver")
            val signature = "$version:$result:$states"
            if (signature != previousCaPacket || now - previousCaPacketAt >= 5_000) {
                recordNrCaObservation(JSONObject().put("type", "root_b9a7_state")
                    .put("t", value.optLong("t")).put("elapsed_ms", now).put("ver", version)
                    .put("collector_run", run.optLong("id")).put("task_id", value.opt("task_id"))
                    .put("clock_id", SurveyTaskStore.CLOCK_ID).put("result", result).put("cc", states))
                previousCaPacket = signature
                previousCaPacketAt = now
            }
        }

        private suspend fun receiveRadioRecord(
            value: JSONObject,
            rat: String,
            now: Long,
            task: SurveyTask?,
            tasks: SurveyTaskStore?,
        ) {
            nrCarriers.accept(value, now)
            val record = DiagRecord(nextId++, now, value)
            when (record.type) {
                "nas_release", "nas_pdu", "nas_qos", "nas_mm" -> receiveNas(record, rat)
                in configurationTypes -> lastConfiguration[record.type] = record
                "cell_change" -> if (rat == "NR") {
                    removeMeasurements("nr_rach:")
                    removeMeasurements("nr_ssb_rx:")
                }
                "nr_rach" -> if (record.source == "0xB88A" && value.has("ta") &&
                    value.optInt("pci", -1) in 0..1007 && value.optInt("arfcn", -1) in 1..3_279_165) {
                    storeMeasurement("nr_rach:serving", record)
                }
                "lte_cell", "lte_mib" -> receiveLteCell(record)
                in signalTypes -> receiveMeasurement(record, rat)
                "nr_neighbor_sinr" -> receiveNrNeighborSinr(record)
                "rrc" -> if ((rat == "NR" || rat == "LTE") && !record.isEstimated()) receiveRrc(record, rat)
            }
            if (record.type in signalingTypes) queueSignaling(pending, record)
            if (record.type in processObservationTypes) {
                for (event in processLedger.accept(value, record.id, now)) {
                    val json = event.toJson()
                        .put("task_id", value.opt("task_id"))
                        .put("received_time_ms", value.optLong("received_time_ms"))
                        .put("received_elapsed_ms", now)
                        .put("clock_id", SurveyTaskStore.CLOCK_ID)
                        .put("collector_run", value.opt("collector_run"))
                        .put("timeline_event", true)
                        .put("t", value.optLong("t"))
                    queueSignaling(pending, DiagRecord(nextId++, now, json))
                    if (task != null) {
                        val linked = "family=${event.family} · phase=${event.phase} · evidence=${event.evidence}" +
                            " · non_claim=${event.nonClaim} · message_ids=${event.messageIds.joinToString(",")}"
                        try {
                            withContext(Dispatchers.IO) {
                                tasks?.append(SurveyEvent(
                                    java.util.UUID.randomUUID().toString(), task.id,
                                    value.optLong("received_time_ms").takeIf { it > 0 } ?: System.currentTimeMillis(),
                                    now, SurveyTaskStore.CLOCK_ID, "root_diag", "process_event",
                                    event.titleZh, linked, rat = event.rat.takeIf { it == "NR" || it == "LTE" },
                                    sourceTimeMs = value.optLong("t").takeIf { it > 0 },
                                    timeBasis = "received",
                                ))
                            }
                        } catch (error: android.database.SQLException) {
                            timber.log.Timber.w(error, "Could not save process event")
                        }
                    }
                }
            }
        }

        private fun receiveNas(record: DiagRecord, rat: String) {
            val value = record.data
            when (record.type) {
                "nas_release" -> {
                    val session = value.optString("psi", value.optString("ebi"))
                    lastConfiguration.remove("nas_pdu:$rat:$session")
                    lastConfiguration.remove("nas_qos:$rat:$session")
                    if (rat == "LTE") {
                        lastConfiguration.values.removeAll { previous ->
                            previous.type == "nas_qos" && previous.data.optString("rat") == "LTE" &&
                                previous.data.optString("linked_ebi") == session
                        }
                    }
                }
                "nas_pdu", "nas_qos" -> {
                    val session = value.optString("psi", value.optString("ebi"))
                    val key = "${record.type}:$rat:$session"
                    val previous = lastConfiguration[key]
                    val modified = value.optBoolean("modified")
                    if (record.type == "nas_pdu" && modified && previous != null) {
                        val updated = JSONObject(value.toString())
                        if (updated.optString("dnn").isBlank() && previous.data.optString("dnn").isNotBlank()) {
                            updated.put("dnn", previous.data.optString("dnn"))
                            updated.put("ims", previous.data.optInt("ims"))
                        }
                        lastConfiguration[key] = record.copy(data = updated)
                    } else {
                        if (record.type == "nas_pdu" && !modified) lastConfiguration.remove("nas_qos:$rat:$session")
                        lastConfiguration[key] = record
                    }
                }
                "nas_mm" -> {
                    if (value.optString("state") == "Deregistered") {
                        lastConfiguration.values.removeAll { previous ->
                            (previous.type == "nas_pdu" || previous.type == "nas_qos") &&
                                previous.data.optString("rat") == rat
                        }
                    }
                    /*
                    if (record.type == "rrc" && (rat == "NR" || rat == "LTE") && !record.isEstimated()) {
                        val sinr = value.optInt("pcell_sinr_index", -1)
                        if (rat == "LTE" && sinr in 1..126) {
                            val reading = JSONObject().put("type", "lte_rrc_sinr")
                                .put("pci", value.optInt("pci", -1)).put("arfcn", value.optInt("arfcn", -1))
                                .put("sinr_index", sinr).put("t", record.time)
                            storeMeasurement("lte_rrc_sinr", record.copy(data = reading))
                        }
                        val config = measurementConfigs.getOrPut(rat) { RrcMeasurementConfig() }
                        val message = value.optString("display_msg").ifBlank { value.optString("msg") }
                        if (message in measurementContextReset && !value.optBoolean("rrc_suspended")) {
                            config.clear()
                            lastConfiguration.remove("plmn_selection:$rat")
                            if (rat == "LTE") {
                                metrics.remove("lte_rrc_sinr")
                                cached.remove("lte_rrc_sinr")
                            }
                            removeMeasurements("rrc_meas:$rat:")
                        }
                        val pci = value.optInt("pci", -1)
                        val arfcn = value.optInt("arfcn", -1)
                        val rrcState = when (message) {
                            "rrcSetupComplete", "rrcResumeComplete", "rrcReestablishmentComplete",
                            "rrcReconfigurationComplete", "rrcConnectionSetupComplete", "rrcConnectionResumeComplete",
                            "rrcConnectionReestablishmentComplete", "rrcConnectionReconfigurationComplete" -> "Connected"
                            "measurementReport" -> if (!value.optBoolean("decode_incomplete", true)) "Connected" else null
                            "rrcRelease", "rrcConnectionRelease" -> when {
                                value.optBoolean("rrc_suspended") -> if (rat == "NR") "Inactive" else "Suspended"
                                !value.optBoolean("decode_incomplete", true) -> "Idle"
                                else -> null
                            }
                            else -> null
                        }
                        if (rrcState != null && pci in 0..(if (rat == "NR") 1007 else 503) &&
                            arfcn in (if (rat == "NR") 1..3_279_165 else 0..262_143)) {
                            val reading = JSONObject().put("type", "rrc_state").put("rat", rat)
                                .put("state", rrcState).put("pci", pci).put("arfcn", arfcn).put("t", record.time)
                            storeMeasurement("rrc_state:$rat", record.copy(data = reading))
                        }
                        val broadcastPlmns = value.optJSONArray("broadcast_plmns")
                        val selectedPlmnIndex = value.optInt("selected_plmn_index", -1)
                        if ((broadcastPlmns != null || selectedPlmnIndex in 1..12) && pci in 0..(if (rat == "NR") 1007 else 503) &&
                            arfcn in (if (rat == "NR") 1..3_279_165 else 0..262_143)) {
                            val selectionKey = "plmn_selection:$rat"
                            val identity = JSONObject().put("rat", rat).put("pci", pci).put("arfcn", arfcn)
                            broadcastPlmns?.let { list ->
                                val key = "plmn_broadcast:$rat:$pci:$arfcn"
                                val previous = lastConfiguration.remove(key)?.data?.optJSONArray("plmns")
                                if (previous != null && previous.toString() != list.toString()) {
                                    val selection = lastConfiguration[selectionKey]?.data
                                    if (selection?.optInt("pci", -1) == pci && selection.optInt("arfcn", -1) == arfcn)
                                        lastConfiguration.remove(selectionKey)
                                }
                                val data = JSONObject(identity.toString()).put("type", "plmn_broadcast").put("plmns", list)
                                lastConfiguration[key] = record.copy(data = data)
                                val keys = lastConfiguration.keys.filter { it.startsWith("plmn_broadcast:$rat:") }
                                if (keys.size > MAX_BROADCAST_CELLS) lastConfiguration.remove(keys.first())
                            }
                            if (selectedPlmnIndex in 1..12) {
                                val data = identity.put("type", "plmn_selection").put("index", selectedPlmnIndex)
                                lastConfiguration[selectionKey] = record.copy(data = data)
                            }
                        }
                        value.optJSONObject("meas_config")?.let { update ->
                            config.update(update).forEach { id ->
                                removeMeasurements("rrc_meas:$rat:$id:")
                            }
                        }
                        val results = value.optJSONObject("measResults")
                        val measurementId = results?.optInt("measId", -1)
                        if (results != null && measurementId != null && measurementId in 1..64) {
                            val key = "rrc_meas:$rat:$measurementId:"
                            fun storeNeighborSinr(neighborRat: String, measurement: RrcNeighborSinr) {
                                val reference = measurement.reference?.label
                                val servingPci = value.optInt("pci", -1)
                                val servingChannel = value.optInt("arfcn", -1)
                                val sample = JSONObject().put("type", "rrc_neighbor_sinr").put("src", "RRC")
                                    .put("rat", neighborRat).put("arfcn", measurement.channel).put("pci", measurement.pci)
                                    .put("serving_rat", rat).put("serving_pci", servingPci).put("serving_arfcn", servingChannel)
                                    .put("reference", reference).put("sinr_index", measurement.index)
                                    .put("beam", measurement.beam).put("t", record.time)
                                // Once resolved, the sample belongs to this cell pair. Reusing a measId
                                // for a different object does not change the frequency of this reading.
                                val sampleKey = "rrc_meas:$rat:sinr:$servingChannel:$servingPci:" +
                                    "$neighborRat:$reference:${measurement.channel}:${measurement.pci}"
                                storeMeasurement(sampleKey, record.copy(data = sample))
                            }
                            if (rat == "NR") {
                                results.nrServingObjectNeighborSinr(value.optInt("pci", -1), value.optInt("arfcn", -1))
                                    ?.let { storeNeighborSinr("NR", it) }
                            }
                            val carrier = config.carrierFor(measurementId)
                            if (carrier != null) {
                                val reading = JSONObject()
                                    .put("type", "rrc_neighbor").put("rat", rat)
                                    .put("measResults", results)
                                    .put("measurement_carrier", carrier).put("t", record.time)
                                storeMeasurement("${key}latest", record.copy(data = reading))
                                val neighborRat = carrier.optString("rat")
                                for (measurement in results.rrcNeighborSinr(carrier)) {
                                    storeNeighborSinr(neighborRat, measurement)
                                }
                            }
                        }
                    }
                    if (record.type == "rrc" && rat == "NR" && !record.isEstimated()) {
                        value.optJSONObject("frequency_config")?.let { config ->
                            val targetPci = config.optInt("pci", -1)
                            val targetArfcn = config.optInt("arfcn", -1)
                            if (targetPci in 0..1007 && targetArfcn in 1..3_279_165) {
                                val type = "nr_frequency_config"
                                storeCellConfiguration("$type:$targetPci:$targetArfcn", record,
                                    JSONObject(config.toString()).put("type", type))
                            }
                        }
                        val pci = value.optInt("pci", -1)
                        val arfcn = value.optInt("arfcn", -1)
                        if (pci in 0..1007 && arfcn in 1..3_279_165) {
                            for ((field, type) in nrBroadcastTypes) {
                                val config = value.optJSONObject(field) ?: continue
                                val key = "$type:$pci:$arfcn"
                                val data = JSONObject(config.toString())
                                    .put("type", type).put("pci", pci).put("arfcn", arfcn)
                                storeCellConfiguration(key, record, data)
                            }
                        }
                    }
                    if (record.type == "rrc" && (rat == "LTE" || rat == "NR") && !record.isEstimated()) {
                        val access = value.optJSONObject("access_config")
                        val pci = value.optInt("pci", -1)
                        val arfcn = value.optInt("arfcn", -1)
                        val validCell = if (rat == "NR") pci in 0..1007 && arfcn in 1..3_279_165
                            else pci in 0..503 && arfcn in 0..262_143
                        val tdd = value.optJSONObject("lte_tdd_config")
                        if (rat == "LTE" && validCell && tdd != null) {
                            val type = "lte_tdd_config"
                            storeCellConfiguration("$type:$pci:$arfcn", record,
                                JSONObject(tdd.toString()).put("type", type).put("pci", pci).put("arfcn", arfcn))
                        }
                        if (access != null && validCell) {
                            val type = if (rat == "NR") "nr_access_config" else "lte_access_config"
                            val key = "$type:$pci:$arfcn"
                            // LTE broadcasts these parameters in separate SIBs.
                            val data = JSONObject(lastConfiguration[key]?.data?.toString() ?: "{}")
                            for (field in access.keys()) data.put(field, access.get(field))
                            storeCellConfiguration(key, record, data.put("type", type).put("pci", pci).put("arfcn", arfcn))
                        }
                    }
                    if (record.type in signalingTypes) {
                        queueSignaling(pending, record)
                    }
                    if (record.type in processObservationTypes) {
                        for (event in processLedger.accept(value, record.id, now)) {
                            val json = event.toJson()
                                .put("task_id", value.opt("task_id"))
                                .put("received_time_ms", value.optLong("received_time_ms"))
                                .put("received_elapsed_ms", now)
                                .put("clock_id", SurveyTaskStore.CLOCK_ID)
                                .put("collector_run", value.opt("collector_run"))
                                .put("timeline_event", true)
                                .put("t", value.optLong("t"))
                            queueSignaling(pending, DiagRecord(nextId++, now, json))
                            if (task != null) {
                                val linked = "family=${event.family} · phase=${event.phase} · evidence=${event.evidence}" +
                                    " · non_claim=${event.nonClaim} · message_ids=${event.messageIds.joinToString(",")}"
                                try {
                                    withContext(Dispatchers.IO) {
                                        tasks?.append(SurveyEvent(
                                            java.util.UUID.randomUUID().toString(), task.id,
                                            value.optLong("received_time_ms").takeIf { it > 0 }
                                                ?: System.currentTimeMillis(),
                                            now, SurveyTaskStore.CLOCK_ID, "root_diag", "process_event",
                                            event.titleZh, linked,
                                            rat = event.rat.takeIf { it == "NR" || it == "LTE" },
                                            sourceTimeMs = value.optLong("t").takeIf { it > 0 },
                                            timeBasis = "received",
                                        ))
                                    }
                                } catch (error: android.database.SQLException) {
                                    timber.log.Timber.w(error, "Could not save process event")
                                }
                            }
                        }
                    }
                    */
                    lastConfiguration["nas_mm:$rat"] = record
                }
            }
        }

        private fun receiveLteCell(record: DiagRecord) {
            val value = record.data
            val pci = value.optInt("pci", -1)
            val earfcn = value.optInt("earfcn", -1)
            if (pci !in 0..503 || earfcn !in 0..262_143) return
            val key = "${record.type}:$earfcn:$pci"
            val previous = lastConfiguration[key]?.data
            val cid = value.optLong("cid")
            val sameCell = cid <= 0 || (previous?.optLong("cid") ?: 0L) <= 0 || previous?.optLong("cid") == cid
            val config = JSONObject(if (sameCell) previous?.toString() ?: "{}" else "{}")
            for (field in value.keys()) config.put(field, value.get(field))
            storeCellConfiguration(key, record, config)
        }

        private fun receiveMeasurement(record: DiagRecord, rat: String) {
            val value = record.data
            val channel = when (record.type) {
                "serving" -> rat
                "lte_meas" -> "${value.optString("src")}:${value.optInt("earfcn", -1)}:" +
                    "${value.optInt("pci", -1)}:${value.optInt("cc", -1)}"
                "nr_meas" -> "${value.optInt("arfcn", -1)}:${value.optInt("layer", -1)}"
                "nr_snr" -> "${value.optString("rs")}:${record.source}:" +
                    "${value.optInt("pci", -1)}:${value.optInt("arfcn", -1)}"
                "layer_throughput" -> "$rat:${value.optString("layer")}:${value.optString("dir")}"
                "lte_phr", "lte_bsr" -> "${value.optInt("pci", -1)}:${value.optInt("arfcn", -1)}"
                "lte_ca", "lte_rank" -> "${value.optInt("pci", -1)}:${value.optInt("earfcn", -1)}"
                else -> ""
            }
            storeMeasurement("${record.type}:$channel", record)
            if (record.type == "nr_snr") storeNrRxMeasurements(record)
            if (record.type in schedulingTypes) storeSchedulingSamples(record)
            if (record.type == "nr_ul_sched" && value.has("mac_new_tb_mbps")) {
                val sample = JSONObject(value.toString()).put("type", "nr_mac_ul_window")
                storeMeasurement("nr_mac_ul_window", record.copy(data = sample))
            }
            if (record.type == "nr_cfg" && !record.isEstimated()) storeNrCarrierConfig(record)
        }

        private fun storeNrRxMeasurements(record: DiagRecord) {
            val value = record.data
            if (record.isEstimated() || value.optString("rs") != "SSB" || value.optInt("carrier", -1) != 0) return
            val pci = value.optInt("pci", -1)
            val arfcn = value.optInt("arfcn", -1)
            if (pci !in 0..1007 || arfcn !in 1..3_279_165) return
            for (rx in 0..3) {
                val sinr = value.optDouble("sinr_rx$rx")
                if (sinr !in -23.0..40.0) continue
                val measurement = JSONObject().put("type", "nr_ssb_rx")
                    .put("src", record.source).put("t", record.time)
                    .put("pci", pci).put("arfcn", arfcn).put("rx", rx).put("sinr", sinr)
                storeMeasurement("nr_ssb_rx:$pci:$arfcn:${record.source}:$rx", record.copy(data = measurement))
            }
        }

        private fun storeNrCarrierConfig(record: DiagRecord) {
            val cells = record.data.optJSONArray("cells") ?: return
            for (i in 0 until cells.length()) {
                val cell = cells.optJSONObject(i) ?: continue
                val pci = cell.optInt("pci", -1)
                val arfcn = cell.optInt("ssb_arfcn", -1)
                if (pci !in 0..1007 || arfcn !in 1..3_279_165) continue
                val type = "nr_carrier_config"
                storeCellConfiguration("$type:$pci:$arfcn", record,
                    JSONObject(cell.toString()).put("type", type).put("arfcn", arfcn))
            }
        }

        private fun receiveNrNeighborSinr(record: DiagRecord) {
            val value = record.data
            val channel = value.optInt("arfcn", -1)
            if (channel !in 1..3_279_165) return
            val cells = value.optJSONArray("cells") ?: return
            for (entry in 0 until cells.length()) {
                val cell = cells.optJSONObject(entry) ?: continue
                val pci = cell.optInt("pci", -1)
                val sinr = cell.optDouble("sinr")
                if (pci !in 0..1007 || sinr !in -23.0..40.0) continue
                val key = "nr_neighbor_sinr:$channel:$pci"
                val previous = metrics[key]
                if (previous?.id == record.id && previous.data.optDouble("sinr") >= sinr) continue
                val reading = JSONObject().put("type", "nr_neighbor_sinr").put("src", "B96A")
                    .put("arfcn", channel).put("pci", pci).put("ssb", cell.optInt("ssb"))
                    .put("sinr", sinr).put("t", record.time)
                storeMeasurement(key, record.copy(data = reading))
            }
        }

        private fun receiveRrc(record: DiagRecord, rat: String) {
            val value = record.data
            val pci = value.optInt("pci", -1)
            val arfcn = value.optInt("arfcn", -1)
            val sinr = value.optInt("pcell_sinr_index", -1)
            if (rat == "LTE" && sinr in 1..126) {
                val reading = JSONObject().put("type", "lte_rrc_sinr").put("pci", pci).put("arfcn", arfcn)
                    .put("sinr_index", sinr).put("t", record.time)
                storeMeasurement("lte_rrc_sinr", record.copy(data = reading))
            }
            val config = measurementConfigs.getOrPut(rat) { RrcMeasurementConfig() }
            val message = value.optString("display_msg").ifBlank { value.optString("msg") }
            if (message in measurementContextReset && !value.optBoolean("rrc_suspended")) {
                config.clear()
                lastConfiguration.remove("plmn_selection:$rat")
                if (rat == "LTE") {
                    metrics.remove("lte_rrc_sinr")
                    cached.remove("lte_rrc_sinr")
                }
                removeMeasurements("rrc_meas:$rat:")
            }
            val validCell = if (rat == "NR") pci in 0..1007 && arfcn in 1..3_279_165
                else pci in 0..503 && arfcn in 0..262_143
            if (validCell) {
                storeRrcState(record, rat, message, pci, arfcn)
                storeRrcPlmns(record, rat, pci, arfcn)
            }
            value.optJSONObject("meas_config")?.let { update ->
                config.update(update).forEach { id -> removeMeasurements("rrc_meas:$rat:$id:") }
            }
            storeRrcMeasurements(record, config, rat, pci, arfcn)
            if (rat == "NR") storeNrFrequencyConfig(record)
            if (validCell) storeRrcCellConfig(record, rat, pci, arfcn)
        }

        private fun storeRrcState(record: DiagRecord, rat: String, message: String, pci: Int, arfcn: Int) {
            val value = record.data
            val rrcState = when (message) {
                "rrcSetupComplete", "rrcResumeComplete", "rrcReestablishmentComplete",
                "rrcReconfigurationComplete", "rrcConnectionSetupComplete", "rrcConnectionResumeComplete",
                "rrcConnectionReestablishmentComplete", "rrcConnectionReconfigurationComplete" -> "Connected"
                "measurementReport" -> if (!value.optBoolean("decode_incomplete", true)) "Connected" else null
                "rrcRelease", "rrcConnectionRelease" -> when {
                    value.optBoolean("rrc_suspended") -> if (rat == "NR") "Inactive" else "Suspended"
                    !value.optBoolean("decode_incomplete", true) -> "Idle"
                    else -> null
                }
                else -> null
            } ?: return
            val reading = JSONObject().put("type", "rrc_state").put("rat", rat)
                .put("state", rrcState).put("pci", pci).put("arfcn", arfcn).put("t", record.time)
            storeMeasurement("rrc_state:$rat", record.copy(data = reading))
        }

        private fun storeRrcPlmns(record: DiagRecord, rat: String, pci: Int, arfcn: Int) {
            val value = record.data
            val broadcastPlmns = value.optJSONArray("broadcast_plmns")
            val selectedPlmnIndex = value.optInt("selected_plmn_index", -1)
            if (broadcastPlmns == null && selectedPlmnIndex !in 1..12) return
            val selectionKey = "plmn_selection:$rat"
            val identity = JSONObject().put("rat", rat).put("pci", pci).put("arfcn", arfcn)
            if (broadcastPlmns != null) {
                val key = "plmn_broadcast:$rat:$pci:$arfcn"
                val previous = lastConfiguration.remove(key)?.data?.optJSONArray("plmns")
                if (previous != null && previous.toString() != broadcastPlmns.toString()) {
                    val selection = lastConfiguration[selectionKey]?.data
                    if (selection?.optInt("pci", -1) == pci && selection.optInt("arfcn", -1) == arfcn) {
                        lastConfiguration.remove(selectionKey)
                    }
                }
                val data = JSONObject(identity.toString()).put("type", "plmn_broadcast").put("plmns", broadcastPlmns)
                lastConfiguration[key] = record.copy(data = data)
                val keys = lastConfiguration.keys.filter { it.startsWith("plmn_broadcast:$rat:") }
                if (keys.size > MAX_BROADCAST_CELLS) lastConfiguration.remove(keys.first())
            }
            if (selectedPlmnIndex in 1..12) {
                val data = identity.put("type", "plmn_selection").put("index", selectedPlmnIndex)
                lastConfiguration[selectionKey] = record.copy(data = data)
            }
        }

        private fun storeRrcMeasurements(record: DiagRecord, config: RrcMeasurementConfig, rat: String, pci: Int, arfcn: Int) {
            val results = record.data.optJSONObject("measResults") ?: return
            val measurementId = results.optInt("measId", -1)
            if (measurementId !in 1..64) return
            if (rat == "NR") {
                results.nrServingObjectNeighborSinr(pci, arfcn)?.let {
                    storeRrcNeighborSinr(record, rat, pci, arfcn, "NR", it)
                }
            }
            val carrier = config.carrierFor(measurementId) ?: return
            val reading = JSONObject().put("type", "rrc_neighbor").put("rat", rat)
                .put("measResults", results).put("measurement_carrier", carrier).put("t", record.time)
            storeMeasurement("rrc_meas:$rat:$measurementId:latest", record.copy(data = reading))
            val neighborRat = carrier.optString("rat")
            for (measurement in results.rrcNeighborSinr(carrier)) {
                storeRrcNeighborSinr(record, rat, pci, arfcn, neighborRat, measurement)
            }
        }

        private fun storeRrcNeighborSinr(
            record: DiagRecord,
            rat: String,
            servingPci: Int,
            servingChannel: Int,
            neighborRat: String,
            measurement: RrcNeighborSinr,
        ) {
            val reference = measurement.reference?.label
            val sample = JSONObject().put("type", "rrc_neighbor_sinr").put("src", "RRC")
                .put("rat", neighborRat).put("arfcn", measurement.channel).put("pci", measurement.pci)
                .put("serving_rat", rat).put("serving_pci", servingPci).put("serving_arfcn", servingChannel)
                .put("reference", reference).put("sinr_index", measurement.index)
                .put("beam", measurement.beam).put("t", record.time)
            // Once resolved, the sample belongs to this cell pair, even if its measId is reused.
            val key = "rrc_meas:$rat:sinr:$servingChannel:$servingPci:" +
                "$neighborRat:$reference:${measurement.channel}:${measurement.pci}"
            storeMeasurement(key, record.copy(data = sample))
        }

        private fun storeNrFrequencyConfig(record: DiagRecord) {
            val config = record.data.optJSONObject("frequency_config") ?: return
            val targetPci = config.optInt("pci", -1)
            val targetArfcn = config.optInt("arfcn", -1)
            if (targetPci !in 0..1007 || targetArfcn !in 1..3_279_165) return
            val type = "nr_frequency_config"
            storeCellConfiguration("$type:$targetPci:$targetArfcn", record,
                JSONObject(config.toString()).put("type", type))
        }

        private fun storeRrcCellConfig(record: DiagRecord, rat: String, pci: Int, arfcn: Int) {
            val value = record.data
            if (rat == "NR") {
                for ((field, type) in nrBroadcastTypes) {
                    val config = value.optJSONObject(field) ?: continue
                    val data = JSONObject(config.toString()).put("type", type).put("pci", pci).put("arfcn", arfcn)
                    storeCellConfiguration("$type:$pci:$arfcn", record, data)
                }
            } else {
                val tdd = value.optJSONObject("lte_tdd_config")
                if (tdd != null) {
                    val type = "lte_tdd_config"
                    storeCellConfiguration("$type:$pci:$arfcn", record,
                        JSONObject(tdd.toString()).put("type", type).put("pci", pci).put("arfcn", arfcn))
                }
            }
            val access = value.optJSONObject("access_config") ?: return
            val type = if (rat == "NR") "nr_access_config" else "lte_access_config"
            val key = "$type:$pci:$arfcn"
            // LTE broadcasts these parameters in separate SIBs.
            val data = JSONObject(lastConfiguration[key]?.data?.toString() ?: "{}")
            for (field in access.keys()) data.put(field, access.get(field))
            storeCellConfiguration(key, record, data.put("type", type).put("pci", pci).put("arfcn", arfcn))
        }

        suspend fun collect() {
            try {
                exitReason = startAndPoll()
            } catch (error: IOException) {
                exitReason = "io_error"
                status = when {
                    stopRequest != null -> RootDiagStatus.Stopped
                    process == null -> RootDiagStatus.Unavailable
                    else -> RootDiagStatus.Unsupported
                }
                timber.log.Timber.w(error, "DIAG collector I/O failed")
            } catch (cancelled: CancellationException) {
                exitReason = "scope_cancelled"
                status = RootDiagStatus.Stopped
                throw cancelled
            } finally {
                closing = true
                try {
                    publish(SystemClock.elapsedRealtime())
                } finally {
                    // Only shutdown is non-cancellable, so the scope can still cancel listening.
                    withContext(NonCancellable) { closeRun() }
                }
            }
        }

        private suspend fun startAndPoll(): String {
            if (lingeringProcess != null) {
                // start() lets us get here only after that process has exited.
                lingeringProcess = null
                lingeringSession = null
                finishInterruptedQmdl()
            }
            stopRequest?.let {
                status = RootDiagStatus.Stopped
                return it.trace
            }
            if (launchCollector == null && !withContext(captureIo) { helper.isFile }) {
                status = RootDiagStatus.Unsupported
                return "missing_helper"
            }
            stopRequest?.let {
                status = RootDiagStatus.Stopped
                return it.trace
            }
            /*
            val stream = running.inputStream
            do {
                when {
                    status == RootDiagStatus.Running && qmdlState.value.phase == QmdlPhase.Idle && qmdlCommand == null -> {
                        val resume = resumeRecordingTask
                        if (resume != null) {
                            resumeRecordingTask = null
                            if (resume == activeTask?.id && !pausedByUser) {
                                recordingFormat = resumeRecordingFormat
                                requestQmdlStart(manual = true)
                            } else requestQmdlStart(manual = false)
                        } else requestQmdlStart(manual = false)
                    }
                    status == RootDiagStatus.Running && qmdlState.value.phase == QmdlPhase.Starting && qmdlCommand == null -> {
                        val taskId = qmdlTaskId
                        val format = recordingFormat
                        val prepared = taskId != null && taskId == eligibleQmdlTask() &&
                            withContext(Dispatchers.IO) {
                                runCatching { recordingSession.start(taskId, format) }.onSuccess {
                                    runCatching { locationRecording.onSessionStarted(taskId) }.onFailure {
                                        timber.log.Timber.w(it, "Could not open location sidecar")
                                    }
                                }.onFailure {
                                    timber.log.Timber.w(it, "Could not prepare recording file")
                                }.isSuccess
                            }
                        if (prepared && taskId == eligibleQmdlTask() && qmdlState.value.phase == QmdlPhase.Starting) {
                            qmdlCommand = format.command
                            qmdlDeadline = SystemClock.elapsedRealtime() + QMDL_ACK_TIMEOUT
                            withContext(Dispatchers.IO) {
                                running.outputStream.write(format.command.code)
                                running.outputStream.flush()
                            }
                        } else {
                            val result = if (!prepared && taskId == eligibleQmdlTask()) QmdlResult.Error
                                else QmdlResult.Interrupted
                            withContext(NonCancellable) { finishQmdl(result) }
                        }
                    }
                    qmdlState.value.phase == QmdlPhase.Stopping && qmdlCommand == null -> {
                        withContext(NonCancellable) { finishQmdl(QmdlResult.Saved) }
                    }
                    qmdlState.value.phase == QmdlPhase.Stopping && (qmdlCommand == 'Q' || qmdlCommand == 'D') -> {
                        qmdlCommand = 'S'
                        qmdlDeadline = SystemClock.elapsedRealtime() + QMDL_ACK_TIMEOUT
                        withContext(Dispatchers.IO) {
                            running.outputStream.write('S'.code)
                            running.outputStream.flush()
                        }
                    }
                }
                val size = withContext(Dispatchers.IO) {
                    val available = stream.available()
                    if (available > 0) stream.read(buffer, 0, minOf(available, buffer.size)) else 0
                }
                for (index in 0 until size) {
                    val byte = buffer[index].toInt()
                    if (byte == '\n'.code) {
                        if (!overlong && line.size() > 0) {
                            val text = line.toString(Charsets.UTF_8.name())
                            if (text.startsWith(DIAG_LOG_PREFIX)) {
                                if (startupLog.size == 5) startupLog.removeFirst()
                                startupLog.addLast(text.removePrefix(DIAG_LOG_PREFIX).take(512))
                            } else if (text.startsWith("{")) {
                                val lineEntered = SystemClock.elapsedRealtime()
                                phaseMarker = "decode"
                                val parsed = try { JSONObject(text) } catch (_: org.json.JSONException) { null }
                                if (parsed != null) {
                                    val lineType = parsed.optString("type")
                                    try {
                                        receive(parsed)
                                    } catch (_: org.json.JSONException) {
                                        invalidLineCount++
                                    }
                                    val lineCost = SystemClock.elapsedRealtime() - lineEntered
                                    linesSinceStatus++
                                    if (lineType.isNotEmpty()) lastLineType = lineType
                                    if (lineType == "status") linesSinceStatus = 0
                                    if (lineCost >= SLOW_LINE_MS) {
                                        slowLines++
                                        if (lineCost > slowestLineMs) {
                                            slowestLineMs = lineCost
                                            slowestLineType = lineType
                                        }
                                    }
                                } else invalidLineCount++
                                phaseMarker = "read"
                            }
                        }
                        if (overlong) invalidLineCount++
                        line.reset()
                        overlong = false
                    } else if (line.size() < MAX_LINE_BYTES) {
                        line.write(byte)
                    } else overlong = true
                }
                val now = SystemClock.elapsedRealtime()
                loopBeatAt = now
                phaseMarker = "publish"
                if (now - lastFlush >= 400) {
                    publish(now)
                    lastFlush = now
                    val publishCost = SystemClock.elapsedRealtime() - now
                    if (publishCost >= SLOW_LINE_MS) {
                        slowPublishes++
                        if (publishCost > slowestPublishMs) slowestPublishMs = publishCost
                    }
                }
                phaseMarker = "read"
                if (!running.isAlive && size == 0) { exitReason = "eof"; break }
                if (qmdlDeadline?.let { now >= it } == true) {
                    qmdlDeadline = null
                    val phase = qmdlState.value.phase
                    val command = qmdlCommand
                    when {
                        phase == QmdlPhase.Stopping && command == 'S' -> {
                            qmdlCommand = null
                            exitReason = "qmdl_deadline"
                            blockQmdlAutoStart()
                            restartAfterStop = DiagRecordingPolicy.shouldListen(currentMode()) && !pausedByUser
                            break
                        }
                        command == 'Q' || command == 'D' -> {
                            qmdlState.value = qmdlState.value.copy(phase = QmdlPhase.Stopping)
                            qmdlCommand = 'S'
                            qmdlDeadline = now + QMDL_ACK_TIMEOUT
                            runCatching {
                                running.outputStream.write('S'.code)
                                running.outputStream.flush()
                            }
                        }
                        phase == QmdlPhase.Starting || phase == QmdlPhase.Stopping -> {
                            qmdlCommand = null
                            exitReason = "qmdl_deadline"
                            blockQmdlAutoStart()
                            restartAfterStop = DiagRecordingPolicy.shouldListen(currentMode()) && !pausedByUser
                            break
                        }
                        else -> qmdlCommand = null
                    }
                }
                val timeout = if (status == RootDiagStatus.Checking) 25_000 else 16_000
                if (now - lastHeartbeat >= timeout) { exitReason = "heartbeat_timeout"; break }
                if (size < buffer.size) delay(40) else yield()
            } while (true)
            status = when (status) {
                RootDiagStatus.Checking -> RootDiagStatus.Denied
                RootDiagStatus.Starting, RootDiagStatus.Running -> RootDiagStatus.Unsupported
                else -> status
            }
            publish(SystemClock.elapsedRealtime())
        } catch (_: IOException) {
            status = if (process == null) RootDiagStatus.Unavailable else RootDiagStatus.Unsupported
            publish(SystemClock.elapsedRealtime())
        } catch (cancelled: CancellationException) {
            status = RootDiagStatus.Stopped
            publish(SystemClock.elapsedRealtime())
            throw cancelled
        } finally {
            runCatching {
                val exitedAt = SystemClock.elapsedRealtime()
                run.put("end_detail", JSONObject()
                    .put("reason", exitReason)
                    .put("phase", phaseMarker)
            */
            val running = withContext(captureIo) {
                (launchCollector?.invoke() ?: ProcessBuilder("su", "-c", collectorCommand(helper))
                    .redirectErrorStream(true).start()).also { process = it }
            }
            val reason = poll(running)
            status = when {
                stopRequest != null -> RootDiagStatus.Stopped
                status == RootDiagStatus.Checking -> RootDiagStatus.Denied
                status == RootDiagStatus.Starting || status == RootDiagStatus.Running -> RootDiagStatus.Unsupported
                else -> status
            }
            return reason
        }

        private suspend fun poll(running: Process): String {
            val stream = running.inputStream
            while (true) {
                requestedExit()?.let { return it }
                updateQmdl(running)
                requestedExit()?.let { return it }
                val (size, output) = readChunk(stream)
                invalidLineCount += output.invalidLines
                for (line in output.lines) receiveLine(line)
                val now = SystemClock.elapsedRealtime()
                loopBeatAt = now
                publishIfDue(now)
                requestedExit()?.let { return it }
                if (size < 0 || (!running.isAlive && size == 0)) return "eof"
                if (qmdlDeadline?.let { now >= it } == true) {
                    blockQmdlAutoStart()
                    qmdlState.value = qmdlState.value.copy(recovering = true)
                    if (stopRequest == null) requestStop(CaptureStop.QmdlTimeout)
                    return "qmdl_deadline"
                }
                val timeout = if (status == RootDiagStatus.Checking) 25_000 else 16_000
                if (now - lastHeartbeat >= timeout) return "heartbeat_timeout"
                if (size < buffer.size) delay(40) else yield()
            }
        }

        private fun requestedExit(): String? = stopRequest?.trace?.takeIf {
            qmdlCommand == null && qmdlState.value.phase == QmdlPhase.Idle
        }

        private suspend fun updateQmdl(running: Process) {
            when (qmdlState.value.phase) {
                QmdlPhase.Idle -> if (stopRequest == null && status == RootDiagStatus.Running && qmdlCommand == null) {
                    val resume = resumeRecordingTask
                    if (resume != null) {
                        resumeRecordingTask = null
                        if (resume == activeTask?.id && !pausedByUser) {
                            recordingFormat = resumeRecordingFormat
                            requestQmdlStart(manual = true)
                        } else {
                            requestQmdlStart(manual = false)
                        }
                    } else {
                        requestQmdlStart(manual = false)
                    }
                }
                QmdlPhase.Starting -> if (status == RootDiagStatus.Running && qmdlCommand == null) {
                    prepareQmdl(running)
                }
                QmdlPhase.Recording -> Unit
                QmdlPhase.Stopping -> when (qmdlCommand) {
                    null -> withContext(NonCancellable) { finishQmdl(QmdlResult.Interrupted) }
                    'Q', 'D' -> sendQmdlCommand(running, 'S')
                    else -> Unit
                }
            }
        }

        private suspend fun prepareQmdl(running: Process) {
            val taskId = qmdlTaskId
            val format = recordingFormat
            val prepared = taskId != null && taskId == eligibleQmdlTask() && withContext(captureIo) {
                runCatching {
                    recordingSession.start(taskId, format)
                    runCatching { locationRecording.onSessionStarted(taskId) }.onFailure {
                        timber.log.Timber.w(it, "Could not open location sidecar")
                    }
                }.onFailure {
                    timber.log.Timber.w(it, "Could not prepare QMDL file")
                }.isSuccess
            }
            if (prepared && taskId == eligibleQmdlTask() && qmdlState.value.phase == QmdlPhase.Starting) {
                sendQmdlCommand(running, format.command)
            } else {
                val result = if (!prepared && taskId != null && taskId == eligibleQmdlTask()) QmdlResult.Error
                    else QmdlResult.Interrupted
                withContext(NonCancellable) { finishQmdl(result) }
            }
        }

        private suspend fun sendQmdlCommand(running: Process, command: Char) {
            qmdlCommand = command
            qmdlDeadline = SystemClock.elapsedRealtime() + QMDL_ACK_TIMEOUT
            withContext(captureIo) {
                running.outputStream.write(command.code)
                running.outputStream.flush()
            }
        }

        private suspend fun readChunk(stream: InputStream): Pair<Int, DiagOutputLines> = withContext(captureIo) {
            val available = stream.available()
            val size = if (available > 0) stream.read(buffer, 0, minOf(available, buffer.size)) else 0
            size to if (size > 0) outputFramer.append(buffer, size) else DiagOutputLines()
        }

        private suspend fun receiveLine(text: String) {
            if (text.startsWith(DIAG_LOG_PREFIX)) {
                if (startupLog.size == 5) startupLog.removeFirst()
                startupLog.addLast(text.removePrefix(DIAG_LOG_PREFIX).take(512))
                return
            }
            if (!text.startsWith("{")) return
            val entered = SystemClock.elapsedRealtime()
            phaseMarker = "parse_json"
            jsonLines++
            jsonChars += text.length
            if (text.length >= DIAG_JSON_PARSE_DISPATCH_THRESHOLD) jsonParseDispatched++
            val parseStarted = SystemClock.elapsedRealtime()
            val value = try {
                parseDiagJson(text, captureIo)
            } catch (_: org.json.JSONException) {
                null
            }
            val parseCost = SystemClock.elapsedRealtime() - parseStarted
            jsonParseMs += parseCost
            jsonParseMaxMs = maxOf(jsonParseMaxMs, parseCost)
            if (parseCost > PERFORMANCE_BUDGET_MS) jsonParseOverBudget++
            if (value == null) {
                invalidLineCount++
                phaseMarker = "read"
                return
            }
            val type = value.optString("type")
            phaseMarker = "receive"
            val receiveStarted = SystemClock.elapsedRealtime()
            try {
                receive(value)
            } catch (_: org.json.JSONException) {
                invalidLineCount++
            } finally {
                val receiveCost = SystemClock.elapsedRealtime() - receiveStarted
                receivedRecords++
                receiveWallMs += receiveCost
                receiveWallMaxMs = maxOf(receiveWallMaxMs, receiveCost)
                if (receiveCost > PERFORMANCE_BUDGET_MS) receiveWallOverBudget++
            }
            val lineCost = SystemClock.elapsedRealtime() - entered
            linesSinceStatus++
            if (type.isNotEmpty()) lastLineType = type
            if (type == "status") linesSinceStatus = 0
            if (lineCost >= SLOW_LINE_MS) {
                slowLines++
                if (lineCost > slowestLineMs) {
                    slowestLineMs = lineCost
                    slowestLineType = type
                }
            }
            phaseMarker = "read"
        }

        private fun publishIfDue(now: Long) {
            if (now - lastFlush < 400) return
            phaseMarker = "publish"
            val publishStarted = SystemClock.elapsedRealtime()
            publish(now)
            val cost = SystemClock.elapsedRealtime() - publishStarted
            lastFlush = now
            publishCalls++
            publishWallMs += cost
            publishWallMaxMs = maxOf(publishWallMaxMs, cost)
            if (cost > PERFORMANCE_BUDGET_MS) publishWallOverBudget++
            if (cost >= SLOW_LINE_MS) {
                slowPublishes++
                slowestPublishMs = maxOf(slowestPublishMs, cost)
            }
            phaseMarker = "read"
        }

        private suspend fun closeRun() {
            val exitedAt = SystemClock.elapsedRealtime()
            val running = process
            val result = if (running == null) "not_started" else withContext(captureIo) {
                closeCollectorProcess(running, if (readyAt == null) 16 else 4)
            }
            if (running?.isAlive == true) {
                lingeringProcess = running
                lingeringSession = this@CaptureSession
                if (qmdlState.value.phase != QmdlPhase.Idle) {
                    qmdlState.value = qmdlState.value.copy(recovering = true)
                }
            } else {
                finishInterruptedQmdl()
            }
            run.put("ended_ms", System.currentTimeMillis()).put("end_state", status.name)
                .put("performance", performanceSnapshot())
                .put("end_detail", JSONObject().put("exit_reason", exitReason).put("phase", phaseMarker)
                    .put("stop_request", stopRequest?.trace ?: JSONObject.NULL)
                    .put("process_exit", result)
                    .put("cleanup_ms", SystemClock.elapsedRealtime() - exitedAt)
                    .put("since_beat_ms", exitedAt - loopBeatAt)
                    .put("since_status_ms", if (lastCollectorStatusAtElapsed > 0) exitedAt - lastCollectorStatusAtElapsed else -1L)
                    .put("lines_since_status", linesSinceStatus)
                    .put("last_type", lastLineType)
                    .put("slow_lines", slowLines)
                    .put("slowest_line_ms", slowestLineMs)
                    .put("slowest_line_type", slowestLineType)
                    .put("slow_publishes", slowPublishes)
                    .put("slowest_publish_ms", slowestPublishMs)
                    .put("invalid_lines", invalidLineCount))
            DiagJournal.append(app, redactDiagnostic(run))
        }

        private suspend fun finishInterruptedQmdl() {
            if (qmdlState.value.phase != QmdlPhase.Idle || qmdlState.value.recovering) {
                withContext(NonCancellable) {
                    finishQmdl(if (pausedByUser) QmdlResult.Saved else QmdlResult.Interrupted)
                }
            }
        }

        suspend fun finishLingeringQmdl() = finishInterruptedQmdl()
    }

    private fun closeCollectorProcess(process: Process, graceSeconds: Long): String {
        // EOF asks the helper to flush and exit. Also unblock a writer with a full stdout pipe.
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        if (process.waitFor(graceSeconds, TimeUnit.SECONDS)) return "exited"
        process.destroy()
        if (process.waitFor(500, TimeUnit.MILLISECONDS)) return "destroyed"
        process.destroyForcibly()
        return if (process.waitFor(500, TimeUnit.MILLISECONDS)) "forced" else "unconfirmed"
    }

    private fun queueSignaling(pending: ArrayDeque<DiagRecord>, record: DiagRecord) {
        if (pending.size == MAX_MESSAGES) pending.removeFirst()
        pending.addLast(record)
    }

    private fun collectorCommand(helper: File): String {
        // Match DiagCap's executable location so Android uses the same linker configuration.
        // The fresh 0700 directory is owned by root and the child keeps the app's stdin pipe.
        val quotedPath = "'" + helper.absolutePath.replace("'", "'\\''") + "'"
        val quotedQmdl = "'" + recordingSession.directory.absolutePath.replace("'", "'\\''") + "'"
        return """
            PATH=/system/bin
            export PATH
            umask 077
            [ "${'$'}(id -u)" = 0 ] || exit 1
            printf '%s\n' '{"type":"status","state":"authorized"}'
            start_error() {
                printf 'wireless-root-diag: %s\n' "${'$'}2" >&2
                printf '{"type":"status","state":"start_error","rc":%s}\n' "${'$'}1"
                exit "${'$'}1"
            }
            work=${'$'}(mktemp -d /data/local/tmp/wireless-root-diag.XXXXXXXX) ||
                start_error 20 'Unable to create the diagnostic working directory'
            trap 'rm -f -- "${'$'}work/collector"; rmdir -- "${'$'}work"' EXIT
            cp -- $quotedPath "${'$'}work/collector" || start_error 21 'Unable to copy the installed diagnostic helper'
            chmod 700 "${'$'}work/collector" || start_error 22 'Unable to make the diagnostic helper executable'
            cd "${'$'}work" || start_error 23 'Unable to enter the diagnostic working directory'
            ./collector $quotedQmdl
            result=${'$'}?
            if [ "${'$'}result" -eq 126 ] || [ "${'$'}result" -eq 127 ]; then
                start_error 24 'Unable to execute the diagnostic helper'
            fi
            exit "${'$'}result"
        """.trimIndent()
    }

    private companion object {
        const val MAX_MESSAGES = 4_000
        const val MODEM_WAIT_TIMEOUT = 20_000L
        const val QMDL_ACK_TIMEOUT = 8_000L
        const val PERFORMANCE_BUDGET_MS = 16L
        const val LINGERING_PROCESS_POLL_MS = 250L
        const val MAX_DIAGNOSTIC_MESSAGES = 400
        const val MAX_COLLECTOR_RUNS = 8
        const val MAX_NEIGHBOR_SAMPLES_PER_CODE = 6
        val neighborProbeSampleCodes = setOf("0xB119", "0xB123", "0xB96A", "0xB96D", "0xB96E", "0xB96F", "0xB992")
        const val MAX_LINE_BYTES = 540_672
        /* A decoded line that takes longer than this is recorded as a slow line;
           the count and the worst type pin a stall to a message class. */
        const val SLOW_LINE_MS = 200L
        const val MAX_DETAIL_CHARS = 12_000_000
        const val MAX_BROADCAST_CELLS = 12
        val measurementContextReset = setOf(
            "rrcSetup", "rrcRelease", "rrcConnectionSetup", "rrcConnectionRelease",
        )
        const val DIAG_LOG_PREFIX = "wireless-root-diag: "
        val configurationTypes = setOf(
            "ims_state", "ims_call", "ims_reg", "ims_session", "ims_reg_log", "sip",
        )
        val nrBroadcastTypes = mapOf("mib" to "nr_mib_config", "sib1_config" to "nr_sib1_config")
        val schedulingTypes = setOf("nr_dl_sched", "nr_ul_sched", "nr_csf", "lte_csf", "nr_ul_pwr", "nr_pdsch",
            "lte_dl_sched", "lte_ul_sched", "lte_ul_pwr", "lte_ul_tx", "lte_pucch_power", "lte_pucch_tx", "lte_rank")
        val signalTypes = setOf(
            "serving", "nr_cell", "lte_meas", "nr_meas", "nr_mib", "nr_snr", "lte_phr", "lte_bsr", "lte_csf",
            "nr_cfg", "nr_pdsch", "nr_csf", "nr_dl_sched", "nr_ul_sched", "nr_ul_pwr", "layer_throughput",
            "lte_dl_sched", "lte_ul_sched", "lte_ul_pwr", "lte_ul_tx", "lte_pucch_power", "lte_pucch_tx",
            "lte_rank", "lte_ca", "ims_media",
        )
        val processObservationTypes = setOf("rrc", "nas", "sip", "nr_rach", "cell_change")
        val signalingTypes = setOf(
            "rrc", "nas", "nas_pdu", "nas_qos", "nas_mm", "nas_release", "sip", "event", "nr_rach",
            "ims_call", "ims_reg", "ims_session", "ims_reg_log", "ims_state", "cell_change",
        )
    }
}

internal fun formatDiagnosticEventTitle(
    type: String,
    rat: String?,
    messageRat: String,
    msg: String,
    event: String,
    psi: Int,
    ebi: Int,
    state: String,
    estimated: Boolean,
): String = when (type) {
    "cell_change" -> "服务小区变化（DIAG）"
    "nas_release" -> {
        val session = psi.takeIf { it > 0 }?.let { "PSI $it" }
            ?: ebi.takeIf { it >= 0 }?.let { "EBI $it" }
        listOfNotNull(rat?.let { "$it 会话释放" } ?: "NAS 会话释放", session).joinToString(" · ")
    }
    else -> (if (messageRat == "IMS") event else msg)
        .ifBlank { event.ifBlank { listOf(type, state.trim(':')).filter { it.isNotBlank() }.joinToString(" ") } } +
        if (estimated) "（推测）" else ""
}

private fun AndroidLteSinrReader.Sample.toDiagnosticJson() = JSONObject().apply {
    fun sinr(value: Int): Any = if (LteSinrUtils.isReported(value)) value else JSONObject.NULL
    put("type", "android_lte_sinr")
    put("task_id", taskId() ?: JSONObject.NULL)
    put("clock_id", clockId() ?: JSONObject.NULL)
    put("t", timeMs())
    put("elapsed_ms", elapsedMs())
    put("subscription_id", subscriptionId())
    put("registered", registered())
    put("connection_status", connectionStatus())
    put("eligible_lte_cells", eligibleCells())
    put("cell_timestamp_ms", cellTimestampMs())
    put("cell_age_ms", elapsedMs() - cellTimestampMs())
    put("cell_rssnr_raw", cellRssnr())
    put("cell_sinr_db", sinr(cellSinr()))
    put("snapshot_timestamp_ms", snapshotTimestampMs() ?: JSONObject.NULL)
    put("snapshot_age_ms", snapshotTimestampMs()?.let { elapsedMs() - it } ?: JSONObject.NULL)
    put("snapshot_rssnr_raw", JSONArray(snapshotRssnr()))
    put("snapshot_sinr_db", JSONArray(snapshotSinr().map(::sinr)))
    put("snapshot_decision", snapshotDecision())
    put("snapshot_error", snapshotError() ?: JSONObject.NULL)
    put("selected_sinr_db", sinr(selectedSinr()))
    put("selected_source", selectedSource())
}

/* "head" is the unknown-layout byte dump (see root_diag.c unknown_layout).
   It holds layout bytes only, no subscriber identity, so excluding it made
   every layout question unanswerable offline -- the dump was decoded and
   then discarded at export time. */
private val excludedDiagnosticKeys = setOf(
    "ncgi", "nci", "nci_hex", "cid", "ci", "tac", "call_id", "raw", "access",
    "reason_hdr", "reason", "dnn", "apn", "imsi", "imei", "tmsi", "ue_identity", "uper_hex", "decoded_tree",
    "from", "to", "contact", "via", "uri", "ip", "impu", "impi", "msisdn", "phone", "user", "p_asserted",
)

private fun redactDiagnostic(data: JSONObject): JSONObject = JSONObject().apply {
    for (key in data.keys()) {
        if (key in excludedDiagnosticKeys ||
            (key == "msg" && data.optString("type") == "event" && data.optString("rat") == "IMS")
        ) continue
        val item = data.get(key)
        put(key, when (item) {
            is JSONObject -> redactDiagnostic(item)
            is JSONArray -> JSONArray().apply {
                for (i in 0 until item.length()) {
                    val part = item.get(i)
                    put(if (part is JSONObject) redactDiagnostic(part) else part)
                }
            }
            else -> item
        })
    }
}
