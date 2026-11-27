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
        collectorAlive: Boolean = false,
    ): Mode {
        // Screen lock drops the activity to ON_STOP. A collector process that is
        // still alive keeps listening; it is restarted only after that process is gone.
        if (paused || (!foreground && !serviceMaintained && !collectorAlive)) return Mode.Idle
        return if (taskRunning) Mode.TaskRecording else Mode.LiveView
    }

    fun shouldListen(mode: Mode): Boolean = mode != Mode.Idle

    fun shouldRecord(mode: Mode): Boolean = mode == Mode.TaskRecording

    fun recordingTaskId(mode: Mode, task: SurveyTask?, manual: Boolean, blockedTaskId: String?): String? =
        task?.takeIf { shouldRecord(mode) && it.endedAt == null &&
            (manual || (it.needLog && it.id != blockedTaskId)) }?.id

    fun shouldHoldService(mode: Mode): Boolean = shouldListen(mode)

    fun overlayStatus(mode: Mode, recording: Boolean = false): String = when {
        mode == Mode.TaskRecording && recording -> "recording"
        mode == Mode.TaskRecording -> "task"
        mode == Mode.LiveView -> "listening"
        else -> "standby"
    }

    /** Overlay headline and the foreground notification use this same value. */
    fun presence(overlayStatus: String, recording: Boolean): String = when {
        recording || overlayStatus == "recording" -> "recording"
        overlayStatus == "task" -> "task"
        overlayStatus == "listening" -> "listening"
        else -> "standby"
    }

    /**
     * A needLog task opens a file only after QMDL or DLF was chosen for that task.
     * An unknown name does not fall back to QMDL.
     */
    fun automaticRecordingFormat(needLog: Boolean, savedName: String?): DiagLogFormat? {
        if (!needLog) return null
        return when (savedName) {
            DiagLogFormat.Qmdl.name -> DiagLogFormat.Qmdl
            DiagLogFormat.Dlf.name -> DiagLogFormat.Dlf
            else -> null
        }
    }

    fun recordingFormatLabel(savedName: String?): String? = when (savedName) {
        DiagLogFormat.Dlf.name -> "DLF"
        DiagLogFormat.Qmdl.name -> "QMDL"
        else -> null
    }
}

/** One recording per task. QMDL is the default; DLF is the same log items without HDLC. */
internal enum class DiagLogFormat {
    Qmdl, Dlf;

    val extension: String get() = if (this == Qmdl) "qmdl" else "dlf"
    val command: Char get() = if (this == Qmdl) 'Q' else 'D'
    val activeName: String get() = "active.$extension"

    companion object {
        fun fromFileName(name: String): DiagLogFormat? = when {
            name.endsWith(".dlf") -> Dlf
            name.endsWith(".qmdl") -> Qmdl
            else -> null
        }
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
    private var format: DiagLogFormat = DiagLogFormat.Qmdl
    private val taskMarker = File(root, TASK_MARKER)

    val directory: File get() = root
    val activeFile get() = File(root, format.activeName)

    fun isOpen(): Boolean = openTaskId != null

    fun openTaskId(): String? = openTaskId

    fun recordings(): List<File> = committedFiles()

    /** Every non-empty segment, including a leftover active file. Active is not preferred. */
    fun exportableRecordings(): List<File> {
        val active = DiagLogFormat.entries.map { File(root, it.activeName) }
            .filter { it.isFile && it.length() > 0L }
        return (committedFiles() + active).sortedBy { it.name }
    }

    fun latestRecording(): File? = exportableRecordings().maxByOrNull { it.lastModified() }

    /** Live view or created-but-not-started tasks must leave the directory empty of new session files. */
    fun ensureLiveOnly() {
        if (openTaskId != null) return
        for (item in DiagLogFormat.entries) {
            val file = File(root, item.activeName)
            if (file.exists() && file.length() == 0L) file.delete()
        }
    }

    fun start(taskId: String, logFormat: DiagLogFormat = DiagLogFormat.Qmdl): File {
        val id = taskId.trim()
        require(id.isNotEmpty()) { "task id required" }
        check(openTaskId == null) { "Previous recording session is still open" }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Unable to create recording directory")
        format = logFormat
        recoverLeftoverActive()
        val file = activeFile
        if (!file.createNewFile()) throw IOException("Unable to create $file")
        taskMarker.writeText(id)
        openTaskId = id
        return file
    }

    fun finish(): File? {
        val id = openTaskId ?: return null
        openTaskId = null
        val archived = archiveFile(activeFile, id, format)
        if (taskMarker.exists() && !taskMarker.delete()) throw IOException("Unable to clear recording task marker")
        return archived
    }

    fun cannotWrite(): Boolean = openTaskId == null || !activeFile.isFile

    /** Name a leftover active file with the task id recorded when it was opened. */
    fun recoverLeftoverActive() {
        if (openTaskId != null) return
        val remembered = rememberedTaskId() ?: "orphan"
        for (item in DiagLogFormat.entries) archiveFile(File(root, item.activeName), remembered, item)
        if (taskMarker.exists()) taskMarker.delete()
    }

    private fun committedFiles(): List<File> = root.listFiles()
        ?.filter { it.isFile && DiagLogFormat.fromFileName(it.name) != null && !it.name.startsWith("active.") }
        ?.sortedBy { it.name }
        ?: emptyList()

    private fun rememberedTaskId(): String? =
        taskMarker.takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun archiveFile(file: File, id: String, itemFormat: DiagLogFormat): File? {
        if (!file.exists()) return null
        if (!file.isFile) throw IOException("Invalid active recording path")
        if (file.length() == 0L) {
            if (!file.delete()) throw IOException("Unable to remove empty recording file")
            return null
        }
        val dest = uniqueCommitted(id, itemFormat)
        if (!rename(file, dest)) throw IOException("Unable to archive recording file")
        return dest
    }

    private fun uniqueCommitted(taskId: String, itemFormat: DiagLogFormat): File {
        val safe = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var dest = File(root, "$safe.${itemFormat.extension}")
        var index = 2
        while (dest.exists()) {
            dest = File(root, "$safe-$index.${itemFormat.extension}")
            index++
        }
        return dest
    }

    companion object {
        const val ACTIVE_NAME = "active.qmdl"
        private const val TASK_MARKER = "active.task"
    }
}
