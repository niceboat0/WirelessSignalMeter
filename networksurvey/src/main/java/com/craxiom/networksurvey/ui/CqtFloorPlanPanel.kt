package com.craxiom.networksurvey.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.db.SurveyTaskStore
import com.craxiom.networksurvey.ui.rootdiag.CQT_FLOOR_IMAGE_MAX_BYTES
import com.craxiom.networksurvey.ui.rootdiag.CQT_FLOOR_IMPORT_MAX_BYTES
import com.craxiom.networksurvey.ui.rootdiag.CQT_FLOOR_MAX_EDGE_PX
import com.craxiom.networksurvey.ui.rootdiag.floorPlanSampleSize
import com.craxiom.networksurvey.ui.rootdiag.readAtMost
import com.craxiom.networksurvey.ui.rootdiag.CqtCanvasPoint
import com.craxiom.networksurvey.ui.rootdiag.CqtFloorKind
import com.craxiom.networksurvey.ui.rootdiag.CqtFloorMarks
import com.craxiom.networksurvey.ui.rootdiag.CqtFloorPlan
import com.craxiom.networksurvey.ui.rootdiag.CqtFloorPlanLibrary
import com.craxiom.networksurvey.ui.rootdiag.StoredCqtFloor
import com.craxiom.networksurvey.ui.rootdiag.fitContent
import com.craxiom.networksurvey.ui.rootdiag.floorSourceName
import com.craxiom.networksurvey.ui.rootdiag.mapTapToCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun CqtFloorPlanPanel(modifier: Modifier = Modifier, onShowReplay: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var taskId by remember { mutableStateOf<String?>(null) }
    var libraryFloor by remember { mutableStateOf(CqtFloorPlan.blank()) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val sessionActive by CqtFloorMarks.sessionActive.collectAsState()
    val sessionFloor by CqtFloorMarks.floor.collectAsState()
    val points by CqtFloorMarks.points.collectAsState()
    val floor = if (sessionActive) sessionFloor else libraryFloor
    val library = remember(context) { CqtFloorPlanLibrary(cqtFloorDirectory(context)) }

    LaunchedEffect(context) {
        val store = withContext(Dispatchers.IO) { SurveyTaskStore.get(context) }
        store.active.collect { task ->
            taskId = task?.id
            val stored = withContext(Dispatchers.IO) {
                if (task == null) StoredCqtFloor(CqtFloorPlan.blank(), null) else library.recall(task.id)
            }
            libraryFloor = stored.plan
            bitmap = stored.image?.let { decodeFloorImage(it) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = taskId
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        if (!CqtFloorMarks.canReplaceFloor()) {
            Toast.makeText(context, R.string.cqt_floor_locked, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { importFloorPlan(context, library, id, uri) }
            }
            saved.onSuccess { (plan, image) ->
                libraryFloor = plan
                bitmap = image
            }.onFailure {
                val message = when {
                    it is IllegalArgumentException && it.message == "large" -> R.string.cqt_floor_image_large
                    it is IllegalStateException -> R.string.cqt_floor_locked
                    else -> R.string.cqt_floor_image_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }) {
                Text(stringResource(R.string.cqt_floor_import))
            }
            TextButton(onClick = {
                val id = taskId ?: return@TextButton
                if (!CqtFloorMarks.canReplaceFloor()) {
                    Toast.makeText(context, R.string.cqt_floor_locked, Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        if (CqtFloorMarks.sessionActive.value &&
                            !CqtFloorMarks.replaceFloor(CqtFloorPlan.blank(), null)
                        ) {
                            false
                        } else {
                            if (!CqtFloorMarks.sessionActive.value) CqtFloorMarks.noteFloorEdited()
                            library.saveBlank(id)
                            true
                        }
                    }
                    if (!saved) {
                        Toast.makeText(context, R.string.cqt_floor_locked, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    libraryFloor = CqtFloorPlan.blank()
                    bitmap = null
                }
            }) { Text(stringResource(R.string.cqt_floor_blank)) }
            TextButton(enabled = sessionActive && points.isNotEmpty(), onClick = { CqtFloorMarks.undo() }) {
                Text(stringResource(R.string.cqt_floor_undo))
            }
            TextButton(onClick = onShowReplay) { Text(stringResource(R.string.cqt_show_replay)) }
        }
        Text(
            stringResource(if (sessionActive) R.string.cqt_floor_hint_live else R.string.cqt_floor_hint_idle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.cqt_floor_count, points.size),
            style = MaterialTheme.typography.labelMedium,
        )
        val sheet = if (floor.kind == CqtFloorKind.IMAGE) bitmap else null
        val outline = MaterialTheme.colorScheme.outline
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(floor, sessionActive, taskId) {
                    detectTapGestures { offset ->
                        if (!sessionActive) return@detectTapGestures
                        val mapped = mapTapToCanvas(
                            offset.x, offset.y, size.width.toFloat(), size.height.toFloat(),
                            floor.widthPx, floor.heightPx,
                        ) ?: return@detectTapGestures
                        val id = taskId ?: return@detectTapGestures
                        CqtFloorMarks.mark(
                            CqtCanvasPoint(
                                xPx = mapped.first,
                                yPx = mapped.second,
                                canvasWidthPx = floor.widthPx,
                                canvasHeightPx = floor.heightPx,
                                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                                wallTimeMs = System.currentTimeMillis(),
                                clockId = "",
                                taskId = id,
                                gnss = null,
                            ),
                        )
                    }
                },
        ) {
            val fit = fitContent(size.width, size.height, floor.widthPx.toFloat(), floor.heightPx.toFloat())
                ?: return@Canvas
            if (sheet != null) {
                drawImage(
                    sheet,
                    dstOffset = androidx.compose.ui.unit.IntOffset(fit.left.toInt(), fit.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(fit.width.toInt().coerceAtLeast(1), fit.height.toInt().coerceAtLeast(1)),
                )
            } else {
                drawRect(Color(0xFFF7F4EC), Offset(fit.left, fit.top), Size(fit.width, fit.height))
                val grid = Color(0xFFD9D3C5)
                for (step in 1 until 8) {
                    val x = fit.left + fit.width * step / 8f
                    val y = fit.top + fit.height * step / 8f
                    drawLine(grid, Offset(x, fit.top), Offset(x, fit.top + fit.height))
                    drawLine(grid, Offset(fit.left, y), Offset(fit.left + fit.width, y))
                }
            }
            drawRect(outline, Offset(fit.left, fit.top), Size(fit.width, fit.height), style = Stroke(2f))
            val label = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 12.dp.toPx()
                isAntiAlias = true
            }
            points.forEachIndexed { index, point ->
                if (point.canvasWidthPx != floor.widthPx || point.canvasHeightPx != floor.heightPx) return@forEachIndexed
                val x = fit.left + (point.xPx / floor.widthPx).toFloat() * fit.width
                val y = fit.top + (point.yPx / floor.heightPx).toFloat() * fit.height
                drawCircle(Color(0xFF1565C0), 11.dp.toPx(), Offset(x, y))
                drawContext.canvas.nativeCanvas.drawText("${index + 1}", x, y + 4.dp.toPx(), label)
            }
        }
    }
}

private fun cqtFloorDirectory(context: android.content.Context): File =
    File(context.applicationContext.noBackupFilesDir, "qmdl/floor-plans")

private fun importFloorPlan(
    context: android.content.Context,
    library: CqtFloorPlanLibrary,
    taskId: String,
    uri: Uri,
): Pair<CqtFloorPlan, ImageBitmap?> {
    val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("image")
    val raw = stream.use { readAtMost(it, CQT_FLOOR_IMPORT_MAX_BYTES) } ?: throw IllegalArgumentException("large")
    val image = decodeFloorImage(raw) ?: throw IllegalArgumentException("image")
    val bytes = encodeFloorImage(image)
    val plan = CqtFloorPlan(
        CqtFloorKind.IMAGE,
        image.width,
        image.height,
        floorSourceName(displayName(context, uri)),
    )
    if (CqtFloorMarks.sessionActive.value) {
        if (!CqtFloorMarks.replaceFloor(plan, bytes)) throw IllegalStateException("locked")
    } else {
        CqtFloorMarks.noteFloorEdited()
    }
    val stored = library.saveImage(taskId, plan.sourceName, plan.widthPx, plan.heightPx, bytes)
    return stored to image
}

private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

internal fun decodeFloorImage(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty() || bytes.size > CQT_FLOOR_IMPORT_MAX_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = floorPlanSampleSize(bounds.outWidth, bounds.outHeight, CQT_FLOOR_MAX_EDGE_PX)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    if (decoded.width <= CQT_FLOOR_MAX_EDGE_PX && decoded.height <= CQT_FLOOR_MAX_EDGE_PX) {
        return decoded.asImageBitmap()
    }
    // inSampleSize can round up. Do not scale a bitmap that is still a full-resolution photo.
    if (decoded.width > CQT_FLOOR_MAX_EDGE_PX * 2 || decoded.height > CQT_FLOOR_MAX_EDGE_PX * 2) {
        decoded.recycle()
        return null
    }
    val scale = CQT_FLOOR_MAX_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled != decoded) decoded.recycle()
    return scaled.asImageBitmap()
}

private fun encodeFloorImage(image: ImageBitmap): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 80, stream)) {
        throw IllegalArgumentException("image")
    }
    val bytes = stream.toByteArray()
    if (bytes.isEmpty() || bytes.size > CQT_FLOOR_IMAGE_MAX_BYTES) throw IllegalArgumentException("large")
    return bytes
}
