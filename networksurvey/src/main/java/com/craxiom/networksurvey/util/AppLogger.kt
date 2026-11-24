package com.craxiom.networksurvey.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 应用日志工具：Logcat 输出 + 写入 filesDir/logs/app.log（超 512KB 保留末 3000 行）。
 * 由 Application.onCreate 调用 init() 初始化，并接管默认未捕获异常处理器。
 */
object AppLogger {

    private const val TAG_FILE = "app.log"
    private const val MAX_LOG_BYTES = 512 * 1024L
    private const val KEEP_LINES = 3000
    internal const val BAND_LOCK_TAG = "BandLock"

    private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var cellularContext = "no scan received"

    /** 初始化：创建 logs 目录、app.log 文件，并接管未捕获异常处理器。 */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            logFile = File(dir, TAG_FILE)
            initialized = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { e("Crash", "uncaught in ${thread.name}; cellular=$cellularContext", throwable) }
                prev?.uncaughtException(thread, throwable)
            }
            // Native crashes cannot reach the Java uncaught exception handler.
            logPreviousExit(context)
        } catch (_: Throwable) {
        }
    }

    /** Keep only scan timing and RAT counts, without cell identities or location. */
    @JvmStatic
    fun recordCellularScan(subscriptionId: Int, cells: List<CellInfo>) {
        cellularContext = "elapsedMs=${SystemClock.elapsedRealtime()} sub=$subscriptionId " +
            "LTE=${cells.count { it is CellInfoLte }} NR=${cells.count { it is CellInfoNr }} total=${cells.size}"
    }

    @JvmStatic
    @JvmOverloads
    fun i(tag: String, msg: String, throwable: Throwable? = null) {
        Log.i(tag, msg)
        write("I", tag, msg, throwable)
    }

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg)
        write("W", tag, msg, throwable)
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg)
        write("E", tag, msg, throwable)
    }

    @JvmStatic
    @JvmOverloads
    fun d(tag: String, msg: String, throwable: Throwable? = null) {
        Log.d(tag, msg)
        write("D", tag, msg, throwable)
    }

    /** 生成设备信息文本（版本/机型/API/日志文件路径），供日志上传附带上报。 */
    @JvmStatic
    fun deviceInfo(context: Context): String {
        var versionName: String? = null
        var versionCode = 0L
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pi.versionName
            versionCode = pi.longVersionCode
        } catch (_: Throwable) {
        }
        val path = try { logFile?.path } catch (_: Throwable) { null } ?: "N/A"
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        return buildString {
            append("\n            ========== 设备信息 ==========\n")
            append("            App 版本 : ").append(versionName ?: "?").append(" (").append(versionCode).append(")\n")
            append("            设备     : ").append(Build.BRAND).append(" ").append(Build.MODEL).append("\n")
            append("            Android  : API ").append(Build.VERSION.SDK_INT).append("\n")
            append("            ABI      : ").append(Build.SUPPORTED_ABIS.joinToString()).append("\n")
            append("            Page size: ").append(pageSize).append(" bytes\n")
            append("            日志文件 : ").append(path).append("\n")
            append("            导出时间 : ").append(LocalDateTime.now().format(dateFormat)).append("\n")
            append("            =============================\n")
        }
    }

    private fun logPreviousExit(context: Context) {
        try {
            val exit = context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
                ?: return
            if (exit.reason == ApplicationExitInfo.REASON_CRASH ||
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.reason == ApplicationExitInfo.REASON_ANR ||
                exit.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            ) {
                w("PreviousExit", "time=${exit.timestamp} process=${exit.processName} " +
                    "reason=${exit.reason} status=${exit.status} description=${exit.description}")
            }
        } catch (e: Exception) {
            Log.w("AppLogger", "Could not read previous process exit", e)
        }
    }

    /** 读取当前日志文件内容（供上传），不存在返回 null。 */
    @JvmStatic
    fun readLog(): String? {
        val file = try { logFile } catch (_: Throwable) { null } ?: return null
        return try {
            if (file.exists() && file.length() > 0) file.readText(Charsets.UTF_8) else null
        } catch (_: Throwable) {
            null
        }
    }

    internal fun readBandLockDiagnostics(): List<String> {
        val marker = " I/$BAND_LOCK_TAG: run="
        return readLog()?.lineSequence()?.filter { it.startsWith(marker, startIndex = 18) }
            ?.toList()?.takeLast(240).orEmpty()
    }

    /** 日志文件字节数（供 UI 提示），无则 0。 */
    @JvmStatic
    fun logSize(): Long {
        val file = try { logFile } catch (_: Throwable) { null } ?: return 0L
        return try { if (file.exists()) file.length() else 0L } catch (_: Throwable) { 0L }
    }

    /** 写日志：时间 + 级别/标签 + 消息（+ 异常堆栈），追加到 app.log，超限截断。 */
    @Synchronized
    private fun write(level: String, tag: String, msg: String, throwable: Throwable?) {
        val file = try { logFile } catch (_: Throwable) { null } ?: return
        try {
            val sb = StringBuilder()
            sb.append(LocalDateTime.now().format(dateFormat))
                .append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (throwable != null) {
                sb.append('\n').append(stackToString(throwable))
            }
            sb.append('\n')
            FileOutputStream(file, true).use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            trimIfTooLarge(file)
        } catch (_: Throwable) {
        }
    }

    /** 堆栈转字符串（含 cause 链）。 */
    private fun stackToString(t: Throwable): String {
        val out = StringWriter()
        PrintWriter(out).use { t.printStackTrace(it) }
        return out.toString()
    }

    /** 日志文件超过 512KB 时截断，仅保留末尾 3000 行。 */
    private fun trimIfTooLarge(file: File) {
        try {
            if (file.length() > MAX_LOG_BYTES) {
                val lines = file.readLines(Charsets.UTF_8)
                val kept = lines.takeLast(KEEP_LINES).joinToString("\n") + "\n"
                FileOutputStream(file).use { out ->
                    out.write(kept.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {
        }
    }
}
