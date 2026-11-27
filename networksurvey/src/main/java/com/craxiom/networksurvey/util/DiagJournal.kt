package com.craxiom.networksurvey.util

import android.content.Context
import com.google.gson.JsonParser
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors

/**
 * Crash-resistant journal of decoded DIAG records.
 *
 * The diagnostic export used to live only in memory, so a crash (or a native
 * collector crash) lost everything. Every exported record is appended here,
 * redacted, one JSON object per line, and survives process restarts. Writes
 * run on a single background thread with a bounded queue: capture never blocks
 * on flash, and a full queue drops the newest lines rather than stalling.
 * Bounded to two files so the newest history always fits in [MAX_BYTES] x 2.
 */
object DiagJournal {
    private const val DIR = "diag"
    private const val NAME = "journal.jsonl"
    private const val PREVIOUS = "journal.1.jsonl"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val QUEUE_LIMIT = 4096

    private val lock = Any()
    private val writeLock = Any()
    private val queue = LinkedBlockingQueue<String>(QUEUE_LIMIT)
    private val scheduled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diag-journal").apply { isDaemon = true }
    }

    @Volatile
    private var directory: File? = null

    /** Lines dropped because the writer could not keep up (reported at export). */
    private val dropped = AtomicLong()

    private fun dir(context: Context): File = synchronized(lock) {
        directory ?: File(context.filesDir, DIR).apply { mkdirs() }.also { directory = it }
    }

    /** Append one already-redacted JSON line. Failures never disturb capture. */
    fun append(context: Context, line: String) {
        if (line.isBlank()) return
        if (!queue.offer(line)) {
            dropped.incrementAndGet()
            return
        }
        if (scheduled.compareAndSet(false, true)) {
            runCatching { executor.execute { drain(context) } }.onFailure { scheduled.set(false) }
        }
    }

    fun append(context: Context, value: JSONObject) = append(context, value.toString())

    private fun drain(context: Context) {
        synchronized(writeLock) {
            runCatching {
                val d = dir(context)
                val file = File(d, NAME)
                if (file.length() > MAX_BYTES) {
                    val previous = File(d, PREVIOUS)
                    previous.delete()
                    file.renameTo(previous)
                }
                FileOutputStream(file, true).use { out ->
                    while (true) {
                        val line = queue.poll() ?: break
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                    }
                    out.flush()
                }
            }
        }
        scheduled.set(false)
        if (queue.isNotEmpty() && scheduled.compareAndSet(false, true)) {
            runCatching { executor.execute { drain(context) } }.onFailure { scheduled.set(false) }
        }
    }

    /** Oldest file first; the caller writes the lines verbatim (already redacted). */
    fun read(context: Context): List<String> = synchronized(lock) {
        runCatching {
            val d = File(context.filesDir, DIR)
            if (!d.isDirectory) return emptyList()
            val lines = ArrayList<String>()
            for (name in listOf(PREVIOUS, NAME)) {
                val file = File(d, name)
                if (file.isFile) file.forEachLine { if (it.isNotBlank()) lines.add(it) }
            }
            lines
        }.getOrDefault(emptyList())
    }

    /**
     * Drop stale `status` and `ims_provider` heartbeat rows from earlier
     * process sessions (`clock_id != currentClockId`) so uptime_s and drop
     * counters stay monotonic for the active session, while keeping earlier
     * signaling/event records and session_start markers for post-crash triage.
     */
    internal fun filterExportLines(lines: List<String>, currentClockId: String): List<String> =
        lines.filter { line ->
            val obj = runCatching { JsonParser.parseString(line) }
                .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@filter true
            val type = obj.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (type != "status" && type != "ims_provider") return@filter true
            val clockId = obj.get("clock_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            clockId == currentClockId
        }

    fun droppedLines(): Long = dropped.get()

    /** Export path: write whatever is queued before the file is read. */
    fun flush(context: Context) {
        synchronized(writeLock) {
            runCatching {
                val d = dir(context)
                FileOutputStream(File(d, NAME), true).use { out ->
                    while (true) {
                        val line = queue.poll() ?: break
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                    }
                    out.flush()
                }
            }
        }
    }

    /** Used by the signaling "clear" action: the journal is user-visible history. */
    fun clear(context: Context) {
        queue.clear()
        synchronized(lock) {
            runCatching { File(context.filesDir, DIR).listFiles()?.forEach { it.delete() } }
        }
    }
}