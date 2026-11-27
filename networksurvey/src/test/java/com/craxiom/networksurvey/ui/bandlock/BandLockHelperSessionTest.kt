package com.craxiom.networksurvey.ui.bandlock

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BandLockHelperSessionTest {
    @Test
    fun cancellingAnActiveCommandClosesTheHelperSession() = runBlocking {
        val directory = Files.createTempDirectory("wsm-band-lock-session").toFile()
        val helper = File(directory, "helper").apply { writeBytes(byteArrayOf(1)) }
        val process = BlockingProcess("SESSION connected\n")
        val session = BandLockHelperSession(
            helper = { helper },
            directory = { directory },
            identityText = { "SM-S9370\nSM8750\nfp/release-keys" },
            startProcess = { process },
        )
        val commandWritten = CountDownLatch(1)
        process.onWrite = { commandWritten.countDown() }

        try {
            val job = launch(Dispatchers.Default) {
                session.run(
                    command = BandLockCommand.console("nr-band 78"),
                    plan = BandLockSessionPolicy.plan(
                        collectorRunning = false,
                        helperConnected = false,
                        knownChannel = null,
                        kind = LockWriteKind.Apply,
                    ),
                    onLine = {},
                    diagnostic = {},
                )
            }

            assertTrue(commandWritten.await(2, TimeUnit.SECONDS))
            job.cancelAndJoin()

            assertTrue(process.destroyed)
            assertFalse(session.helperConnected)
        } finally {
            session.close()
            directory.deleteRecursively()
        }
    }

    private class BlockingProcess(startup: String) : Process() {
        private val input = ByteArrayInputStream(startup.toByteArray())
        private val output = ByteArrayOutputStream()
        var onWrite: (() -> Unit)? = null
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream =
            RecordingOutputStream(output) { onWrite?.invoke() }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = !destroyed

        override fun toString(): String = "BlockingProcess"

        private class RecordingOutputStream(
            private val delegate: ByteArrayOutputStream,
            private val onWrite: () -> Unit,
        ) : OutputStream() {
            override fun write(value: Int) {
                delegate.write(value)
                onWrite()
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                delegate.write(bytes, offset, length)
                onWrite()
            }
        }
    }
}
