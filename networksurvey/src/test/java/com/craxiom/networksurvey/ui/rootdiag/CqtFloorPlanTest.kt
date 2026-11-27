package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.db.existingTaskForMapTracking
import com.craxiom.networksurvey.db.missionTaskToBind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CqtFloorPlanTest {
    @Test fun tapMappingUsesTheFittedSheetAndIgnoresTheLetterbox() {
        val inside = mapTapToCanvas(100f, 50f, 200f, 100f, 100, 100)
        assertEquals(50.0, inside!!.first, 0.001)
        assertEquals(50.0, inside.second, 0.001)
        assertEquals(0.0, mapTapToCanvas(50f, 0f, 200f, 100f, 100, 100)!!.first, 0.001)
        assertEquals(100.0, mapTapToCanvas(150f, 100f, 200f, 100f, 100, 100)!!.first, 0.001)
        assertNull(mapTapToCanvas(10f, 50f, 200f, 100f, 100, 100))
        assertNull(mapTapToCanvas(1f, 1f, 0f, 10f, 100, 100))
    }

    @Test fun blankSheetIsTheDefaultAndAnImportedImageRoundTrips() {
        val library = CqtFloorPlanLibrary(temp())
        assertEquals(CqtFloorKind.BLANK, library.recall("task").plan.kind)
        assertNull(library.recall("task").image)
        val plan = library.saveImage("task", "rooms/floor.png", 30, 40, byteArrayOf(1, 2, 3))
        assertEquals("floor.png", plan.sourceName)
        assertEquals(CqtFloorKind.IMAGE, plan.kind)
        val stored = library.recall("task")
        assertEquals(30, stored.plan.widthPx)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.image)
        library.saveBlank("task")
        assertEquals(CqtFloorKind.BLANK, library.recall("task").plan.kind)
        assertNull(library.recall("task").image)
    }

    @Test fun imageLimitsAndSourceNameAreChecked() {
        val library = CqtFloorPlanLibrary(temp())
        assertTrue(runCatching { library.saveImage("task", "a.png", 0, 10, byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { library.saveImage("task", "a.png", 10, 10, ByteArray(0)) }.isFailure)
        assertEquals("plan.png", floorSourceName("C:\\maps\\plan.png"))
        assertNull(floorSourceName("   "))
        assertEquals(CqtFloorKind.BLANK, CqtFloorPlan.blank().kind)
        assertEquals(CQT_BLANK_WIDTH_PX, CqtFloorPlan.blank().widthPx)
    }

    @Test fun missionAndMapTrackingDoNotInventATask() {
        assertNull(missionTaskToBind(null, "mission", true))
        assertNull(missionTaskToBind("task", "mission", false))
        assertNull(missionTaskToBind("task", " ", true))
        assertEquals("task", missionTaskToBind("task", "mission", true))
        assertNull(existingTaskForMapTracking(null))
        assertNull(existingTaskForMapTracking("  "))
        assertEquals("task", existingTaskForMapTracking(" task "))
        assertFalse(shouldRetainLocationSidecar(sessionOpen = true, airBytes = 0))
        assertFalse(shouldRetainLocationSidecar(sessionOpen = false, airBytes = 20))
        assertTrue(shouldRetainLocationSidecar(sessionOpen = true, airBytes = 20))
    }

    private fun temp(): File = createTempDirectory("cqt-floor-").toFile().also { it.deleteOnExit() }
}
