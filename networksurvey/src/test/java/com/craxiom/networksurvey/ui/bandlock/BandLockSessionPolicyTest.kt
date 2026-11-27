package com.craxiom.networksurvey.ui.bandlock

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class BandLockSessionPolicyTest {
    @Test
    fun connectedHelperSkipsRootShellAndChannelDiscovery() {
        val plan = BandLockSessionPolicy.plan(
            collectorRunning = true,
            helperConnected = true,
            knownChannel = "samsung-diag-router",
            kind = LockWriteKind.Probe,
        )
        assertEquals(BandLockTransport.ReuseSession, plan.transport)
        assertFalse(plan.spawnRootShell)
        assertFalse(plan.discoverChannel)
        assertFalse(plan.announceRootDiagCheck)
        assertTrue(plan.checksCapabilityAndBackup)
        assertNull(plan.knownChannel)
    }

    @Test
    fun runningCollectorDoesNotAnnounceRootDiagRediscovery() {
        val saved = BandLockSessionPolicy.plan(
            collectorRunning = true,
            helperConnected = false,
            knownChannel = "diag-stream",
            kind = LockWriteKind.Probe,
        )
        assertEquals(BandLockTransport.OpenSession, saved.transport)
        assertFalse(saved.announceRootDiagCheck)
        assertFalse(saved.discoverChannel)
        assertEquals("diag-stream", saved.knownChannel)
        assertTrue(saved.checksCapabilityAndBackup)

        val unknown = BandLockSessionPolicy.plan(true, false, null, LockWriteKind.Probe)
        assertFalse(unknown.announceRootDiagCheck)
        assertTrue(unknown.checksCapabilityAndBackup)
        assertTrue(unknown.spawnRootShell)
    }

    @Test
    fun coldProbeStillDiscoversRootAndTheDiagChannel() {
        val plan = BandLockSessionPolicy.plan(false, false, null, LockWriteKind.Probe)
        assertEquals(BandLockTransport.OpenSession, plan.transport)
        assertTrue(plan.announceRootDiagCheck)
        assertTrue(plan.discoverChannel)
        assertTrue(plan.spawnRootShell)
        assertTrue(plan.checksCapabilityAndBackup)
    }

    @Test
    fun collectorAloneDoesNotTurnAWriteIntoAConfirmedProbe() {
        val write = BandLockSessionPolicy.plan(true, true, "diag-seqpacket", LockWriteKind.Apply)
        assertFalse(write.checksCapabilityAndBackup)
        assertFalse(write.announceRootDiagCheck)
        assertEquals(BandLockTransport.ReuseSession, write.transport)
    }

    @Test
    fun unsafeChannelNamesAreNotReused() {
        assertEquals("samsung-diag-router", BandLockSessionPolicy.normalizedChannel("samsung-diag-router"))
        assertNull(BandLockSessionPolicy.normalizedChannel("diag stream"))
        assertNull(BandLockSessionPolicy.normalizedChannel("diag;reboot"))
        assertNull(BandLockSessionPolicy.normalizedChannel("../diag"))
        val plan = BandLockSessionPolicy.plan(true, false, "diag;reboot", LockWriteKind.Probe)
        assertNull(plan.knownChannel)
        assertTrue(plan.discoverChannel)
    }

    @Test
    fun sessionFramesCarryArgumentsWithoutAShell() {
        assertEquals(
            "CMD 3\nprobe\nPixel 8\nTensor\n",
            BandLockSessionPolicy.encodeSessionCommand(listOf("probe", "Pixel 8", "Tensor")),
        )
        assertEquals(0, BandLockSessionPolicy.sessionExitCode("SESSION exit 0"))
        assertEquals(2, BandLockSessionPolicy.sessionExitCode("SESSION exit 2"))
        assertNull(BandLockSessionPolicy.sessionExitCode("SESSION exit"))
        assertNull(BandLockSessionPolicy.sessionExitCode("RESULT ready"))
        assertTrue(runCatching {
            BandLockSessionPolicy.encodeSessionCommand(listOf("probe\nid"))
        }.isFailure)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BandLockCollectorReuseTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("band_lock_probe", 0).edit().clear().commit()
        app.getSharedPreferences("band_lock_backend", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    private class FakeQmiRunner : QmiLockRunner({ java.io.File("unused") }) {
        var next: QmiLockOutcome = QmiLockOutcome(1, "", "unavailable", false)
        val calls = mutableListOf<List<String>>()
        override suspend fun run(arguments: List<String>, timeoutMs: Long): QmiLockOutcome {
            calls += arguments
            return next
        }
    }

    private fun model(): BandLockViewModel = BandLockViewModel(app).also {
        it.qmiRunner = FakeQmiRunner()
        models.put("lock", it)
    }

    private class RecordingRunner(
        private val availability: () -> LockAvailability = { LockAvailability.Unchecked },
    ) : BandLockCommandRunner {
        var connected = false
        var succeed = true
        val calls = mutableListOf<Pair<BandLockPlan, List<String>>>()
        val availabilities = mutableListOf<LockAvailability>()
        override val helperConnected get() = connected
        override fun close() { connected = false }
        override suspend fun run(
            command: BandLockCommand,
            plan: BandLockPlan,
            onLine: (String) -> Unit,
            diagnostic: (String) -> Unit,
        ): BandLockOutcome {
            calls += plan to command.arguments
            availabilities += availability()
            if (plan.transport == BandLockTransport.OpenSession) connected = true
            val output = when {
                !succeed -> "No recognized modem acknowledgement.\n"
                command.arguments.first() == "probe" -> """
                    CHANNEL samsung-diag-router
                    RESULT ready
                    CAP lte_bandpref 1
                    BACKUP lte 0
                    BACKUP nr 0
                """.trimIndent() + "\n"
                else -> "RESULT accepted\n"
            }
            output.lineSequence().filter { it.isNotEmpty() }.forEach(onLine)
            return BandLockOutcome(if (succeed) 0 else 1, output)
        }
    }

    @Test
    fun runningCollectorReusesTheHelperInsteadOfRediscoveringRootAndDiag() {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }.also { it.connected = true }
        model.commandRunner = runner

        model.refresh(collectorRunning = true)
        dispatcher.scheduler.advanceUntilIdle()

        val (plan, args) = runner.calls.single()
        assertEquals(listOf("probe", android.os.Build.MODEL, android.os.Build.SOC_MODEL), args)
        assertEquals(BandLockTransport.ReuseSession, plan.transport)
        assertFalse(plan.spawnRootShell)
        assertFalse(plan.discoverChannel)
        assertFalse(plan.announceRootDiagCheck)
        assertTrue(plan.checksCapabilityAndBackup)
        assertEquals(listOf(LockAvailability.Unchecked), runner.availabilities)
        assertEquals(LockAvailability.Ready, model.state.value.availability)
    }

    @Test
    fun secondCommandDoesNotOpenAnotherRootShell() {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }
        model.commandRunner = runner

        model.refresh(collectorRunning = true)
        dispatcher.scheduler.advanceUntilIdle()
        model.refresh(collectorRunning = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, runner.calls.size)
        assertEquals(BandLockTransport.OpenSession, runner.calls[0].first.transport)
        assertFalse(runner.calls[0].first.announceRootDiagCheck)
        assertTrue(runner.calls[0].first.checksCapabilityAndBackup)
        assertEquals(BandLockTransport.ReuseSession, runner.calls[1].first.transport)
        assertFalse(runner.calls[1].first.spawnRootShell)
        assertFalse(runner.calls[1].first.discoverChannel)
        assertTrue(runner.calls.all { it.second.first() == "probe" })
        assertTrue(runner.availabilities.none { it == LockAvailability.Checking })
    }

    @Test
    fun coldProbeStillAnnouncesRootAndDiagDiscovery() {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }
        model.commandRunner = runner

        model.refresh(collectorRunning = false)
        dispatcher.scheduler.advanceUntilIdle()

        val plan = runner.calls.single().first
        assertTrue(plan.announceRootDiagCheck)
        assertTrue(plan.discoverChannel)
        assertTrue(plan.spawnRootShell)
        assertEquals(listOf(LockAvailability.Checking), runner.availabilities)
        assertEquals(LockAvailability.Ready, model.state.value.availability)
    }

    @Test
    fun cachedWriteStillNeedsALiveProbeWhenTheCollectorIsRunning() {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }.also { it.connected = true; it.succeed = false }
        model.commandRunner = runner
        model.restoreForTest(BandLockState(
            availability = LockAvailability.Cached,
            channel = "samsung-diag-router",
            profile = BandLockProfile(LockSupportLevel.Verified, "qualcomm-diag", "samsung-diag-router"),
        ))

        model.run(
            BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30),
            collectorRunning = true,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("probe"), runner.calls.map { it.second.first() })
        assertTrue(runner.calls.single().first.checksCapabilityAndBackup)
        assertFalse(runner.calls.single().first.spawnRootShell)
        assertFalse(runner.calls.single().first.discoverChannel)
        assertTrue(runner.availabilities.none { it == LockAvailability.Checking })
        assertEquals(LockAvailability.Unavailable, model.state.value.availability)
        assertEquals(
            com.craxiom.networksurvey.R.string.lock_write_blocked_unconfirmed,
            model.state.value.result,
        )
    }

    @Test
    fun confirmedCachedWriteReusesOneHelperForTheProbeAndTheWrite() = runTest(dispatcher) {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }.also { it.connected = true }
        model.commandRunner = runner
        model.restoreForTest(BandLockState(
            availability = LockAvailability.Cached,
            channel = "diag-stream",
            profile = BandLockProfile(LockSupportLevel.Verified, "qualcomm-diag", "diag-stream"),
        ))

        model.run(
            BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30),
            collectorRunning = true,
        )
        assertTrue(model.state.value.busy)
        // Backend persistence uses real IO; an idle test scheduler does not imply completion.
        model.state.first { !it.busy }

        assertEquals(listOf("probe", "nr-band", "mode"), runner.calls.map { it.second.first() })
        assertTrue(runner.calls.all { !it.first.spawnRootShell && !it.first.discoverChannel && !it.first.announceRootDiagCheck })
        assertTrue(runner.calls.first().first.checksCapabilityAndBackup)
        assertEquals(LockAvailability.Ready, model.state.value.availability)
        assertTrue(runner.availabilities.none { it == LockAvailability.Checking })
    }

    @Test
    fun qmiModeSuccessSkipsDiagModeAfterLock() = runTest(dispatcher) {
        val model = model()
        val runner = RecordingRunner { model.state.value.availability }.also { it.connected = true }
        val qmi = FakeQmiRunner().apply {
            next = QmiLockOutcome(0, "QMI mode ok\nVERDICT ok\n", "ok", reachable = true)
        }
        model.commandRunner = runner
        model.qmiRunner = qmi
        model.restoreForTest(BandLockState(
            availability = LockAvailability.Ready,
            channel = "samsung-diag-router",
            profile = BandLockProfile(LockSupportLevel.Verified, "qualcomm-diag", "samsung-diag-router"),
        ))

        model.run(
            BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "1", "", "", 30),
            collectorRunning = true,
        )
        model.state.first { !it.busy }

        assertEquals(listOf("nr-band"), runner.calls.map { it.second.first() })
        assertEquals(listOf(listOf("mode", "nr")), qmi.calls)
        assertEquals(LockNetworkMode.Nr, model.state.value.appliedMode)
        assertEquals(com.craxiom.networksurvey.R.string.lock_and_mode_accepted, model.state.value.result)
    }
}
