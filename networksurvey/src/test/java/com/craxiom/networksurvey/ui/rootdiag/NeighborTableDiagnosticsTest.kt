package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NeighborTableDiagnosticsTest {
    @Test
    fun distinguishesExpiredReadingsFromMissingReadingsAfterEviction() {
        val state = RootSignalState(lastNeighborMeasurementAt = mapOf("NR" to 1_000L))
        val tables = neighborTableDiagnostics(state, now = 40_000L).associateBy { it.getString("rat") }
        val nr = tables.getValue("NR")
        assertEquals(0, nr.getInt("rows"))
        assertEquals(39_000L, nr.getLong("newest_age_ms"))
        assertTrue(nr.getBoolean("stale"))

        val lte = tables.getValue("LTE")
        assertEquals(0, lte.getInt("rows"))
        assertEquals(-1L, lte.getLong("newest_age_ms"))
        assertFalse(lte.getBoolean("stale"))
    }

    @Test
    fun reportsNotStaleAtTheRetentionBoundary() {
        val state = RootSignalState(lastNeighborMeasurementAt = mapOf("LTE" to 1_000L))
        val lte = neighborTableDiagnostics(state, now = 31_000L).single { it.getString("rat") == "LTE" }
        assertEquals(30_000L, lte.getLong("newest_age_ms"))
        assertFalse(lte.getBoolean("stale"))
    }
}
