package com.craxiom.networksurvey.ui.bandlock

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * One root helper and one diag-router client for every lock command.
 *
 * The first command starts `su` and connects the router. Later commands are
 * written to that process. This is not the collector's libdiag session: that
 * process only accepts QMDL start/stop on stdin, and lock writes stay off its
 * DCI client so the live log stream is not stalled.
 */
internal class BandLockHelperSession(
    private val helper: () -> File,
    private val directory: () -> File,
    private val identityText: () -> String?,
) : BandLockCommandRunner {
    private val gate = Any()
    private var held: Process? = null
    private var ready = false

    override val helperConnected: Boolean
        get() = synchronized(gate) { ready && held?.isAlive == true }

    override fun close() {
        val running = synchronized(gate) {
            ready = false
            held.also { held = null }
        }
        running?.let { process ->
            runCatching { process.outputStream.close() }
            process.destroy()
        }
    }

    override suspend fun run(
        command: BandLockCommand,
        plan: BandLockPlan,
        onLine: (String) -> Unit,
        diagnostic: (String) -> Unit,
    ): BandLockOutcome = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val running = synchronized(gate) { held.takeIf { ready && it?.isAlive == true } }
        if (plan.transport == BandLockTransport.ReuseSession && running != null) {
            submit(running, command, started, onLine, diagnostic)
        } else {
            open(plan, command, started, onLine, diagnostic)
        }
    }

    private suspend fun open(
        plan: BandLockPlan,
        command: BandLockCommand,
        started: Long,
        onLine: (String) -> Unit,
        diagnostic: (String) -> Unit,
    ): BandLockOutcome {
        close()
        val binary = helper()
        if (!binary.isFile) {
            diagnostic("helper_missing")
            throw IOException("The lock helper is unavailable for this ABI.")
        }
        val stateDir = directory()
        if (!stateDir.isDirectory && !stateDir.mkdirs()) {
            diagnostic("state_dir_unavailable")
            throw IOException("Cannot open the private backup directory.")
        }
        val identity = identityText() ?: throw IOException("The device identity is invalid.")
        val sessionFile = File(stateDir, "session-identity")
        sessionFile.writeText(identity)
        val arguments = mutableListOf(binary.absolutePath, "--state-dir", stateDir.absolutePath, "--session")
        plan.knownChannel?.let { arguments += listOf("--channel", it) }
        val running = try {
            ProcessBuilder("su", "-c", "exec " + arguments.joinToString(" ", transform = ::quote))
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            diagnostic("helper_launch_failed")
            sessionFile.delete()
            throw error
        }
        synchronized(gate) { held = running }
        val startup = try {
            readUntil(running, started, onLine, diagnostic, waitForConnect = true)
        } catch (error: IOException) {
            diagnostic("helper_io_error")
            close()
            sessionFile.delete()
            throw error
        }
        if (!startup.connected) {
            val exit = startup.processExit ?: 1
            close()
            sessionFile.delete()
            diagnostic("exit=$exit elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            return BandLockOutcome(exit, startup.output.toString())
        }
        synchronized(gate) { if (held === running) ready = true }
        sessionFile.delete()
        return submit(running, command, started, onLine, diagnostic)
    }

    private suspend fun submit(
        running: Process,
        command: BandLockCommand,
        started: Long,
        onLine: (String) -> Unit,
        diagnostic: (String) -> Unit,
    ): BandLockOutcome {
        val frame = try {
            BandLockSessionPolicy.encodeSessionCommand(command.arguments)
        } catch (error: IllegalArgumentException) {
            throw IOException(error.message ?: "Invalid lock command.")
        }
        try {
            running.outputStream.write(frame.toByteArray(Charsets.UTF_8))
            running.outputStream.flush()
        } catch (error: IOException) {
            diagnostic("helper_io_error")
            close()
            throw error
        }
        val read = try {
            readUntil(running, started, onLine, diagnostic, waitForConnect = false)
        } catch (error: IOException) {
            diagnostic("helper_io_error")
            close()
            throw error
        }
        if (!running.isAlive) close()
        val exit = read.sessionExit ?: read.processExit ?: 1
        diagnostic("exit=$exit elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        return BandLockOutcome(exit, read.output.toString())
    }

    private suspend fun readUntil(
        running: Process,
        started: Long,
        onLine: (String) -> Unit,
        diagnostic: (String) -> Unit,
        waitForConnect: Boolean,
    ): ReadFrame {
        val frame = ReadFrame()
        val pending = StringBuilder()
        val input = running.inputStream
        val bytes = ByteArray(4096)
        fun takeLine(line: String): Boolean {
            if (line == BandLockSessionPolicy.SESSION_CONNECTED) {
                frame.connected = true
                return true
            }
            val exit = BandLockSessionPolicy.sessionExitCode(line)
            if (exit != null) {
                frame.sessionExit = exit
                return true
            }
            frame.output.append(line).append('\n')
            onLine(line)
            return false
        }
        while (running.isAlive || input.available() > 0) {
            if (SystemClock.elapsedRealtime() - started > 110_000) {
                diagnostic("helper_timeout")
                running.destroyForcibly()
                close()
                throw IOException("Command timed out. Changes may be partial; refresh the device status.")
            }
            val available = input.available()
            if (available == 0) {
                if (!running.isAlive) break
                delay(80)
                continue
            }
            val count = input.read(bytes, 0, minOf(bytes.size, available))
            if (count <= 0) break
            val chunk = String(bytes, 0, count, Charsets.UTF_8)
            if (frame.output.length + pending.length + chunk.length > 48_000) {
                throw IOException("Unexpected helper output size.")
            }
            pending.append(chunk)
            var end = pending.indexOf("\n")
            while (end >= 0) {
                val done = takeLine(pending.substring(0, end).trimEnd('\r'))
                pending.delete(0, end + 1)
                if (done) return frame
                end = pending.indexOf("\n")
            }
        }
        if (pending.isNotEmpty()) takeLine(pending.toString())
        if (!frame.connected && frame.sessionExit == null) {
            frame.processExit = runCatching { running.waitFor() }.getOrNull()
                ?: if (!running.isAlive) 1 else null
        }
        if (waitForConnect && !frame.connected && frame.processExit == null) frame.processExit = 1
        return frame
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private class ReadFrame {
        val output = StringBuilder()
        var connected = false
        var sessionExit: Int? = null
        var processExit: Int? = null
    }
}
