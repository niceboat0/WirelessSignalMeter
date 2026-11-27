package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.model.SurveyLocationMode
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale

/** Hint passed to LocationManager. Android may deliver fixes slower than this. */
internal const val RECORDING_LOCATION_INTERVAL_MS = 1_000L
internal const val RECORDING_LOCATION_MIN_DISTANCE_M = 0f
internal const val CQT_WAIT_MS = 5_000L
internal const val LOCATION_ALIGN_WINDOW_MS = 10_000L
internal const val LOCATION_MAX_AGE_MS = 10_000L
internal const val LOCATION_MAX_ACCURACY_M = 100.0

internal data class RecordedFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Double?,
    val speedMps: Double?,
    val provider: String?,
    val ageMs: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val clockId: String,
    val taskId: String,
) {
    fun qualified(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            latitude.isFinite() && longitude.isFinite() &&
            ageMs in 0..LOCATION_MAX_AGE_MS &&
            accuracyM != null && accuracyM >= 0.0 && accuracyM <= LOCATION_MAX_ACCURACY_M

    /**
     * Match a decoded diagnostic row on this process clock.
     * [receivedElapsedMs] is the row's received_elapsed_ms. The corrected wall-clock field `t` is not a key.
     */
    fun matchesDiagnostic(receivedElapsedMs: Long, receivedClockId: String): Boolean =
        clockId == receivedClockId &&
            kotlin.math.abs(elapsedRealtimeMs - receivedElapsedMs) <= LOCATION_ALIGN_WINDOW_MS &&
            qualified()
}

/** GPS, then fused, then network. The stored fix keeps whichever provider actually delivered it. */
internal fun selectRecordingProvider(gpsEnabled: Boolean, fusedEnabled: Boolean, networkEnabled: Boolean): String? =
    when {
        gpsEnabled -> "gps"
        fusedEnabled -> "fused"
        networkEnabled -> "network"
        else -> null
    }

internal fun providerOrder(gpsEnabled: Boolean, fusedEnabled: Boolean, networkEnabled: Boolean): List<String> =
    buildList {
        if (gpsEnabled) add("gps")
        if (fusedEnabled) add("fused")
        if (networkEnabled) add("network")
    }

internal fun selectCachedFix(fixes: List<RecordedFix>): RecordedFix? {
    val qualified = fixes.filter { it.qualified() }
    return providerOrder(gpsEnabled = true, fusedEnabled = true, networkEnabled = true)
        .firstNotNullOfOrNull { provider -> qualified.firstOrNull { it.provider == provider } }
        ?: qualified.firstOrNull()
}

internal fun chooseCoarseFix(fixes: List<RecordedFix>): RecordedFix? =
    fixes.filter { it.accuracyM != null && it.accuracyM >= 0.0 && it.accuracyM.isFinite() }
        .minByOrNull { it.accuracyM!! }
        ?: fixes.lastOrNull()

/** A location file exists only for an open recording session whose task already has a DT/CQT choice. */
internal fun shouldOpenLocationSidecar(recordingSessionOpen: Boolean, mode: SurveyLocationMode?): Boolean =
    recordingSessionOpen && mode != null

/**
 * Remembers a task's DT/CQT choice without a survey_task column.
 * [snapshot] and [restore] stand in for the preference file across process restarts.
 */
internal class SurveyLocationModeMemory {
    private val values = LinkedHashMap<String, String>()

    fun remember(taskId: String, mode: SurveyLocationMode) {
        values[taskId] = mode.name
    }

    fun recall(taskId: String): SurveyLocationMode? = SurveyLocationMode.fromPreference(values[taskId])

    fun snapshot(): Map<String, String> = values.toMap()

    fun restore(saved: Map<String, String>) {
        values.clear()
        values.putAll(saved)
    }
}

/**
 * Location file born with [DiagRecordingSession.start] and archived with its finish.
 * DT appends a GPX track. CQT keeps one marked point until the user asks for another.
 * Map-point dedup (3 m / 15 s, removed in onPause) is intentionally not applied.
 */
internal class RecordingLocationSidecar(
    private val root: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {
    private val lock = Any()
    private var mode: SurveyLocationMode? = null
    private var taskId: String? = null
    private var clockId: String? = null
    private var segmentClock: String? = null
    private var writer: BufferedWriter? = null
    private var active: File? = null
    private var accepting = false
    private val cqtPoints = mutableListOf<String>()
    private var cqtMarks = 0
    private var remarkBudget = 0

    fun isAccepting(): Boolean = synchronized(lock) { accepting }

    fun open(taskId: String, mode: SurveyLocationMode, clockId: String) {
        val id = taskId.trim()
        require(id.isNotEmpty()) { "task id required" }
        require(clockId.isNotEmpty()) { "clock id required" }
        synchronized(lock) {
            check(!accepting && active == null) { "Location sidecar is still open" }
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Unable to create location directory")
            archiveOrphans()
            this.taskId = id
            this.mode = mode
            this.clockId = clockId
            segmentClock = null
            cqtPoints.clear()
            cqtMarks = 0
            remarkBudget = 0
            val file = File(root, if (mode == SurveyLocationMode.DT) ACTIVE_GPX else ACTIVE_CQT)
            if (mode == SurveyLocationMode.DT) {
                val out = file.bufferedWriter(Charsets.UTF_8)
                try {
                    out.write(gpxHeader(id, clockId))
                    out.flush()
                } catch (error: IOException) {
                    out.close()
                    file.delete()
                    throw error
                }
                writer = out
            } else {
                file.writeText(cqtDocument(id, clockId, emptyList()))
            }
            active = file
            accepting = true
        }
    }

    fun accept(fix: RecordedFix) {
        synchronized(lock) {
            if (!accepting || mode != SurveyLocationMode.DT || fix.taskId != taskId) return
            if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return
            if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return
            val out = writer ?: return
            if (segmentClock != fix.clockId) {
                if (segmentClock != null) out.write("</trkseg>\n<trkseg>\n")
                segmentClock = fix.clockId
            }
            out.write(trackPoint(fix))
            out.flush()
        }
    }

    /** One extra CQT mark. Stationary updates do not call this. */
    fun allowRemark(): Boolean = synchronized(lock) {
        if (!accepting || mode != SurveyLocationMode.CQT || cqtMarks <= 0) return false
        remarkBudget++
        true
    }

    fun captureCqt(fix: RecordedFix?, coarse: Boolean): Boolean = synchronized(lock) {
        val id = taskId ?: return false
        val clock = clockId ?: return false
        if (!accepting || mode != SurveyLocationMode.CQT) return false
        if (fix != null && fix.taskId != id) return false
        if (cqtMarks > 0 && remarkBudget <= 0) return false
        if (cqtMarks > 0) remarkBudget--
        val markedCoarse = coarse || fix == null || !fix.qualified()
        cqtPoints.add(cqtPoint(fix, markedCoarse, id, clock))
        cqtMarks++
        File(root, ACTIVE_CQT).writeText(cqtDocument(id, clock, cqtPoints))
        true
    }

    fun stopAccepting() {
        synchronized(lock) { accepting = false }
    }

    fun finish(): File? = synchronized(lock) {
        val file = active ?: return null
        val id = taskId
        val currentMode = mode
        val currentClock = clockId
        accepting = false
        try {
            if (currentMode == SurveyLocationMode.DT) {
                writer?.apply {
                    write("</trkseg></trk></gpx>\n")
                    flush()
                    close()
                }
                writer = null
            } else if (currentMode == SurveyLocationMode.CQT && id != null && currentClock != null) {
                if (cqtPoints.isEmpty()) cqtPoints.add(cqtPoint(null, coarse = true, id, currentClock))
                file.writeText(cqtDocument(id, currentClock, cqtPoints))
            }
        } catch (error: IOException) {
            writer?.runCatching { close() }
            writer = null
            active = null
            clearIdentity()
            throw error
        }
        if (!file.exists() || file.length() == 0L) {
            if (file.exists() && !file.delete()) throw IOException("Unable to remove empty location sidecar")
            clearIdentity()
            active = null
            return null
        }
        val extension = if (currentMode == SurveyLocationMode.CQT) CQT_EXTENSION else GPX_EXTENSION
        val dest = uniqueCommitted(id ?: "orphan", extension)
        if (!rename(file, dest)) {
            active = null
            clearIdentity()
            throw IOException("Unable to archive location sidecar")
        }
        active = null
        clearIdentity()
        dest
    }

    private fun clearIdentity() {
        mode = null
        taskId = null
        clockId = null
        segmentClock = null
        cqtPoints.clear()
        cqtMarks = 0
        remarkBudget = 0
        writer = null
    }

    private fun archiveOrphans() {
        archiveOrphan(ACTIVE_GPX, GPX_EXTENSION, seal = true)
        archiveOrphan(ACTIVE_CQT, CQT_EXTENSION, seal = false)
    }

    private fun archiveOrphan(name: String, extension: String, seal: Boolean) {
        val file = File(root, name)
        if (!file.exists()) return
        if (file.length() == 0L) {
            if (!file.delete()) throw IOException("Unable to remove empty location sidecar")
            return
        }
        if (seal) sealGpx(file)
        val dest = uniqueCommitted("orphan", extension)
        if (!rename(file, dest)) throw IOException("Unable to archive orphan location sidecar")
    }

    private fun sealGpx(file: File) {
        val text = file.readText(Charsets.UTF_8)
        if (text.contains("</gpx>")) return
        file.appendText("</trkseg></trk></gpx>\n", Charsets.UTF_8)
    }

    private fun uniqueCommitted(taskId: String, extension: String): File {
        var index = 1
        var dest = File(root, committedName(taskId, extension, index))
        while (dest.exists() || dest.name == ACTIVE_GPX || dest.name == ACTIVE_CQT) {
            index++
            dest = File(root, committedName(taskId, extension, index))
        }
        return dest
    }

    private fun committedName(taskId: String, extension: String, index: Int): String {
        val safe = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "task" }
        return if (index == 1) "$safe.$extension" else "$safe-$index.$extension"
    }

    companion object {
        const val ACTIVE_GPX = "active.gpx"
        const val ACTIVE_CQT = "active.cqt.json"
        private const val GPX_EXTENSION = "gpx"
        private const val CQT_EXTENSION = "cqt.json"
    }
}

private fun gpxHeader(taskId: String, clockId: String): String = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="WirelessSignalMeter" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><extensions><mode>DT</mode><task_id>${xmlEscape(taskId)}</task_id><clock_id>${xmlEscape(clockId)}</clock_id></extensions></metadata>
<trk>
<trkseg>
""".trimStart()

private fun trackPoint(fix: RecordedFix): String {
    val time = runCatching { Instant.ofEpochMilli(fix.wallTimeMs).toString() }.getOrElse { Instant.EPOCH.toString() }
    val ele = fix.altitudeM?.takeIf { it.isFinite() }?.let { "<ele>${jsonNumber(it)}</ele>" }.orEmpty()
    return buildString {
        append("<trkpt lat=\"").append(jsonNumber(fix.latitude))
        append("\" lon=\"").append(jsonNumber(fix.longitude)).append("\">")
        append(ele)
        append("<time>").append(time).append("</time><extensions>")
        fix.accuracyM?.takeIf { it.isFinite() }?.let { append("<accuracy_m>").append(jsonNumber(it)).append("</accuracy_m>") }
        fix.speedMps?.takeIf { it.isFinite() }?.let { append("<speed_mps>").append(jsonNumber(it)).append("</speed_mps>") }
        fix.provider?.let { append("<provider>").append(xmlEscape(it)).append("</provider>") }
        append("<age_ms>").append(fix.ageMs).append("</age_ms>")
        append("<wall_time_ms>").append(fix.wallTimeMs).append("</wall_time_ms>")
        append("<elapsed_realtime_ms>").append(fix.elapsedRealtimeMs).append("</elapsed_realtime_ms>")
        append("<clock_id>").append(xmlEscape(fix.clockId)).append("</clock_id>")
        append("<task_id>").append(xmlEscape(fix.taskId)).append("</task_id>")
        append("</extensions></trkpt>\n")
    }
}

private fun cqtDocument(taskId: String, clockId: String, points: List<String>): String =
    "{\"mode\":\"CQT\",\"task_id\":${jsonString(taskId)},\"clock_id\":${jsonString(clockId)},\"points\":[${points.joinToString(",")}]}\n"

private fun cqtPoint(fix: RecordedFix?, coarse: Boolean, taskId: String, clockId: String): String {
    fun num(value: Double?) = if (value == null || !value.isFinite()) "null" else jsonNumber(value)
    return buildString {
        append("{\"latitude\":").append(num(fix?.latitude))
        append(",\"longitude\":").append(num(fix?.longitude))
        append(",\"altitude_m\":").append(num(fix?.altitudeM))
        append(",\"accuracy_m\":").append(num(fix?.accuracyM))
        append(",\"speed_mps\":").append(num(fix?.speedMps))
        append(",\"provider\":").append(if (fix?.provider == null) "null" else jsonString(fix.provider))
        append(",\"age_ms\":").append(fix?.ageMs?.toString() ?: "null")
        append(",\"wall_time_ms\":").append(fix?.wallTimeMs?.toString() ?: "null")
        append(",\"elapsed_realtime_ms\":").append(fix?.elapsedRealtimeMs?.toString() ?: "null")
        append(",\"clock_id\":").append(jsonString(fix?.clockId ?: clockId))
        append(",\"task_id\":").append(jsonString(fix?.taskId ?: taskId))
        append(",\"coarse\":").append(if (coarse) "true" else "false")
        append("}")
    }
}

internal fun xmlEscape(value: String): String = buildString {
    for (ch in value) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> if (ch.code >= 0x20) append(ch)
    }
}

internal fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch.code < 0x20) append("\\u%04x".format(Locale.US, ch.code)) else append(ch)
    }
    append('"')
}

internal fun jsonNumber(value: Double): String {
    if (!value.isFinite()) return "null"
    return String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
