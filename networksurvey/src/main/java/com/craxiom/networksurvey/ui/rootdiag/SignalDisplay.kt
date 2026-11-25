package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal data class AntennaSinr(val index: Int, val db: Double)

/** Chains actually present in this sample. Missing keys are antennas the mask did not report. */
internal fun antennaSinr(data: JSONObject?): List<AntennaSinr> {
    if (data == null) return emptyList()
    return (0..3).mapNotNull { rx ->
        val key = "sinr_rx$rx"
        if (!data.has(key) || data.isNull(key)) return@mapNotNull null
        val value = data.optDouble(key)
        if (!value.isFinite()) return@mapNotNull null
        AntennaSinr(rx, value)
    }
}

internal fun antennaSinrText(chain: AntennaSinr): Pair<String, String> =
    "SINR Rx${chain.index}" to String.format(Locale.ROOT, "%.1f dB", chain.db)

/** True when the headline is the max of the chains carried beside it. */
internal fun maxRxMatchesChains(combined: Double?, chains: List<AntennaSinr>): Boolean {
    if (combined == null || !combined.isFinite() || chains.isEmpty()) return false
    return abs(combined - chains.maxOf { it.db }) < 0.051
}

/**
 * Bandwidths already decoded as MHz. Values outside 1.4..400 are not shown.
 * Distinct sources that disagree are both shown; equal values stay one number.
 * This does not reinterpret a raw field as an enum or a PRB count.
 */
internal fun bandwidthMhzText(candidates: List<Double?>): String? {
    val usable = ArrayList<Double>()
    for (value in candidates) {
        if (value == null || !value.isFinite() || value !in 1.4..400.0) continue
        val key = round(value * 10.0).toInt()
        if (usable.none { round(it * 10.0).toInt() == key }) usable += value
    }
    if (usable.isEmpty()) return null
    fun format(value: Double) = if (abs(value - value.toInt()) < 0.05) "${value.toInt()} MHz"
        else String.format(Locale.ROOT, "%.1f MHz", value)
    return usable.joinToString(" · ", transform = ::format)
}
