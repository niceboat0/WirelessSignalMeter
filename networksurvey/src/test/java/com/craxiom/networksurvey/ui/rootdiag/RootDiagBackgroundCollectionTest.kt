package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.craxiom.networksurvey.services.RootDiagCollectorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootDiagBackgroundCollectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var app: Application

    @Before
    fun setUp() {
        // Queue capture jobs so these lifecycle checks never launch a native helper.
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        RootDiagPrefs.store(app).edit().clear().putBoolean("background_collection", false).commit()
        RootDiagCollectorService.stop(app)
        RootDiagCollectorService.stopRequested.value = false
    }

    @After
    fun tearDown() {
        models.clear()
        RootDiagCollectorService.stop(app)
        RootDiagCollectorService.stopRequested.value = false
        RootDiagPrefs.store(app).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun model() = RootDiagViewModel(app).also { models.put("diag", it) }

    @Test
    fun legacyDisabledPreferenceDoesNotStopLiveListeningWhenActivityLeaves() {
        val model = model()
        model.enterForeground()
        assertTrue(RootDiagCollectorService.isMaintained)

        model.leaveForeground()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertTrue(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
        assertFalse(model.qmdl.value.hasRecording)
    }

    @Test
    fun manualPauseSurvivesActivityAndOverlayPreferenceChangesUntilExplicitRetry() {
        val model = model()
        model.enterForeground()
        model.pause()
        model.leaveForeground()
        model.enterForeground()
        model.onCollectionPreferenceChanged()

        assertEquals(DiagRecordingPolicy.Mode.Idle, model.collectionMode.value)
        assertFalse(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)

        model.retry()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertTrue(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
    }

    @Test
    fun screenLockDoesNotResetOrCancelACollectorThatIsStillAlive() {
        val model = model()
        model.enterForeground()
        val process = FakeCollectorProcess(alive = true)
        model.bindLiveCollectorForTest(process, RootDiagStatus.Running)
        RootDiagCollectorService.stop(app)
        assertFalse(RootDiagCollectorService.isMaintained)

        model.leaveForeground()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertFalse(model.captureWasCancelled())
        assertTrue(process.isAlive)

        model.enterForeground()

        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertFalse(model.captureWasCancelled())
        assertFalse(process.destroyed)
    }

    @Test
    fun foregroundEntryRestartsOnlyAfterTheCollectorProcessIsGone() {
        val model = model()
        model.bindLiveCollectorForTest(FakeCollectorProcess(alive = false), RootDiagStatus.Running)

        model.enterForeground()

        assertEquals(RootDiagStatus.Checking, model.signal.value.status)
    }

    @Test
    fun pauseStillStopsAnAliveCollector() {
        val model = model()
        model.enterForeground()
        model.bindLiveCollectorForTest(FakeCollectorProcess(alive = true), RootDiagStatus.Running)

        model.pause()

        assertEquals(DiagRecordingPolicy.Mode.Idle, model.collectionMode.value)
        assertTrue(model.captureWasCancelled())
    }
}

private class FakeCollectorProcess(alive: Boolean) : Process() {
    var destroyed = false
        private set
    private var running = alive

    override fun isAlive(): Boolean = running && !destroyed
    override fun destroy() {
        destroyed = true
        running = false
    }
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
}
