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
        val qmdl = session.finish()!!
        val gpx = side.finish(qmdl.name)!!
        assertEquals("task-1.qmdl", qmdl.name)
        assertEquals("task-1.qmdl.gpx", gpx.name)
        assertEquals(listOf("task-1.qmdl"), session.recordings().map { it.name })
        assertEquals(listOf("task-1.qmdl.gpx"), locationSidecarsFor(dir, listOf(qmdl)).map { it.name })
    }

    @Test fun dlfSegmentDoesNotTakeTheQmdlSidecar() {
        val dir = temp()
        val session = DiagRecordingSession(dir)
        session.start("task-1").writeBytes(byteArrayOf(1))
        val drive = RecordingLocationSidecar(dir)
        drive.open("task-1", SurveyLocationMode.DT, "clock-a")
        drive.accept(fix())
        val qmdl = session.finish()!!
        drive.finish(qmdl.name)
        session.start("task-1", DiagLogFormat.Dlf).writeBytes(byteArrayOf(2))
        val point = RecordingLocationSidecar(dir)
        point.open("task-1", SurveyLocationMode.CQT, "clock-a", CqtFloorPlan(CqtFloorKind.IMAGE, 20, 10, "plan"), byteArrayOf(4, 5))
        assertTrue(point.mark(tap(x = 1.0, y = 2.0, width = 20, height = 10)))
        val dlf = session.finish()!!
        val json = point.finish(dlf.name)!!
        assertEquals("task-1.dlf", dlf.name)
        assertEquals("task-1.dlf.cqt.json", json.name)
        assertTrue(File(dir, "task-1.dlf.cqt-plan").readBytes().contentEquals(byteArrayOf(4, 5)))
        assertEquals(listOf("task-1.qmdl.gpx"), locationSidecarsFor(dir, listOf(qmdl)).map { it.name })
        assertEquals(
            setOf("task-1.dlf.cqt.json", "task-1.dlf.cqt-plan"),
            locationSidecarsFor(dir, listOf(dlf)).map { it.name }.toSet(),
        )
        val recorded = json.readText()
        assertTrue(recorded.contains("\"file\":\"task-1.dlf.cqt-plan\""))
        assertFalse(recorded.contains("active.cqt-plan"))
        assertFalse(recorded.contains("task-1.qmdl"))
    }

    @Test fun retargetFloorPlanFileReplacesOnlyThePlanName() {
        val raw = """{"mode":"CQT","floor_plan":{"kind":"image","file":"active.cqt-plan"},"points":[{"x_px":1}]}"""
        val updated = retargetFloorPlanFile(raw, "task.qmdl.cqt-plan")
        assertTrue(updated.contains("\"file\":\"task.qmdl.cqt-plan\""))
        assertFalse(updated.contains("active.cqt-plan"))
        assertTrue(updated.contains("\"x_px\":1"))
        assertEquals("""{"task_id":"keep-me"}""", retargetFloorPlanFile("""{"task_id":"keep-me"}""", "task.qmdl.cqt-plan"))
        assertTrue(retargetFloorPlanFile(raw, null).contains("\"file\":null"))
    }

    @Test fun cqtStoresFloorTapsAndUndoWithoutSplittingTheAirFile() {
        val dir = temp()
        val session = DiagRecordingSession(dir)
        val air = session.start("task-1")
        air.writeBytes(byteArrayOf(1, 2, 3))
        val side = RecordingLocationSidecar(dir)
        side.open("task-1", SurveyLocationMode.CQT, "clock-a")
        assertTrue(side.mark(tap(x = 12.5, y = 40.0)))
        assertTrue(side.mark(tap(x = 80.0, y = 90.0, elapsed = 60_000)))
        assertFalse(side.mark(tap(task = "other")))
        assertFalse(side.mark(tap(x = -1.0)))
        assertTrue(side.undo())
        assertFalse(side.replaceFloor(CqtFloorPlan.blank(), null))
        assertEquals(3L, air.length())
        assertTrue(session.isOpen())
        assertEquals(listOf("active.qmdl"), dir.listFiles()!!.filter { it.name.endsWith(".qmdl") }.map { it.name })
        val file = side.finish()!!
        assertEquals("task-1.qmdl", session.finish()?.name)
        val text = file.readText()
        assertTrue(text.contains("\"kind\":\"blank\""))
        assertTrue(text.contains("\"x_px\":12.5"))
        assertTrue(text.contains("\"y_px\":40"))
        assertEquals(1, Regex("\"x_px\"").findAll(text).count())
        assertFalse(text.contains("\"latitude\""))
        assertTrue(text.contains("\"elapsed_realtime_ms\":50000"))
        assertTrue(text.contains("\"clock_id\":\"clock-a\""))
        assertFalse(text.contains("<trkpt"))
    }

    @Test fun cqtGnssIsMetadataBesideTheFloorCoordinate() {
        val side = RecordingLocationSidecar(temp())
        side.open("task-1", SurveyLocationMode.CQT, "clock-a")
        assertTrue(side.mark(tap(x = 12.5, y = 40.0, gnss = fix())))
        val text = side.finish()!!.readText()
        assertTrue(text.contains("\"x_px\":12.5"))
        assertTrue(text.contains("\"y_px\":40"))
        assertTrue(text.contains("\"gnss\":{\"latitude\":31.2"))
        assertTrue(text.contains("\"coarse\":false"))
        val point = tap()
        assertTrue(point.matchesDiagnostic(55_000, "clock-a"))
        assertFalse(point.matchesDiagnostic(55_000, "clock-b"))
        assertFalse(point.matchesDiagnostic(70_001, "clock-a"))
    }

    @Test fun cqtWithoutTapsSavesTheFloorPlanAndNoSyntheticPoint() {
        val side = RecordingLocationSidecar(temp())
        side.open("point", SurveyLocationMode.CQT, "clock-a")
        assertFalse(side.undo())
        val text = side.finish()!!.readText()
        assertTrue(text.contains("\"mode\":\"CQT\""))
        assertTrue(text.contains("\"points\":[]"))
        assertTrue(text.contains("\"kind\":\"blank\""))
        assertFalse(text.contains("\"x_px\""))
        assertFalse(text.contains("\"latitude\""))
        assertNull(side.finish())
    }

    @Test fun cqtArchivesTheFloorImageBesideTheSameTask() {
        val dir = temp()
        val plan = CqtFloorPlan(CqtFloorKind.IMAGE, 640, 480, "level.png")
        val side = RecordingLocationSidecar(dir)
        side.open("task-1", SurveyLocationMode.CQT, "clock", plan, byteArrayOf(9, 8, 7))
        assertTrue(side.mark(tap(x = 1.0, y = 2.0, width = 640, height = 480, clock = "clock")))
        val json = side.finish()!!
        assertEquals("task-1.cqt.json", json.name)
        assertTrue(json.readText().contains("\"file\":\"task-1.cqt-plan\""))
        assertTrue(json.readText().contains("\"width_px\":640"))
        assertTrue(File(dir, "task-1.cqt-plan").readBytes().contentEquals(byteArrayOf(9, 8, 7)))
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

    private fun tap(
        x: Double = 10.0,
        y: Double = 20.0,
        width: Int = CQT_BLANK_WIDTH_PX,
        height: Int = CQT_BLANK_HEIGHT_PX,
        elapsed: Long = 50_000,
        clock: String = "clock-a",
        task: String = "task-1",
        gnss: RecordedFix? = null,
    ) = CqtCanvasPoint(x, y, width, height, elapsed, 1_700_000_000_000, clock, task, gnss)

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
