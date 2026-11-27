package com.craxiom.networksurvey.ui.bandlock

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * One-shot runner for the QMI instant-lock probe. Unlike the NV helper this is
 * a plain command line tool: no stdin session, no diag-router socket; it opens
 * its own QRTR socket in a short-lived `su` child and prints one line per step.
 */
internal open class QmiLockRunner(private val helper: () -> File) {
    open suspend fun run(arguments: List<String>, timeoutMs: Long = 25_000): QmiLockOutcome =
        withContext(Dispatchers.IO) {
            val binary = helper()
            if (!binary.isFile) {
                return@withContext QmiLockOutcome.unavailable()
            }
            val command = "exec " + (listOf(binary.absolutePath) + arguments).joinToString(" ", transform = ::quote)
            val started = SystemClock.elapsedRealtime()
            val process = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            } catch (error: IOException) {
                return@withContext QmiLockOutcome.unavailable()
            }
            val output = StringBuilder()
            try {
                val input = process.inputStream
                val bytes = ByteArray(4096)
                var pending = StringBuilder()
                while (process.isAlive || input.available() > 0) {
                    if (SystemClock.elapsedRealtime() - started > timeoutMs) {
                        process.destroyForcibly()
                        return@withContext QmiLockOutcome(1, output.toString(), "timeout", false)
                    }
                    val available = input.available()
                    if (available == 0) {
                        delay(50)
                        continue
                    }
                    val count = input.read(bytes, 0, minOf(bytes.size, available))
                    if (count <= 0) break
                    if (output.length > 48_000) break
                    pending.append(String(bytes, 0, count, Charsets.UTF_8))
                    var end = pending.indexOf("\n")
                    while (end >= 0) {
                        output.append(pending.substring(0, end).trimEnd('\r')).append('\n')
                        pending.delete(0, end + 1)
                        end = pending.indexOf("\n")
                    }
                }
                if (pending.isNotEmpty()) output.append(pending).append('\n')
                val exit = runCatching {
                    if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.exitValue() else 1
                }.getOrDefault(1)
                QmiLockOutcome.read(exit, output.toString(), arguments.first())
            } finally {
                runCatching { process.destroy() }
            }
        }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

}
