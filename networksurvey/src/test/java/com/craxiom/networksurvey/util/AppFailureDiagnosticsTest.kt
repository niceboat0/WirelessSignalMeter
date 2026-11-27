package com.craxiom.networksurvey.util

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppFailureDiagnosticsTest {
    @Test
    fun crashExportKeepsOnlyBoundedClassesAndFrames() {
        val nested = IllegalArgumentException("private nested message")
        val failure = IllegalStateException("private outer message", nested).apply {
            stackTrace = Array(20) { StackTraceElement("example.Signal", "render", "Signal.kt", it) }
        }
        val diagnostic = AppLogger.crashDiagnostic(failure, mainThread = true)
        val causes = diagnostic.getJSONArray("causes")

        assertEquals("app_crash", diagnostic.getString("type"))
        assertEquals("main", diagnostic.getString("thread"))
        assertEquals(2, causes.length())
        assertEquals(IllegalStateException::class.java.name, causes.getJSONObject(0).getString("class"))
        assertEquals(IllegalArgumentException::class.java.name, causes.getJSONObject(1).getString("class"))
        assertEquals(12, causes.getJSONObject(0).getJSONArray("frames").length())
        assertFalse(diagnostic.toString().contains("private"))
    }
}
