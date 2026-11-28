package com.craxiom.networksurvey.util

import android.view.FrameMetrics
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Window frame-time accounting used by the diagnostic export.
 *
 * Besides the global averages this keeps a per-screen attribution ("signal",
 * "band_lock", "signaling", ...) so a jank report can name the screen that
 * missed its deadline instead of only reporting one aggregate number. A screen
 * tags itself with [tag] while it is the visible one; frames are then counted
 * against that tag.
 */
object FramePerformanceDiagnostics {
    /** A frame slower than this is reported as a visible hitch. */
    private const val HITCH_NS = 24_000_000L

    private val frames = AtomicLong()
    private val callbackDrops = AtomicLong()
    private val deadlineSamples = AtomicLong()
    private val deadlineMisses = AtomicLong()
    private val totalNs = AtomicLong()
    private val maxTotalNs = AtomicLong()
    private val maxLayoutNs = AtomicLong()
    private val maxDrawNs = AtomicLong()
    private val maxSyncNs = AtomicLong()
    private val maxGpuNs = AtomicLong()

    private val framesByScreen = ConcurrentHashMap<String, AtomicLong>()
    private val hitchesByScreen = ConcurrentHashMap<String, AtomicLong>()
    private val worstNs = AtomicLong()
    private val worstScreen = AtomicReference("")

    @Volatile
    private var screen = "app"

    /** Name the screen that is currently visible; pass "" when it goes away. */
    fun tag(name: String) {
        screen = name.ifBlank { "app" }
    }

    fun record(metrics: FrameMetrics, droppedSinceLastCallback: Int = 0) {
        if (droppedSinceLastCallback > 0) callbackDrops.addAndGet(droppedSinceLastCallback.toLong())
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (total <= 0L) return
        frames.incrementAndGet()
        totalNs.addAndGet(total)
        updateMax(maxTotalNs, total)
        updateMax(maxLayoutNs, metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
        updateMax(maxDrawNs, metrics.getMetric(FrameMetrics.DRAW_DURATION))
        updateMax(maxSyncNs, metrics.getMetric(FrameMetrics.SYNC_DURATION))
        updateMax(maxGpuNs, metrics.getMetric(FrameMetrics.GPU_DURATION))

        val at = screen
        framesByScreen.getOrPut(at) { AtomicLong() }.incrementAndGet()
        if (total > HITCH_NS) hitchesByScreen.getOrPut(at) { AtomicLong() }.incrementAndGet()
        if (total > worstNs.get()) {
            worstNs.set(total)
            worstScreen.set(at)
        }

        val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
        if (deadline > 0L) {
            deadlineSamples.incrementAndGet()
            if (total > deadline) deadlineMisses.incrementAndGet()
        }
    }

    fun snapshot() = JSONObject()
        .put("type", "frame_performance")
        .put("samples", frames.get())
        .put("callback_drops", callbackDrops.get())
        .put("deadline_samples", deadlineSamples.get())
        .put("deadline_misses", deadlineMisses.get())
        .put("total_avg_ms", totalNs.get().toDouble() / frames.get().coerceAtLeast(1L) / 1_000_000.0)
        .put("total_max_ms", maxTotalNs.get().toDouble() / 1_000_000.0)
        .put("layout_max_ms", maxLayoutNs.get().toDouble() / 1_000_000.0)
        .put("draw_max_ms", maxDrawNs.get().toDouble() / 1_000_000.0)
        .put("sync_max_ms", maxSyncNs.get().toDouble() / 1_000_000.0)
        .put("gpu_max_ms", maxGpuNs.get().toDouble() / 1_000_000.0)
        .put("total_ns", totalNs.get())
        .put("total_max_ns", maxTotalNs.get())
        .put("layout_max_ns", maxLayoutNs.get())
        .put("draw_max_ns", maxDrawNs.get())
        .put("sync_max_ns", maxSyncNs.get())
        .put("gpu_max_ns", maxGpuNs.get())
        .put("screen", screen)
        .put("worst_screen", worstScreen.get())
        .put("worst_ms", worstNs.get().toDouble() / 1_000_000.0)
        .put("hitches_by_screen", counters(hitchesByScreen))
        .put("frames_by_screen", counters(framesByScreen))

    private fun counters(source: Map<String, AtomicLong>) = JSONObject().apply {
        source.entries.sortedByDescending { it.value.get() }.forEach { (name, value) ->
            put(name, value.get())
        }
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        if (value <= 0L) return
        target.accumulateAndGet(value) { current, candidate -> maxOf(current, candidate) }
    }
}
