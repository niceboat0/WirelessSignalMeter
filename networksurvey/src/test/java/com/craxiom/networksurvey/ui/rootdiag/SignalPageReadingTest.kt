package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignalPageReadingTest {
    private fun chain(source: String, pci: Int, channel: Int, rsrp: Double, at: Long, id: Long = at) =
        NeighborReading("LTE", channel, pci, NeighborMetric("%.1f".format(rsrp), rsrp), null, null,
            source, receivedAt = at, recordId = id)

    @Test fun newerB193ReplacesOlderB179() {
        val rows = listOf(
            chain("DIAG B179", 10, 1750, -110.0, 1_000),
            chain("DIAG B193", 10, 1750, -90.0, 5_000),
        )
        val merged = mergeNeighborReadings(rows)
        assertEquals(-90.0, merged.rsrp!!.value, 0.01)
        assertEquals("DIAG B193", merged.source)
    }

    @Test fun tiedB179StillWins() {
        val rows = listOf(
            chain("DIAG B193", 10, 1750, -90.0, 5_000, id = 2),
            chain("DIAG B179", 10, 1750, -91.0, 5_000, id = 1),
        )
        assertEquals(-91.0, mergeNeighborReadings(rows).rsrp!!.value, 0.01)
    }

    @Test fun freshTableKeepsOnlyTheCurrentSample() {
        val snap = neighborSnapshot(listOf(
            chain("DIAG B179", 10, 1750, -110.0, 1_000),
            chain("DIAG B193", 10, 1750, -90.0, 20_000),
        ), now = 21_000)
        assertEquals(NeighborFreshness.CURRENT, snap.freshness)
        assertEquals(-90.0, snap.rows.single().rsrp!!.value, 0.01)
    }

    @Test fun expiredReadingsAreNotAnEmptyTable() {
        val snap = neighborSnapshot(listOf(chain("DIAG B179", 10, 1750, -110.0, 1_000)), now = 20_000)
        assertEquals(NeighborFreshness.EXPIRED, snap.freshness)
        assertTrue(snap.rows.isEmpty())
    }

    @Test fun noReadingsStayEmpty() {
        val snap = neighborSnapshot(emptyList(), now = 20_000)
        assertEquals(NeighborFreshness.EMPTY, snap.freshness)
        assertTrue(snap.rows.isEmpty())
    }

    @Test fun cellChangeDropsThePreviousSnapshotWithoutErasingTheNextReport() {
        val previous = chain("DIAG B179", 10, 1750, -110.0, 1_000)
        val next = chain("DIAG B193", 12, 1550, -95.0, 8_000)
        val waiting = neighborSnapshot(listOf(previous), now = 8_100, cellChangedAt = 7_000)
        assertEquals(NeighborFreshness.CELL_CHANGED, waiting.freshness)
        assertTrue(waiting.rows.isEmpty())
        val resumed = neighborSnapshot(listOf(previous, next), now = 8_100, cellChangedAt = 7_000)
        assertEquals(NeighborFreshness.CURRENT, resumed.freshness)
        assertEquals(12, resumed.rows.single().pci)
        assertEquals(-95.0, resumed.rows.single().rsrp!!.value, 0.01)
    }

    @Test fun restartOmitsCachedRecordsAndKeepsTheNewReport() {
        val cached = chain("DIAG B179", 10, 1750, -110.0, 1_000, id = 4)
        val fresh = chain("DIAG B193", 10, 1750, -88.0, 40_000, id = 9)
        val visible = omitCachedRecords(listOf(cached, fresh), setOf(4L)) { it.recordId }
        val snap = neighborSnapshot(visible, now = 41_000)
        assertEquals(NeighborFreshness.CURRENT, snap.freshness)
        assertEquals(-88.0, snap.rows.single().rsrp!!.value, 0.01)
    }

    @Test fun antennaMaskKeepsNonContiguousChainsOnTheSameSample() {
        val sample = JSONObject()
            .put("sinr", 12.5)
            .put("sinr_agg", "max_rx")
            .put("sinr_src", "B193")
            .put("sinr_rx0", -20.0)
            .put("sinr_rx2", 12.5)
        val chains = antennaSinr(sample)
        assertEquals(listOf(0, 2), chains.map { it.index })
        assertTrue(maxRxMatchesChains(sample.optDouble("sinr"), chains))
        assertEquals("SINR Rx2", antennaSinrText(chains[1]).first)
        assertEquals("12.5 dB", antennaSinrText(chains[1]).second)
    }

    @Test fun maskWithoutRx0DoesNotInventRx0() {
        val chains = antennaSinr(JSONObject().put("sinr_rx1", 1.0).put("sinr_rx3", 4.0))
        assertEquals(listOf(1, 3), chains.map { it.index })
        assertTrue(maxRxMatchesChains(4.0, chains))
        assertFalse(maxRxMatchesChains(1.0, chains))
    }

    @Test fun bandwidthKeepsAgreedMhzAndShowsDisagreement() {
        assertEquals("40 MHz", bandwidthMhzText(listOf(40.0, 40.0)))
        assertEquals("1.4 MHz", bandwidthMhzText(listOf(1.4, null)))
        assertEquals("40 MHz · 100 MHz", bandwidthMhzText(listOf(40.0, 100.0)))
        assertNull(bandwidthMhzText(listOf(0.0, 5000.0, null)))
    }
}
