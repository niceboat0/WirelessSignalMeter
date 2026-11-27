package com.craxiom.networksurvey.util

import android.view.FrameMetrics
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object FramePerformanceDiagnostics {
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

    private fun updateMax(target: AtomicLong, value: Long) {
        if (value <= 0L) return
        target.accumulateAndGet(value) { current, candidate -> maxOf(current, candidate) }
    }
}
