package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.model.SurveyTask
import java.io.File
import java.io.IOException

/**
 * Live viewing and test-task recording are separate.
 * A maintained foreground service keeps the live stream up without opening a recording.
 */
internal object DiagRecordingPolicy {
    enum class Mode { Idle, LiveView, TaskRecording }

    fun mode(
        taskRunning: Boolean,
        foreground: Boolean,
        serviceMaintained: Boolean,
        paused: Boolean,
    ): Mode {
        if (paused || (!foreground && !serviceMaintained)) return Mode.Idle
        return if (taskRunning) Mode.TaskRecording else Mode.LiveView
    }

    fun shouldListen(mode: Mode): Boolean = mode != Mode.Idle

    fun shouldRecord(mode: Mode): Boolean = mode == Mode.TaskRecording

    fun recordingTaskId(mode: Mode, task: SurveyTask?, manual: Boolean, blockedTaskId: String?): String? =
        task?.takeIf { shouldRecord(mode) && it.endedAt == null &&
            (manual || (it.needLog && it.id != blockedTaskId)) }?.id

    fun shouldHoldService(mode: Mode): Boolean = shouldListen(mode)

    fun overlayStatus(mode: Mode): String = when (mode) {
        Mode.TaskRecording -> "task"
        Mode.LiveView -> "listening"
        Mode.Idle -> "standby"
    }
}

/**
 * One test task owns one recording session's files.
 * Live viewing must not create files in [root]. Writes go to a single
 * active file that is only created in [start] and renamed in [finish].
 */
internal class DiagRecordingSession(
    private val root: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {
    private var openTaskId: String? = null

    val activeFile get() = File(root, ACTIVE_NAME)

    fun isOpen(): Boolean = openTaskId != null

    fun openTaskId(): String? = openTaskId

    fun recordings(): List<File> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".qmdl") && it.name != ACTIVE_NAME }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun latestRecording(): File? =
        activeFile.takeIf { it.isFile && it.length() > 0L }
            ?: recordings().filter { it.length() > 0L }.maxByOrNull { it.lastModified() }

    /** Live view or created-but-not-started tasks must leave the directory empty of new session files. */
    fun ensureLiveOnly() {
        if (openTaskId != null) return
        if (activeFile.exists() && activeFile.length() == 0L) activeFile.delete()
    }

    fun start(taskId: String): File {
        val id = taskId.trim()
        require(id.isNotEmpty()) { "task id required" }
        check(openTaskId == null) { "Previous QMDL session is still open" }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Unable to create QMDL directory")
        val file = activeFile
        archiveActive("orphan")
        if (!file.createNewFile()) throw IOException("Unable to create $file")
        openTaskId = id
        return file
    }

    fun finish(): File? {
        val id = openTaskId ?: return null
        openTaskId = null
        return archiveActive(id)
    }

    fun cannotWrite(): Boolean = openTaskId == null || !activeFile.isFile

    private fun archiveActive(id: String): File? {
        val file = activeFile
        if (!file.exists()) return null
        if (!file.isFile) throw IOException("Invalid active QMDL path")
        if (file.length() == 0L) {
            if (!file.delete()) throw IOException("Unable to remove empty QMDL file")
            return null
        }
        val dest = uniqueCommitted(id)
        if (!rename(file, dest)) throw IOException("Unable to archive QMDL file")
        return dest
    }

    private fun uniqueCommitted(taskId: String): File {
        val safe = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var dest = File(root, "$safe.qmdl")
        var index = 2
        while (dest.exists()) {
            dest = File(root, "$safe-$index.qmdl")
            index++
        }
        return dest
    }

    companion object {
        const val ACTIVE_NAME = "active.qmdl"
    }
}
