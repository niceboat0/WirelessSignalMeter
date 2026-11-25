package com.craxiom.networksurvey.ui.cellular

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.rootdiag.SIGNAL_UNAVAILABLE
import com.craxiom.networksurvey.ui.theme.InterfaceStyle
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

private val signalPlotHeight = 144.dp

@Composable
internal fun CellularChartComponent(viewModel: CellularChartViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lastTime = history.samples.lastOrNull()?.time
    LaunchedEffect(lastTime, lifecycle) {
        if (lastTime == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                clock = SystemClock.elapsedRealtime()
                if (clock - lastTime >= SIGNAL_WINDOW_MS) break
                delay(5_000)
            } while (true)
        }
    }
    val end = maxOf(clock, lastTime ?: clock)
    CellularTrendCard(history, end)
}

@Composable
internal fun CellularTrendCard(history: CellularSignalHistory, end: Long) {
    val samples = remember(history.samples, end) {
        history.samples.dropWhile { it.time < end - SIGNAL_WINDOW_MS }
    }
    val values = remember(samples) { samples.mapNotNull { it.dbm } }
    val latest = samples.lastOrNull()?.dbm?.takeIf { end - samples.last().time <= 30_000L }
    val colors = MaterialTheme.colorScheme
    val style = LocalInterfaceStyle.current
    val title = stringResource(R.string.cellular_trend)

    SignalSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
                    modifier = Modifier.semantics { heading() },
                    fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.cellular_trend_window, history.title),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    color = colors.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(latest?.toString() ?: SIGNAL_UNAVAILABLE,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp),
                    fontFamily = if (style == InterfaceStyle.COMPACT) FontFamily.Monospace else null,
                    fontWeight = FontWeight.SemiBold, color = colors.primary)
                Text("dBm", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = colors.onSurfaceVariant)
            }
        }

        if (values.size < 2) {
            Box(Modifier.fillMaxWidth().height(signalPlotHeight), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (values.isEmpty()) R.string.cellular_trend_waiting else R.string.cellular_trend_waiting_next),
                    Modifier.padding(style.cardPadding),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            SignalPlot(
                samples = samples,
                values = values,
                end = end,
                title = title,
                showCurrent = latest != null,
            )
        }
    }
}

@Composable
private fun SignalPlot(
    samples: List<CellularSignalSample>,
    values: List<Int>,
    end: Long,
    title: String,
    showCurrent: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant, letterSpacing = 0.sp)
    val labels = listOf(stringResource(R.string.cellular_trend_start), "−60 s", stringResource(R.string.cellular_trend_latest))
    val bottomDbm = floor((values.min() - 4) / 10f).toInt() * 10
    val topDbm = maxOf(bottomDbm + 20, ceil((values.max() + 4) / 10f).toInt() * 10)
    Canvas(
        Modifier.fillMaxWidth().height(signalPlotHeight).semantics { contentDescription = title }
            .drawWithCache {
                val axisStyle: TextStyle = textStyle.copy(fontSize = 10.sp)
                val yLabels = listOf(topDbm, (topDbm + bottomDbm) / 2, bottomDbm)
                    .map { textMeasurer.measure(it.toString(), axisStyle) }
                val xLabels = labels.map { textMeasurer.measure(it, axisStyle) }
                val left = 12.dp.toPx() + yLabels.maxOf { it.size.width } + 9.dp.toPx()
                val right = size.width - 13.dp.toPx()
                val top = 15.dp.toPx()
                val bottom = size.height - xLabels.maxOf { it.size.height } - 18.dp.toPx()
                val curve = Path()
                val area = Path()
                var runStart: Offset? = null
                var runEnd: Offset? = null

                fun closeRun() {
                    val first = runStart ?: return
                    val last = runEnd ?: return
                    area.lineTo(last.x, bottom)
                    area.lineTo(first.x, bottom)
                    area.close()
                    runStart = null
                    runEnd = null
                }

                for (sample in samples) {
                    val value = sample.dbm
                    if (value != null) {
                        val point = Offset(
                            right - (right - left) * (end - sample.time).toFloat() / SIGNAL_WINDOW_MS,
                            bottom - (bottom - top) * (value - bottomDbm).toFloat() / (topDbm - bottomDbm),
                        )
                        if (runStart == null) {
                            runStart = point
                            curve.moveTo(point.x, point.y)
                            area.moveTo(point.x, point.y)
                        } else {
                            curve.lineTo(point.x, point.y)
                            area.lineTo(point.x, point.y)
                        }
                        runEnd = point
                    }
                }
                val last = runEnd
                closeRun()
                val fill = Brush.verticalGradient(
                    listOf(colors.primary.copy(alpha = 0.20f), colors.primary.copy(alpha = 0.02f)),
                    startY = top,
                    endY = bottom.coerceAtLeast(top + 1),
                )
                onDrawBehind {
                    if (right <= left || bottom <= top) return@onDrawBehind
                    yLabels.forEachIndexed { index, label ->
                        val y = top + (bottom - top) * index / 2
                        drawLine(colors.outlineVariant.copy(alpha = 0.54f), Offset(left, y), Offset(right, y))
                        drawText(label, topLeft = Offset(left - label.size.width - 8.dp.toPx(), y - label.size.height / 2f))
                    }
                    xLabels.forEachIndexed { index, label ->
                        val x = when (index) {
                            0 -> left
                            1 -> (left + right - label.size.width) / 2f
                            else -> right - label.size.width
                        }
                        drawText(label, topLeft = Offset(x, bottom + 8.dp.toPx()))
                    }
                    drawPath(area, fill)
                    drawPath(curve, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (last != null && showCurrent) {
                        drawCircle(colors.primary.copy(alpha = 0.18f), 6.dp.toPx(), last)
                        drawCircle(colors.primary, 3.dp.toPx(), last)
                    }
                }
            },
    ) {}
}
