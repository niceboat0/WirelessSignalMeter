package com.craxiom.networksurvey.ui.rootdiag

import java.util.Locale

/** PHY rate and the scheduling fields shown beside it share this window. */
internal const val THROUGHPUT_FRESH_MS = 3_000L

/**
 * A missing or expired rate is unavailable. A decoded zero, including a layer
 * counter that did not move, stays "0.00".
 */
internal fun Double?.mbpsText(): String =
    if (this == null) SIGNAL_UNAVAILABLE else String.format(Locale.ROOT, "%.2f", this)

/**
 * Downlink PHY prefers the cumulative PDSCH counters. They still produce a
 * window after some frames are dropped. The per-TB rate is the fallback.
 * A real zero is not treated as missing.
 */
internal fun nrDlPhyMbps(cumulativeTb: Double?, scheduledTb: Double?): Double? = cumulativeTb ?: scheduledTb

/** One fresh sample per carrier. Older observations are not mixed into the selected carrier. */
internal fun scheduleByCarrier(
    samples: List<DiagRecord>,
    group: String,
    now: Long,
    freshMs: Long = THROUGHPUT_FRESH_MS,
): Map<Int, DiagRecord> = samples.filter { record ->
    record.data.optString("group") == group && now - record.receivedAt in 0..freshMs
}.associateBy { it.data.optInt("cc", -1) }
