package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.craxiom.networksurvey.db.SurveyTaskStore
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootDiagBackgroundCollectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val collectors = mutableListOf<FakeCollectorProcess>()
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        RootDiagPrefs.store(app).edit().clear().putBoolean("background_collection", false).commit()
        RootDiagCollectorService.stop(app)
        RootDiagCollectorService.stopRequested.value = false
    }

    @After
    fun tearDown() {
        models.clear()
        dispatcher.scheduler.runCurrent()
        SurveyTaskStore.get(app).finish()
        RootDiagCollectorService.stop(app)
        RootDiagCollectorService.stopRequested.value = false
        RootDiagPrefs.store(app).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun model(initialPackets: Int = 1, ignoreClose: Boolean = false) = RootDiagViewModel(app, dispatcher) {
        collectors.lastOrNull()?.assertReleased()
        FakeCollectorProcess(initialPackets, ignoreClose).also { collectors += it }
    }.also { models.put("diag", it) }

    private fun advance(millis: Long = 400) {
        dispatcher.scheduler.runCurrent()
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(40L, remaining)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            dispatcher.scheduler.advanceTimeBy(step)
            dispatcher.scheduler.runCurrent()
            remaining -= step
        }
    }

    @Test
    fun legacyDisabledPreferenceDoesNotStopLiveListeningWhenActivityLeaves() {
        val model = model()
        model.enterForeground()
        assertTrue(RootDiagCollectorService.isMaintained)

        model.leaveForeground()
        advance()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertTrue(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
        assertFalse(model.qmdl.value.hasRecording)
        assertTrue(collectors.single().commands.isEmpty())
    }

    @Test
    fun manualPauseSurvivesActivityAndOverlayPreferenceChangesUntilExplicitRetry() {
        val model = model()
        model.enterForeground()
        model.pause()
        model.leaveForeground()
        model.enterForeground()
        model.onCollectionPreferenceChanged()
        advance()

        assertEquals(DiagRecordingPolicy.Mode.Idle, model.collectionMode.value)
        assertFalse(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)

        model.retry()
        advance()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertTrue(RootDiagCollectorService.isMaintained)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
    }

    @Test
    fun screenLockDoesNotResetOrCancelACollectorThatIsStillAlive() {
        val model = model()
        model.enterForeground()
        advance()
        val process = collectors.single()
        RootDiagCollectorService.stop(app)
        assertFalse(RootDiagCollectorService.isMaintained)

        model.leaveForeground()
        advance()

        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertTrue(process.isAlive)

        model.enterForeground()
        advance()

        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertEquals(DiagRecordingPolicy.Mode.LiveView, model.collectionMode.value)
        assertTrue(process.isAlive)
        assertEquals(1, collectors.size)
    }

    @Test
    fun foregroundEntryRestartsOnlyAfterTheCollectorProcessIsGone() {
        val model = model()
        model.enterForeground()
        advance()
        val previous = collectors.single()
        previous.exit()

        model.enterForeground()
        assertEquals(1, collectors.size)
        advance()
        previous.assertReleased()

        advance(CollectorRestartPolicy.delayMs(0))
        assertEquals(2, collectors.size)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
    }

    @Test
    fun malformedCollectorLineDoesNotStopLaterStatusUpdates() {
        val model = model()
        model.enterForeground()
        advance()
        val process = collectors.single()

        process.emit("{malformed-json")
        process.heartbeat(packets = 2)
        advance()

        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertTrue(model.signal.value.hasModemPackets)
    }

    @Test
    fun pauseStillStopsAnAliveCollector() {
        val model = model()
        model.enterForeground()
        advance()
        val process = collectors.single()

        model.pause()
        advance()

        assertEquals(DiagRecordingPolicy.Mode.Idle, model.collectionMode.value)
        process.assertReleased()
        assertEquals(1, collectors.size)
    }

    @Test
    fun immediateRetryStartsOnlyAfterThePausedCollectorCloses() {
        val model = model()
        model.enterForeground()
        advance()

        model.pause()
        model.retry()
        assertEquals(1, collectors.size)

        advance()
        assertEquals(2, collectors.size)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
    }

    @Test
    fun silentReconnectReleasesThePreviousCollectorBeforeStartingAnother() {
        val model = model(initialPackets = 0)
        model.enterForeground()
        advance(10_000)
        collectors.single().heartbeat(packets = 0)
        advance(10_400)
        assertTrue(model.signal.value.modemWaitTimedOut)

        model.reconnectSilentCollector()
        assertEquals(1, collectors.size)
        advance()

        assertEquals(2, collectors.size)
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
        assertFalse(model.signal.value.modemWaitTimedOut)
    }

    @Test
    fun retryWhileStoppingQmdlWaitsForTheAcknowledgmentAndArchivesTheRecording() {
        val task = SurveyTaskStore.get(app).start("QMDL test", needLog = false)
        val model = model()
        model.enterForeground()
        advance()
        val process = collectors.single()
        assertTrue(process.commands.isEmpty())

        model.startQmdl()
        advance()
        assertEquals("Q", process.commands.toString())
        val recordingDir = File(app.noBackupFilesDir, "qmdl")
        val active = File(recordingDir, DiagRecordingSession.ACTIVE_NAME)
        val archived = File(recordingDir, "${task.id}.qmdl")
        val samples = byteArrayOf(1, 2, 3)
        assertTrue(active.isFile)
        active.writeBytes(samples)
        process.emit("""{"type":"qmdl","state":"recording","packets":1,"bytes":3}""")
        advance()
        assertEquals(QmdlPhase.Recording, model.qmdl.value.phase)

        model.pause()
        model.retry()
        advance()

        assertEquals("QS", process.commands.toString())
        assertEquals(QmdlPhase.Stopping, model.qmdl.value.phase)
        assertEquals(1, collectors.size)
        assertTrue(process.isAlive)
        assertTrue(active.isFile)
        assertFalse(archived.exists())

        process.emit("""{"type":"qmdl","state":"stopped","packets":1,"bytes":3}""")
        advance()

        assertEquals(2, collectors.size)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
        assertEquals(QmdlResult.Saved, model.qmdl.value.result)
        assertTrue(model.qmdl.value.hasRecording)
        assertFalse(active.exists())
        assertTrue(samples.contentEquals(archived.readBytes()))
    }

    @Test
    fun lingeringProcessExitFinalizesQmdlAndHonorsRetryRequestedWhileItWasAlive() {
        val task = SurveyTaskStore.get(app).start("Lingering QMDL", needLog = false)
        val model = model(ignoreClose = true)
        model.enterForeground()
        advance()
        val process = collectors.single()

        model.startQmdl()
        advance()
        process.emit("""{"type":"qmdl","state":"recording","packets":1,"bytes":3}""")
        advance()
        assertEquals(QmdlPhase.Recording, model.qmdl.value.phase)

        val recordingDir = File(app.noBackupFilesDir, "qmdl")
        val active = File(recordingDir, DiagRecordingSession.ACTIVE_NAME)
        val archived = File(recordingDir, "${task.id}.qmdl")
        val samples = byteArrayOf(4, 5, 6)
        active.writeBytes(samples)
        process.failReads()
        advance()

        assertTrue(process.isAlive)
        assertTrue(model.qmdl.value.recovering)
        assertTrue(active.isFile)
        assertFalse(archived.exists())

        model.retry()
        process.exit()
        advance(CollectorRestartPolicy.delayMs(0))

        assertEquals(2, collectors.size)
        assertEquals(QmdlPhase.Idle, model.qmdl.value.phase)
        assertEquals(QmdlResult.Interrupted, model.qmdl.value.result)
        assertTrue(model.qmdl.value.hasRecording)
        assertFalse(active.exists())
        assertTrue(samples.contentEquals(archived.readBytes()))
        assertEquals(RootDiagStatus.Running, model.signal.value.status)
    }
}

private class FakeCollectorProcess(initialPackets: Int, private val ignoreClose: Boolean = false) : Process() {
    var commands = StringBuilder()
        private set
    private var running = true
    private var stdinClosed = false
    private var stdoutClosed = false
    private var waitedForExit = false
    private var failReads = false
    private val queue = ArrayDeque<Byte>()

    private val stdin = object : OutputStream() {
        override fun write(b: Int) {
            check(!stdinClosed)
            commands.append(b.toChar())
        }

        override fun close() {
            stdinClosed = true
            if (!ignoreClose) exit()
        }
    }

    private val stdout = object : InputStream() {
        override fun available(): Int {
            if (failReads) throw IOException("simulated collector read failure")
            return queue.size
        }

        override fun read(): Int {
            check(!stdoutClosed)
            return queue.removeFirstOrNull()?.toInt()?.and(0xff) ?: -1
        }

        override fun close() {
            stdoutClosed = true
        }
    }

    init {
        heartbeat(initialPackets)
    }

    fun heartbeat(packets: Int) = emit("""{"type":"status","state":"running","pkts":$packets}""")

    fun emit(json: String) {
        for (byte in "$json\n".toByteArray()) queue.addLast(byte)
    }

    fun exit() {
        running = false
    }

    fun failReads() {
        failReads = true
    }

    fun assertReleased() {
        assertFalse(isAlive)
        assertTrue(stdinClosed)
        assertTrue(stdoutClosed)
        assertTrue(waitedForExit)
    }

    override fun isAlive(): Boolean = running
    override fun destroy() {
        if (!ignoreClose) exit()
    }

    override fun destroyForcibly(): Process {
        if (!ignoreClose) exit()
        return this
    }
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = error("Collector shutdown must use a bounded wait")
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitedForExit = true
        return !isAlive
    }
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
}
