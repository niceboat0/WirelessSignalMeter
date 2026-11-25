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
}
