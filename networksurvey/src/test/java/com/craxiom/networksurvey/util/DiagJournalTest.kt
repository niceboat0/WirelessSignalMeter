package com.craxiom.networksurvey.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagJournalTest {
    @Test
    fun filtersStaleHeartbeatsWhileKeepingCurrentHeartbeatsAndCrashSignaling() {
        val current = "clock-new"
        val stale = "clock-old"
        val lines = listOf(
            """{"type":"session_start","clock_id":"$stale","t":1000}""",
            """{"type":"status","state":"running","uptime_s":1540,"clock_id":"$stale"}""",
            """{"type":"ims_provider","name":"android_telephony","clock_id":"$stale"}""",
            """{"type":"rrc","rat":"NR","msg":"rrcSetup","clock_id":"$stale"}""",
            """{"type":"nas_release","rat":"NR","psi":1,"clock_id":"$stale"}""",
            """{"type":"status","state":"running","uptime_s":10,"clock_id":"$current"}""",
            """{"type":"ims_provider","name":"android_telephony","clock_id":"$current"}""",
            """{"type":"session_start","clock_id":"$current","t":2000}""",
            """{"type":"status","state":"running","uptime_s":1}""",
            "not-json-line",
        )

        val filtered = DiagJournal.filterExportLines(lines, current)

        assertEquals(
            listOf(
                """{"type":"session_start","clock_id":"$stale","t":1000}""",
                """{"type":"rrc","rat":"NR","msg":"rrcSetup","clock_id":"$stale"}""",
                """{"type":"nas_release","rat":"NR","psi":1,"clock_id":"$stale"}""",
                """{"type":"status","state":"running","uptime_s":10,"clock_id":"$current"}""",
                """{"type":"ims_provider","name":"android_telephony","clock_id":"$current"}""",
                """{"type":"session_start","clock_id":"$current","t":2000}""",
                "not-json-line",
            ),
            filtered,
        )
    }
}
