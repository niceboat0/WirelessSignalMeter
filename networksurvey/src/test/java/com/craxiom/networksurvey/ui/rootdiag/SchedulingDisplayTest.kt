package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SchedulingDisplayTest {
    @Test fun phyFreshnessMatchesTheThreeSecondRateWindow() {
        assertEquals(3_000L, THROUGHPUT_FRESH_MS)
        val now = 10_000L
        val edge = csf("nr_csf", 0, 8.0, 1, now - THROUGHPUT_FRESH_MS)
        val late = csf("nr_csf", 0, 8.0, 1, now - THROUGHPUT_FRESH_MS - 1)
        assertEquals(8.0, scheduleByCarrier(listOf(edge), "nr_csf", now).getValue(0).data.getDouble("cqi_avg"), 0.0)
        assertNull(scheduleByCarrier(listOf(late), "nr_csf", now)[0])
    }

    @Test fun missingPhyIsUnavailableAndADecodedZeroStaysZero() {
        assertEquals("-", null.mbpsText())
        assertEquals("0.00", 0.0.mbpsText())
        assertEquals("0.00", 0.004.mbpsText())
        assertEquals("3.56", 3.56.mbpsText())
    }

    @Test fun downlinkPhyPrefersTheCumulativeCounterIncludingZero() {
        assertEquals(0.19, nrDlPhyMbps(0.19, 3.56)!!, 0.0001)
        assertEquals(0.0, nrDlPhyMbps(0.0, 3.56)!!, 0.0)
        assertEquals(3.56, nrDlPhyMbps(null, 3.56)!!, 0.0001)
        assertNull(nrDlPhyMbps(null, null))
        assertEquals("-", nrDlPhyMbps(null, null).mbpsText())
        assertEquals("0.00", nrDlPhyMbps(0.0, null).mbpsText())
    }

    @Test fun nrCqiAndRankStayOnTheirOwnCarrier() {
        val now = 50_000L
        val samples = listOf(
            csf("nr_csf", 0, 9.67, 2, now - 200),
            csf("nr_csf", 1, 12.08, 4, now - 200),
            csf("nr_csf", 1, 10.88, 2, now - 18_000),
        )
        val fresh = scheduleByCarrier(samples, "nr_csf", now)
        assertEquals(9.67, fresh[0]!!.data.getDouble("cqi_avg"), 0.001)
        assertEquals(2, fresh[0]!!.data.getInt("rank"))
        assertEquals(12.08, fresh[1]!!.data.getDouble("cqi_avg"), 0.001)
        assertEquals(4, fresh[1]!!.data.getInt("rank"))
        assertNull(scheduleByCarrier(listOf(samples[2]), "nr_csf", now)[1])
    }

    @Test fun lteCqiStaysOnItsOwnCarrier() {
        val now = 20_000L
        val samples = listOf(
            csf("lte_csf", 0, 11.0, 1, now - 400),
            csf("lte_csf", 1, 7.0, 2, now - 400),
        )
        val fresh = scheduleByCarrier(samples, "lte_csf", now)
        assertEquals(11.0, fresh[0]!!.data.getDouble("cqi_avg"), 0.0)
        assertEquals(7.0, fresh[1]!!.data.getDouble("cqi_avg"), 0.0)
        assertNull(scheduleByCarrier(samples, "nr_csf", now)[0])
    }

    @Test fun idleSchedulingFieldsExpireWithThePhyWindow() {
        val now = 80_000L
        val staleMcs = sched("nr_dl_sched", 0, now - 18_000, "mcs_avg", 16.0, "phy_tb_mbps", 1.25)
        val freshZero = sched("nr_dl_sched", 0, now - 500, "mcs_avg", 4.0, "phy_tb_mbps", 0.0)
        val staleCqi = csf("nr_csf", 1, 11.12, 2, now - 18_000)
        assertNull(scheduleByCarrier(listOf(staleMcs, staleCqi), "nr_dl_sched", now)[0])
        assertNull(scheduleByCarrier(listOf(staleMcs, staleCqi), "nr_csf", now)[1])
        val live = scheduleByCarrier(listOf(staleMcs, freshZero), "nr_dl_sched", now)
        assertEquals(4.0, live[0]!!.data.getDouble("mcs_avg"), 0.0)
        assertEquals("0.00", live[0]!!.data.getDouble("phy_tb_mbps").mbpsText())
        assertEquals("-", null.mbpsText())
    }

    private fun csf(group: String, cc: Int, cqi: Double, rank: Int, at: Long) = DiagRecord(
        id = at,
        receivedAt = at,
        data = JSONObject().put("type", "schedule_sample").put("group", group)
            .put("cc", cc).put("cqi_avg", cqi).put("rank", rank),
    )

    private fun sched(group: String, cc: Int, at: Long, vararg numbers: Any) = DiagRecord(
        id = at,
        receivedAt = at,
        data = JSONObject().put("type", "schedule_sample").put("group", group).put("cc", cc).apply {
            var i = 0
            while (i < numbers.size) {
                put(numbers[i] as String, numbers[i + 1] as Double)
                i += 2
            }
        },
    )
}
