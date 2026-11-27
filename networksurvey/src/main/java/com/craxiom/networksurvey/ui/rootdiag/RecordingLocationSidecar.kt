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

internal fun sanitizeTaskId(taskId: String): String =
    taskId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "task" }

/** Stem shared by an air-interface file and its location sidecars. `.cqt.json` keeps both suffixes. */
internal fun committedStem(fileName: String): String = when {
    fileName.endsWith(".cqt.json") -> fileName.removeSuffix(".cqt.json")
    fileName.endsWith(".cqt-plan") -> fileName.removeSuffix(".cqt-plan")
    fileName.endsWith(".qmdl") -> fileName.removeSuffix(".qmdl")
    fileName.endsWith(".dlf") -> fileName.removeSuffix(".dlf")
    fileName.endsWith(".gpx") -> fileName.removeSuffix(".gpx")
    else -> fileName.substringBeforeLast('.')
}

internal fun locationSidecarsFor(root: File, airFiles: List<File>): List<File> {
    val suffixes = listOf(".gpx", ".cqt.json", ".cqt-plan")
    return airFiles.map { committedStem(it.name) }.distinct().flatMap { stem ->
        suffixes.map { File(root, stem + it) }
    }.filter { file -> file.isFile && file.length() > 0L && airFiles.none { it.absolutePath == file.absolutePath } }
        .sortedBy { it.name }
}

/**
 * Location file born with [DiagRecordingSession.start] and archived with its finish.
 * DT appends a GPX track. CQT stores floor-plan taps in one JSON file for the whole segment.
 * Another tap does not open a new air-interface file. Map-point dedup is not applied to the GPX track.
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
    private var floor = CqtFloorPlan.blank()
    private val cqtMarks = mutableListOf<CqtCanvasPoint>()

    fun isAccepting(): Boolean = synchronized(lock) { accepting }

    fun open(
        taskId: String,
        mode: SurveyLocationMode,
        clockId: String,
        floor: CqtFloorPlan = CqtFloorPlan.blank(),
        floorImage: ByteArray? = null,
    ) {
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
            cqtMarks.clear()
            this.floor = if (mode == SurveyLocationMode.CQT) floor else CqtFloorPlan.blank()
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
                val image = floorImage?.takeIf { floor.kind == CqtFloorKind.IMAGE && it.isNotEmpty() }
                if (floor.kind == CqtFloorKind.IMAGE && image == null) this.floor = CqtFloorPlan.blank()
                writePlan(image)
                file.writeText(cqtDocument(id, clockId, this.floor, planFileName(), cqtMarks))
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

    /** Append one floor-plan tap. This does not open or split an air-interface file. */
    fun mark(point: CqtCanvasPoint): Boolean = synchronized(lock) {
        val id = taskId ?: return false
        if (!accepting || mode != SurveyLocationMode.CQT || point.taskId != id) return false
        if (!point.fits(floor)) return false
        val stored = point.copy(gnss = point.gnss?.takeIf { it.taskId == id && it.latitude.isFinite() && it.longitude.isFinite() })
        cqtMarks.add(stored)
        try {
            rewriteCqt()
        } catch (error: IOException) {
            cqtMarks.removeAt(cqtMarks.lastIndex)
            throw error
        }
        true
    }

    fun undo(): Boolean = synchronized(lock) {
        if (!accepting || mode != SurveyLocationMode.CQT || cqtMarks.isEmpty()) return false
        val removed = cqtMarks.removeAt(cqtMarks.lastIndex)
        try {
            rewriteCqt()
        } catch (error: IOException) {
            cqtMarks.add(removed)
            throw error
        }
        true
    }

    fun replaceFloor(plan: CqtFloorPlan, image: ByteArray?): Boolean = synchronized(lock) {
        if (!accepting || mode != SurveyLocationMode.CQT || cqtMarks.isNotEmpty()) return false
        val next = if (plan.kind == CqtFloorKind.IMAGE && (image == null || image.isEmpty())) CqtFloorPlan.blank() else plan
        val bytes = if (next.kind == CqtFloorKind.IMAGE) image else null
        floor = next
        writePlan(bytes)
        rewriteCqt()
        true
    }

    fun stopAccepting() {
        synchronized(lock) { accepting = false }
    }

    /** Delete the open sidecar. Used when the air-interface segment is discarded. */
    fun discard() {
        synchronized(lock) {
            accepting = false
            writer?.runCatching { close() }
            writer = null
            discardActiveLocationSidecars(root)
            active = null
            clearIdentity()
        }
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
                file.writeText(cqtDocument(id, currentClock, floor, planFileName(), cqtMarks))
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
            discardActiveLocationSidecars(root)
            clearIdentity()
            active = null
            return null
        }
        val extension = if (currentMode == SurveyLocationMode.CQT) CQT_EXTENSION else GPX_EXTENSION
        val dest = uniqueCommitted(id ?: "orphan", extension)
        if (currentMode == SurveyLocationMode.CQT && id != null && currentClock != null) {
            val stem = committedStem(dest.name)
            val plan = File(root, ACTIVE_CQT_PLAN)
            val planName = if (plan.isFile && plan.length() > 0L) "$stem.cqt-plan" else null
            file.writeText(cqtDocument(id, currentClock, floor, planName, cqtMarks))
            if (planName != null && !rename(plan, File(root, planName))) {
                active = null
                clearIdentity()
                throw IOException("Unable to archive floor plan")
            }
        }
        if (!rename(file, dest)) {
            active = null
            clearIdentity()
            throw IOException("Unable to archive location sidecar")
        }
        active = null
        clearIdentity()
        dest
    }

    private fun planFileName(): String? {
        val plan = File(root, ACTIVE_CQT_PLAN)
        return if (plan.isFile && plan.length() > 0L) ACTIVE_CQT_PLAN else null
    }

    private fun rewriteCqt() {
        val id = taskId ?: return
        val clock = clockId ?: return
        File(root, ACTIVE_CQT).writeText(cqtDocument(id, clock, floor, planFileName(), cqtMarks))
    }

    private fun writePlan(image: ByteArray?) {
        val plan = File(root, ACTIVE_CQT_PLAN)
        if (image == null) {
            if (plan.exists() && !plan.delete()) throw IOException("Unable to remove floor plan")
        } else {
            plan.writeBytes(image)
        }
    }

    private fun clearIdentity() {
        mode = null
        taskId = null
        clockId = null
        segmentClock = null
        floor = CqtFloorPlan.blank()
        cqtMarks.clear()
        writer = null
    }

    private fun archiveOrphans() {
        archiveOrphan(ACTIVE_GPX, GPX_EXTENSION, seal = true)
        archiveOrphan(ACTIVE_CQT, CQT_EXTENSION, seal = false)
        archiveOrphan(ACTIVE_CQT_PLAN, "cqt-plan", seal = false)
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

    private fun uniqueCommitted(taskId: String, extension: String): File {
        var index = 1
        var dest = File(root, committedName(taskId, extension, index))
        while (dest.exists() || dest.name == ACTIVE_GPX || dest.name == ACTIVE_CQT || dest.name == ACTIVE_CQT_PLAN) {
            index++
            dest = File(root, committedName(taskId, extension, index))
        }
        return dest
    }

    private fun committedName(taskId: String, extension: String, index: Int): String {
        val safe = sanitizeTaskId(taskId)
        return if (index == 1) "$safe.$extension" else "$safe-$index.$extension"
    }

    companion object {
        const val ACTIVE_GPX = "active.gpx"
        const val ACTIVE_CQT = "active.cqt.json"
        private const val GPX_EXTENSION = "gpx"
        private const val CQT_EXTENSION = "cqt.json"
    }
}

internal fun discardActiveLocationSidecars(root: File) {
    for (name in listOf(RecordingLocationSidecar.ACTIVE_GPX, RecordingLocationSidecar.ACTIVE_CQT, ACTIVE_CQT_PLAN)) {
        val file = File(root, name)
        if (file.exists() && !file.delete()) throw IOException("Unable to remove location sidecar")
    }
}

/** Move leftover sidecars next to the air file that was just archived under [stem]. */
internal fun archiveActiveLocationSidecars(root: File, stem: String, rename: (File, File) -> Boolean) {
    moveActiveSidecar(root, RecordingLocationSidecar.ACTIVE_GPX, stem, ".gpx", seal = true, rename)
    moveActiveSidecar(root, RecordingLocationSidecar.ACTIVE_CQT, stem, ".cqt.json", seal = false, rename)
    moveActiveSidecar(root, ACTIVE_CQT_PLAN, stem, ".cqt-plan", seal = false, rename)
}

internal fun latestCommittedAirStem(root: File, taskId: String): String? {
    val safe = sanitizeTaskId(taskId)
    return root.listFiles().orEmpty().mapNotNull { file ->
        if (!file.isFile || file.length() == 0L || file.name.startsWith("active.")) return@mapNotNull null
        if (!file.name.endsWith(".qmdl") && !file.name.endsWith(".dlf")) return@mapNotNull null
        val stem = committedStem(file.name)
        if (stem != safe && !stem.startsWith("$safe-")) return@mapNotNull null
        val index = if (stem == safe) 1 else stem.removePrefix("$safe-").toIntOrNull() ?: return@mapNotNull null
        stem to index
    }.maxByOrNull { it.second }?.first
}

private fun moveActiveSidecar(
    root: File,
    activeName: String,
    stem: String,
    suffix: String,
    seal: Boolean,
    rename: (File, File) -> Boolean,
) {
    val file = File(root, activeName)
    if (!file.exists()) return
    if (file.length() == 0L) {
        if (!file.delete()) throw IOException("Unable to remove empty location sidecar")
        return
    }
    if (seal) sealGpx(file)
    var index = 1
    var dest = File(root, stem + suffix)
    while (dest.exists()) {
        index++
        dest = File(root, "$stem-$index$suffix")
    }
    if (!rename(file, dest)) throw IOException("Unable to archive location sidecar")
}

private fun sealGpx(file: File) {
    val text = file.readText(Charsets.UTF_8)
    if (text.contains("</gpx>")) return
    file.appendText("</trkseg></trk></gpx>\n", Charsets.UTF_8)
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

private fun cqtDocument(
    taskId: String,
    clockId: String,
    floor: CqtFloorPlan,
    planFileName: String?,
    points: List<CqtCanvasPoint>,
): String {
    val floorJson = buildString {
        append("{\"kind\":").append(jsonString(if (floor.kind == CqtFloorKind.IMAGE) "image" else "blank"))
        append(",\"width_px\":").append(floor.widthPx)
        append(",\"height_px\":").append(floor.heightPx)
        append(",\"source_name\":").append(if (floor.sourceName == null) "null" else jsonString(floor.sourceName))
        append(",\"file\":").append(if (planFileName == null) "null" else jsonString(planFileName))
        append("}")
    }
    return "{\"mode\":\"CQT\",\"task_id\":${jsonString(taskId)},\"clock_id\":${jsonString(clockId)}," +
        "\"floor_plan\":$floorJson,\"points\":[${points.joinToString(",") { cqtPoint(it) }}]}\n"
}

private fun cqtPoint(point: CqtCanvasPoint): String = buildString {
    append("{\"x_px\":").append(jsonNumber(point.xPx))
    append(",\"y_px\":").append(jsonNumber(point.yPx))
    append(",\"canvas_width_px\":").append(point.canvasWidthPx)
    append(",\"canvas_height_px\":").append(point.canvasHeightPx)
    append(",\"elapsed_realtime_ms\":").append(point.elapsedRealtimeMs)
    append(",\"wall_time_ms\":").append(point.wallTimeMs)
    append(",\"clock_id\":").append(jsonString(point.clockId))
    append(",\"task_id\":").append(jsonString(point.taskId))
    append(",\"gnss\":").append(gnssJson(point.gnss))
    append("}")
}

private fun gnssJson(fix: RecordedFix?): String {
    if (fix == null) return "null"
    fun num(value: Double?) = if (value == null || !value.isFinite()) "null" else jsonNumber(value)
    return buildString {
        append("{\"latitude\":").append(num(fix.latitude))
        append(",\"longitude\":").append(num(fix.longitude))
        append(",\"accuracy_m\":").append(num(fix.accuracyM))
        append(",\"age_ms\":").append(fix.ageMs)
        append(",\"provider\":").append(if (fix.provider == null) "null" else jsonString(fix.provider))
        append(",\"elapsed_realtime_ms\":").append(fix.elapsedRealtimeMs)
        append(",\"clock_id\":").append(jsonString(fix.clockId))
        append(",\"coarse\":").append(if (fix.qualified()) "false" else "true")
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
