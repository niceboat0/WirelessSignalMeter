package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.model.SurveyLocationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RecordingLocationSidecarTest {
    @Test fun liveViewAndMissingChoiceDoNotCreateALocationFile() {
        val dir = temp()
        DiagRecordingSession(dir).ensureLiveOnly()
        assertFalse(shouldOpenLocationSidecar(recordingSessionOpen = false, SurveyLocationMode.DT))
        assertFalse(shouldOpenLocationSidecar(recordingSessionOpen = true, null))
        assertTrue(shouldOpenLocationSidecar(recordingSessionOpen = true, SurveyLocationMode.DT))
        assertTrue(shouldOpenLocationSidecar(recordingSessionOpen = true, SurveyLocationMode.CQT))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test fun requestIsOneSecondAndZeroMetersRatherThanTheMapDedup() {
        assertEquals(1_000L, RECORDING_LOCATION_INTERVAL_MS)
        assertEquals(0f, RECORDING_LOCATION_MIN_DISTANCE_M)
        assertEquals(5_000L, CQT_WAIT_MS)
        assertEquals(10_000L, LOCATION_ALIGN_WINDOW_MS)
        assertEquals(100.0, LOCATION_MAX_ACCURACY_M, 0.0)
    }

    @Test fun providerFallsBackFromGpsToFusedThenNetwork() {
        assertEquals("gps", selectRecordingProvider(gpsEnabled = true, fusedEnabled = true, networkEnabled = true))
        assertEquals("fused", selectRecordingProvider(false, true, true))
        assertEquals("network", selectRecordingProvider(false, false, true))
        assertNull(selectRecordingProvider(false, false, false))
        assertEquals(listOf("gps", "fused", "network"), providerOrder(true, true, true))
    }

    @Test fun cachedCqtFixPrefersQualifyingGpsOverABetterFusedFix() {
        val gps = fix(provider = "gps", accuracyM = 40.0, ageMs = 2_000)
        val fused = fix(provider = "fused", accuracyM = 3.0, ageMs = 100)
        assertEquals("gps", selectCachedFix(listOf(fused, gps))?.provider)
        assertEquals("fused", selectCachedFix(listOf(fused, gps.copy(ageMs = 10_001)))?.provider)
        assertNull(selectCachedFix(listOf(gps.copy(accuracyM = 100.1), fused.copy(ageMs = 10_001))))
    }

    @Test fun alignmentUsesElapsedRealtimeAndClockIdNotWallTime() {
        val point = fix(elapsedRealtimeMs = 50_000, clockId = "clock-a", ageMs = 1_000, accuracyM = 20.0)
        assertTrue(point.matchesDiagnostic(55_000, "clock-a"))
        assertTrue(point.matchesDiagnostic(40_000, "clock-a"))
        assertTrue(point.copy(wallTimeMs = 1L).matchesDiagnostic(50_000, "clock-a"))
        assertFalse(point.matchesDiagnostic(50_000, "clock-b"))
        assertFalse(point.matchesDiagnostic(60_001, "clock-a"))
        assertFalse(point.copy(ageMs = 10_001).matchesDiagnostic(50_000, "clock-a"))
        assertFalse(point.copy(accuracyM = 100.1).matchesDiagnostic(50_000, "clock-a"))
        assertTrue(point.copy(ageMs = 10_000, accuracyM = 100.0).matchesDiagnostic(60_000, "clock-a"))
        assertFalse(point.copy(accuracyM = null).matchesDiagnostic(50_000, "clock-a"))
    }

    @Test fun modeChoiceIsRememberedPerTaskAcrossRestart() {
        val memory = SurveyLocationModeMemory()
        memory.remember("task-a", SurveyLocationMode.CQT)
        memory.remember("task-b", SurveyLocationMode.DT)
        val restarted = SurveyLocationModeMemory()
        restarted.restore(memory.snapshot())
        assertEquals(SurveyLocationMode.CQT, restarted.recall("task-a"))
        assertEquals(SurveyLocationMode.DT, restarted.recall("task-b"))
        assertNull(restarted.recall("task-c"))
        assertNull(SurveyLocationMode.fromPreference("drive-test"))
        assertNull(SurveyLocationMode.fromPreference(null))
    }

    @Test fun dtWritesEveryFixIncludingCloseOnesAndSplitsClockDomains() {
        val dir = temp()
        val side = RecordingLocationSidecar(dir)
        side.open("task-1", SurveyLocationMode.DT, "clock-a")
        side.accept(fix(latitude = 31.0, longitude = 121.0, elapsedRealtimeMs = 1_000, clockId = "clock-a"))
        side.accept(fix(latitude = 31.00001, longitude = 121.0, elapsedRealtimeMs = 2_000, clockId = "clock-a",
            altitudeM = 12.5, speedMps = 3.25, provider = "gps", ageMs = 400, accuracyM = 6.0, wallTimeMs = 1_700_000_000_000))
        side.accept(fix(elapsedRealtimeMs = 3_000, clockId = "clock-b"))
        side.accept(fix(taskId = "other", elapsedRealtimeMs = 4_000, clockId = "clock-b"))
        side.stopAccepting()
        side.accept(fix(elapsedRealtimeMs = 9_000, clockId = "clock-a"))
        val file = side.finish()!!
        assertEquals("task-1.gpx", file.name)
        val text = file.readText()
        assertTrue(text.contains("<mode>DT</mode>"))
        assertTrue(text.contains("<task_id>task-1</task_id>"))
        assertEquals(3, Regex("<trkpt ").findAll(text).count())
        assertEquals(2, Regex("<trkseg>").findAll(text).count())
        assertTrue(text.contains("<ele>12.5</ele>"))
        assertTrue(text.contains("<accuracy_m>6</accuracy_m>"))
        assertTrue(text.contains("<speed_mps>3.25</speed_mps>"))
        assertTrue(text.contains("<provider>gps</provider>"))
        assertTrue(text.contains("<age_ms>400</age_ms>"))
        assertTrue(text.contains("<wall_time_ms>1700000000000</wall_time_ms>"))
        assertTrue(text.contains("<elapsed_realtime_ms>2000</elapsed_realtime_ms>"))
        assertTrue(text.contains("<clock_id>clock-b</clock_id>"))
        assertTrue(text.contains("</gpx>"))
        assertFalse(File(dir, RecordingLocationSidecar.ACTIVE_GPX).exists())
        side.accept(fix(elapsedRealtimeMs = 10_000))
        assertEquals(3, Regex("<trkpt ").findAll(file.readText()).count())
    }

    @Test fun dtArchivesBesideQmdlAndQmdlListingIgnoresTheSidecar() {
        val dir = temp()
        val session = DiagRecordingSession(dir)
        session.start("task-1").writeBytes(byteArrayOf(1, 2))
        val side = RecordingLocationSidecar(dir)
        side.open("task-1", SurveyLocationMode.DT, "clock-a")
        side.accept(fix())
        val qmdl = session.finish()
        val gpx = side.finish()
        assertEquals("task-1.qmdl", qmdl?.name)
        assertEquals("task-1.gpx", gpx?.name)
        assertEquals(listOf("task-1.qmdl"), session.recordings().map { it.name })
    }

    @Test fun cqtKeepsOnePointUntilAnExplicitRemarkAndMarksACoarseFix() {
        val dir = temp()
        val side = RecordingLocationSidecar(dir)
        side.open("task-1", SurveyLocationMode.CQT, "clock-a")
        val coarse = fix(accuracyM = 180.0, ageMs = 20_000, provider = "network")
        assertTrue(side.captureCqt(coarse, coarse = false))
        repeat(5) { assertFalse(side.captureCqt(fix(elapsedRealtimeMs = 1_000L + it), coarse = false)) }
        assertTrue(side.allowRemark())
        assertTrue(side.captureCqt(fix(provider = "gps", accuracyM = 8.0, ageMs = 500), coarse = false))
        val file = side.finish()!!
        assertEquals("task-1.cqt.json", file.name)
        val text = file.readText()
        assertTrue(text.contains("\"mode\":\"CQT\""))
        assertTrue(text.contains("\"task_id\":\"task-1\""))
        assertEquals(2, Regex("\"coarse\":").findAll(text).count())
        assertTrue(text.contains("\"coarse\":true"))
        assertTrue(text.contains("\"coarse\":false"))
        assertTrue(text.contains("\"provider\":\"network\""))
        assertTrue(text.contains("\"provider\":\"gps\""))
        assertFalse(text.contains("<trkpt"))
    }

    @Test fun cqtWithoutAFixStillSavesOneCoarsePoint() {
        val side = RecordingLocationSidecar(temp())
        side.open("point", SurveyLocationMode.CQT, "clock-a")
        val file = side.finish()!!
        val text = file.readText()
        assertTrue(text.contains("\"mode\":\"CQT\""))
        assertTrue(text.contains("\"latitude\":null"))
        assertTrue(text.contains("\"coarse\":true"))
        assertEquals(1, Regex("\"coarse\":").findAll(text).count())
        assertNull(side.finish())
    }

    @Test fun headerKeepsTheRawTaskIdAndALaterSessionDoesNotReuseTheFile() {
        val dir = temp()
        val side = RecordingLocationSidecar(dir)
        side.open("a<b>&\"c", SurveyLocationMode.DT, "clock")
        side.accept(fix(taskId = "a<b>&\"c"))
        val first = side.finish()!!
        assertTrue(first.readText().contains("<task_id>a&lt;b&gt;&amp;&quot;c</task_id>"))
        assertFalse(first.readText().contains("<task_id>a<b>"))
        side.open("task-1", SurveyLocationMode.DT, "clock")
        val second = side.finish()!!
        side.open("task-1", SurveyLocationMode.CQT, "clock")
        val third = side.finish()!!
        assertEquals("task-1.gpx", second.name)
        assertEquals("task-1.cqt.json", third.name)
        side.open("task-1", SurveyLocationMode.CQT, "clock")
        assertEquals("task-1-2.cqt.json", side.finish()?.name)
    }

    @Test fun orphanActiveTrackIsSealedAwayFromTheNextClockDomain() {
        val dir = temp()
        File(dir, RecordingLocationSidecar.ACTIVE_GPX).writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx><trk><trkseg>\n" +
                "<trkpt lat=\"31\" lon=\"121\"><extensions><clock_id>old-clock</clock_id><task_id>old-task</task_id></extensions></trkpt>\n",
        )
        val side = RecordingLocationSidecar(dir)
        side.open("new-task", SurveyLocationMode.DT, "new-clock")
        side.accept(fix(taskId = "new-task", clockId = "new-clock"))
        val current = side.finish()!!
        val orphan = dir.listFiles()!!.single { it.name.startsWith("orphan") && it.name.endsWith(".gpx") }
        val orphanText = orphan.readText()
        assertTrue(orphanText.contains("old-clock"))
        assertTrue(orphanText.contains("</gpx>"))
        assertFalse(current.readText().contains("old-clock"))
        assertTrue(current.readText().contains("new-clock"))
    }

    @Test fun failedArchiveLeavesTheActiveFileForTheNextSessionToSeal() {
        val dir = temp()
        val blocked = RecordingLocationSidecar(dir) { _, _ -> false }
        blocked.open("task-1", SurveyLocationMode.DT, "clock")
        blocked.accept(fix())
        assertTrue(runCatching { blocked.finish() }.exceptionOrNull() is java.io.IOException)
        val side = RecordingLocationSidecar(dir)
        side.open("task-2", SurveyLocationMode.DT, "clock-2")
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("orphan") && it.name.endsWith(".gpx") })
        assertEquals("task-2.gpx", side.finish()?.name)
    }

    private fun fix(
        latitude: Double = 31.2,
        longitude: Double = 121.5,
        altitudeM: Double? = null,
        accuracyM: Double? = 5.0,
        speedMps: Double? = null,
        provider: String? = "gps",
        ageMs: Long = 100,
        wallTimeMs: Long = 1_700_000_000_000,
        elapsedRealtimeMs: Long = 50_000,
        clockId: String = "clock-a",
        taskId: String = "task-1",
    ) = RecordedFix(
        latitude, longitude, altitudeM, accuracyM, speedMps, provider, ageMs, wallTimeMs,
        elapsedRealtimeMs, clockId, taskId,
    )

    private fun temp(): File = createTempDirectory("location-sidecar-").toFile().also { it.deleteOnExit() }
}
