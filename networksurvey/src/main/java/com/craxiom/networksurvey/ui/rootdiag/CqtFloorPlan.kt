package com.craxiom.networksurvey.ui.rootdiag

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Logical pixel size of the blank sheet used when no floor-plan image is imported. */
internal const val CQT_BLANK_WIDTH_PX = 1_000
internal const val CQT_BLANK_HEIGHT_PX = 1_000
/** Compressed bytes kept for a plan. The import path never stores a larger original. */
internal const val CQT_FLOOR_IMAGE_MAX_BYTES = 4 * 1024 * 1024
/** Stop reading a content URI once it passes this, instead of buffering the rest. */
internal const val CQT_FLOOR_IMPORT_MAX_BYTES = 32 * 1024 * 1024
/** Longest edge of a decoded or stored floor plan. Full-resolution photos are sampled down to this. */
internal const val CQT_FLOOR_MAX_EDGE_PX = 2_048
internal const val ACTIVE_CQT_PLAN = "active.cqt-plan"

internal enum class CqtFloorKind { BLANK, IMAGE }

/**
 * Base map for one CQT task.
 * [widthPx] and [heightPx] are the coordinate space of every tap, including a blank sheet.
 */
internal data class CqtFloorPlan(
    val kind: CqtFloorKind,
    val widthPx: Int,
    val heightPx: Int,
    val sourceName: String?,
) {
    companion object {
        fun blank() = CqtFloorPlan(CqtFloorKind.BLANK, CQT_BLANK_WIDTH_PX, CQT_BLANK_HEIGHT_PX, null)
    }
}

internal data class StoredCqtFloor(val plan: CqtFloorPlan, val image: ByteArray?)

/**
 * One manual tap on the floor plan.
 * [xPx] and [yPx] are the mark. [gnss] is optional quality metadata and does not replace the tap.
 * [elapsedRealtimeMs] plus [clockId] line the tap up with diagnostic rows from the same recording.
 */
internal data class CqtCanvasPoint(
    val xPx: Double,
    val yPx: Double,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val elapsedRealtimeMs: Long,
    val wallTimeMs: Long,
    val clockId: String,
    val taskId: String,
    val gnss: RecordedFix?,
) {
    fun fits(plan: CqtFloorPlan): Boolean =
        canvasWidthPx == plan.widthPx && canvasHeightPx == plan.heightPx &&
            plan.widthPx > 0 && plan.heightPx > 0 &&
            xPx.isFinite() && yPx.isFinite() &&
            xPx in 0.0..plan.widthPx.toDouble() && yPx in 0.0..plan.heightPx.toDouble()

    fun matchesDiagnostic(receivedElapsedMs: Long, receivedClockId: String): Boolean =
        clockId == receivedClockId &&
            kotlin.math.abs(elapsedRealtimeMs - receivedElapsedMs) <= LOCATION_ALIGN_WINDOW_MS
}

internal data class CanvasFit(val left: Float, val top: Float, val width: Float, val height: Float)

/** Letterboxed rectangle used to draw [contentWidth] x [contentHeight] inside the view. */
internal fun fitContent(viewWidth: Float, viewHeight: Float, contentWidth: Float, contentHeight: Float): CanvasFit? {
    if (viewWidth <= 0f || viewHeight <= 0f || contentWidth <= 0f || contentHeight <= 0f) return null
    val scale = minOf(viewWidth / contentWidth, viewHeight / contentHeight)
    val width = contentWidth * scale
    val height = contentHeight * scale
    return CanvasFit((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
}

/** Map a view tap into floor-plan pixels. A tap in the letterbox is not a point. */
internal fun mapTapToCanvas(
    tapX: Float,
    tapY: Float,
    viewWidth: Float,
    viewHeight: Float,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
): Pair<Double, Double>? {
    val fit = fitContent(viewWidth, viewHeight, canvasWidthPx.toFloat(), canvasHeightPx.toFloat()) ?: return null
    if (tapX < fit.left || tapY < fit.top || tapX > fit.left + fit.width || tapY > fit.top + fit.height) return null
    val x = (tapX - fit.left) / fit.width * canvasWidthPx
    val y = (tapY - fit.top) / fit.height * canvasHeightPx
    return x.toDouble() to y.toDouble()
}

private fun unescapeFloorName(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            append(when (value[index + 1]) {
                '"' -> '"'
                '\\' -> '\\'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> value[index + 1]
            })
            index += 2
        } else {
            append(ch)
            index++
        }
    }
}

/**
 * Power-of-two sample size so a decoded bitmap stays within [maxEdge].
 * Callers must pass this to the decoder. They must not allocate the source dimensions.
 */
internal fun floorPlanSampleSize(width: Int, height: Int, maxEdge: Int = CQT_FLOOR_MAX_EDGE_PX): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    val largest = maxOf(width, height)
    while (largest / sample > maxEdge) {
        val next = sample shl 1
        if (next <= sample) break
        sample = next
    }
    return sample
}

/**
 * Read [input] only while it stays within [maxBytes].
 * A longer stream returns null and is not copied further.
 */
internal fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
    if (maxBytes < 0) return null
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return out.toByteArray()
        if (count > maxBytes - total) return null
        out.write(buffer, 0, count)
        total += count
    }
}

internal fun floorSourceName(raw: String?): String? {
    val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    val clean = base.filter { it.code >= 0x20 && it != '"' && it != '\\' }.take(80)
    return clean.ifEmpty { null }
}

/**
 * Floor plan chosen for a task, kept outside the recording directory so it is not exported by itself.
 * The recording sidecar copies the image when a CQT session opens.
 */
internal class CqtFloorPlanLibrary(private val directory: File) {
    fun recall(taskId: String): StoredCqtFloor {
        val meta = readMeta(taskId) ?: return StoredCqtFloor(CqtFloorPlan.blank(), null)
        if (meta.kind != CqtFloorKind.IMAGE) return StoredCqtFloor(CqtFloorPlan.blank(), null)
        val image = imageFile(taskId)
        if (!image.isFile || image.length() <= 0L || image.length() > CQT_FLOOR_IMAGE_MAX_BYTES) {
            return StoredCqtFloor(CqtFloorPlan.blank(), null)
        }
        val bytes = image.readBytes()
        return StoredCqtFloor(meta, bytes)
    }

    fun saveBlank(taskId: String) {
        require(taskId.isNotBlank())
        metaFile(taskId).delete()
        imageFile(taskId).delete()
    }

    fun saveImage(taskId: String, sourceName: String?, widthPx: Int, heightPx: Int, bytes: ByteArray): CqtFloorPlan {
        require(taskId.isNotBlank())
        if (bytes.isEmpty() || bytes.size > CQT_FLOOR_IMAGE_MAX_BYTES) {
            throw IllegalArgumentException("floor plan image")
        }
        if (widthPx !in 1..CQT_FLOOR_MAX_EDGE_PX || heightPx !in 1..CQT_FLOOR_MAX_EDGE_PX) {
            throw IllegalArgumentException("floor plan size")
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to store floor plan")
        val image = imageFile(taskId)
        val meta = metaFile(taskId)
        image.writeBytes(bytes)
        val plan = CqtFloorPlan(CqtFloorKind.IMAGE, widthPx, heightPx, floorSourceName(sourceName))
        try {
            meta.writeText(buildString {
                append("{\"kind\":\"image\"")
                append(",\"width_px\":").append(widthPx)
                append(",\"height_px\":").append(heightPx)
                append(",\"source_name\":")
                append(if (plan.sourceName == null) "null" else jsonString(plan.sourceName))
                append("}\n")
            })
        } catch (error: IOException) {
            image.delete()
            throw error
        }
        return plan
    }

    private fun readMeta(taskId: String): CqtFloorPlan? {
        val file = metaFile(taskId)
        if (!file.isFile) return null
        val text = file.readText()
        if (!text.contains("\"kind\":\"image\"")) return CqtFloorPlan.blank()
        val width = Regex("\"width_px\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = Regex("\"height_px\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (width !in 1..CQT_FLOOR_MAX_EDGE_PX || height !in 1..CQT_FLOOR_MAX_EDGE_PX) return null
        val name = Regex("\"source_name\":\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)
            ?.let(::unescapeFloorName)
        return CqtFloorPlan(CqtFloorKind.IMAGE, width, height, floorSourceName(name))
    }

    private fun metaFile(taskId: String) = File(directory, "${sanitizeTaskId(taskId)}.json")
    private fun imageFile(taskId: String) = File(directory, "${sanitizeTaskId(taskId)}.img")
}

/**
 * UI bridge for taps during the open CQT recording.
 * The recording sidecar is the store; this only mirrors the points still on the open segment.
 */
internal object CqtFloorMarks {
    val sessionActive = MutableStateFlow(false)
    val points = MutableStateFlow<List<CqtCanvasPoint>>(emptyList())
    val floor = MutableStateFlow(CqtFloorPlan.blank())
    private var onMark: ((CqtCanvasPoint) -> Boolean)? = null
    private var onUndo: (() -> Boolean)? = null
    private var onReplaceFloor: ((CqtFloorPlan, ByteArray?) -> Boolean)? = null

    fun bind(
        plan: CqtFloorPlan,
        onMark: (CqtCanvasPoint) -> Boolean,
        onUndo: () -> Boolean,
        onReplaceFloor: (CqtFloorPlan, ByteArray?) -> Boolean,
    ) {
        this.onMark = onMark
        this.onUndo = onUndo
        this.onReplaceFloor = onReplaceFloor
        floor.value = plan
        points.value = emptyList()
        sessionActive.value = true
    }

    fun clear() {
        onMark = null
        onUndo = null
        onReplaceFloor = null
        sessionActive.value = false
    }

    fun canReplaceFloor(): Boolean = !sessionActive.value || points.value.isEmpty()

    /** Drop on-screen dots after the base map changes and the segment is no longer open. */
    fun noteFloorEdited() {
        if (!sessionActive.value) points.value = emptyList()
    }

    fun mark(point: CqtCanvasPoint): Boolean {
        val ok = onMark?.invoke(point) == true
        if (ok) points.value = points.value + point
        return ok
    }

    fun undo(): Boolean {
        val ok = onUndo?.invoke() == true
        if (ok && points.value.isNotEmpty()) points.value = points.value.dropLast(1)
        return ok
    }

    fun replaceFloor(plan: CqtFloorPlan, image: ByteArray?): Boolean {
        val callback = onReplaceFloor ?: return !sessionActive.value
        val ok = callback(plan, image)
        if (ok) floor.value = plan
        return ok
    }
}

/** Keep a location sidecar only when the air-interface segment itself is kept. */
internal fun shouldRetainLocationSidecar(sessionOpen: Boolean, airBytes: Long): Boolean =
    sessionOpen && airBytes > 0L
