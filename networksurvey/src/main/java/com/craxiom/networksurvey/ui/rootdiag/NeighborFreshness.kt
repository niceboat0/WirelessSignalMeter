package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONObject

internal data class NeighborReading(
    val rat: String,
    val channel: Int,
    val pci: Int,
    val rsrp: NeighborMetric?,
    val rsrq: NeighborMetric?,
    val sinr: NeighborMetric?,
    val source: String,
    val reference: String? = null,
    val sinrOnly: Boolean = false,
    val beam: Int? = null,
    /** Receipt time. Zero means a live untimed source such as the current Android scan. */
    val receivedAt: Long = 0L,
    val recordId: Long = 0L,
)

internal data class NeighborMetric(
    val text: String,
    val value: Double,
    val beamReference: String? = null,
    val otherReference: String? = null,
    val source: String? = null,
)

internal data class NeighborKey(val rat: String, val pci: Int, val channel: Int)

internal val NeighborReading.matchKey: NeighborKey
    get() = NeighborKey(rat, pci, channel)

internal fun NeighborReading.isServingNeighbor(serving: JSONObject?): Boolean {
    val servingPci = serving?.optInt("pci", -1) ?: -1
    val servingChannel = serving?.optInt("arfcn", -1) ?: -1
    return servingPci >= 0 && servingChannel >= 0 && pci == servingPci && channel == servingChannel
}

/**
 * Newest RSRP wins across DIAG sources. B179 wins only when its receipt time
 * ties the newest report. Android readings use receivedAt 0 and fill gaps
 * after a timed DIAG sample, without covering a newer DIAG RSRP.
 */
internal fun mergeNeighborReadings(readings: List<NeighborReading>): NeighborReading {
    val ranked = readings.sortedWith(
        compareByDescending<NeighborReading> { it.receivedAt > 0L }
            .thenByDescending { it.receivedAt }
            .thenByDescending { if (it.source == "DIAG B179" && it.rsrp != null) 1 else 0 }
            .thenByDescending { it.recordId },
    )
    val primary = ranked.firstOrNull { !it.sinrOnly && it.rsrp != null }
        ?: ranked.firstOrNull { !it.sinrOnly }
        ?: ranked.first()
    val isCsi = primary.reference == NrMeasurementReference.CsiRs.label
    val compatible = readings.filter {
        val sameReference = (it.reference == NrMeasurementReference.CsiRs.label) == isCsi
        val notOlder = it.receivedAt == 0L || primary.receivedAt == 0L || it.receivedAt >= primary.receivedAt
        sameReference && notOlder
    }
    val sources = linkedSetOf(primary.source)
    fun measured(metric: (NeighborReading) -> NeighborMetric?): NeighborMetric? =
        metric(primary)?.copy(source = primary.source) ?: compatible.firstOrNull { metric(it) != null }?.let {
            sources += it.source
            metric(it)?.copy(source = it.source)
        }

    val rsrp = measured { it.rsrp }
    val rsrq = measured { it.rsrq }
    val sinr = measured { it.sinr?.takeIf { metric -> metric.beamReference == null } }
        ?: measured { it.sinr }
        ?: readings.firstOrNull {
            it.reference != null && it.sinr != null &&
                (it.receivedAt == 0L || primary.receivedAt == 0L || it.receivedAt >= primary.receivedAt)
        }?.let {
            sources += it.source
            it.sinr?.copy(otherReference = it.reference, source = it.source)
        }
    sources.remove(sinr?.source)
    return primary.copy(rsrp = rsrp, rsrq = rsrq, sinr = sinr, source = sources.joinToString(" · "))
}

internal enum class NeighborFreshness { CURRENT, EXPIRED, EMPTY, CELL_CHANGED }

internal data class NeighborSnapshot(
    val rows: List<NeighborReading>,
    val freshness: NeighborFreshness,
)

/** Same hold as the serving-measurement heartbeat. Older retained rows are not current. */
internal const val NEIGHBOR_FRESH_MS = 15_000L

/**
 * @param cellChangedAt receipt time of the serving identity now on screen.
 * Readings older than that belong to the previous cell and stay out of the table.
 * Zero means this serving identity has not replaced another one in this view.
 */
internal fun neighborSnapshot(
    readings: List<NeighborReading>,
    now: Long,
    cellChangedAt: Long = 0L,
    freshMs: Long = NEIGHBOR_FRESH_MS,
): NeighborSnapshot {
    val diag = readings.filter { it.receivedAt > 0L }
    val live = readings.filter { it.receivedAt == 0L }
    val inContext = if (cellChangedAt > 0L) diag.filter { it.receivedAt >= cellChangedAt } else diag
    val fresh = inContext.filter { now - it.receivedAt in 0..freshMs }
    val mergedFresh = fresh.groupBy { it.matchKey }.values.map(::mergeNeighborReadings)
    val freshness = when {
        fresh.isNotEmpty() -> NeighborFreshness.CURRENT
        inContext.isNotEmpty() -> NeighborFreshness.EXPIRED
        diag.isNotEmpty() -> NeighborFreshness.CELL_CHANGED
        else -> NeighborFreshness.EMPTY
    }
    val rows = (mergedFresh + live).groupBy { it.matchKey }.values.map(::mergeNeighborReadings)
    val shown = if (rows.isNotEmpty()) NeighborFreshness.CURRENT else freshness
    return NeighborSnapshot(rows, shown)
}

internal fun <T> omitCachedRecords(records: List<T>, cachedIds: Set<Long>, id: (T) -> Long): List<T> =
    if (cachedIds.isEmpty()) records else records.filter { id(it) !in cachedIds }
