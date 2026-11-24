package com.craxiom.networksurvey.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.craxiom.networksurvey.R
import org.json.JSONObject

/**
 * Update metadata is trusted only after an HTTPS request with platform TLS validation.
 * Update downloads never fall back to the legacy HTTP service used by other features.
 */
data class RemoteVersion(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val forceUpdate: Boolean,
    val title: String,
    val releaseNotes: List<String>,
    val publishDate: String,
    val source: String = ""
)

object UpdateChecker {
    // Legacy service discovery is shared by speed nodes and diagnostic log upload.
    const val PRIMARY_URL = "http://47.108.209.162:9000/nsrebuild"
    private val BACKUP_URLS: List<String> = listOf("http://rsrp.cn:9000/nsrebuild")
    private val SECURE_UPDATE_URLS = listOf("https://rsrp.cn/nsrebuild")

    private val VERSION_PATHS = listOf("/api/version", "/version.json")
    internal const val LOG_PATH = "/api/log"
    private const val PREFS = "update_check_prefs"
    private const val KEY_LAST = "last_check_ts"
    private const val KEY_IGNORED = "ignored_version_code"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000 // 每天最多检查一次（手动/强提醒时 force=true 跳过）

    /** 供 Compose 读取以弹窗（新版本到来时置位，自动检测用） */
    val pendingUpdate = mutableStateOf<RemoteVersion?>(null)

    /** 手动检测（设置页）的 UI 状态机 */
    sealed interface CheckUiState {
        data object Idle : CheckUiState
        data object Checking : CheckUiState
        data object UpToDate : CheckUiState
        data class HasUpdate(val rv: RemoteVersion) : CheckUiState
        data class Error(val msg: String) : CheckUiState
    }
    val checkState = mutableStateOf<CheckUiState>(CheckUiState.Idle)

    /** Legacy endpoints for speed nodes and log upload only; never use for app updates. */
    internal fun candidateUrls(): List<String> {
        val set = LinkedHashSet<String>()
        set.add(PRIMARY_URL.trimEnd('/'))
        BACKUP_URLS.forEach { if (it.isNotBlank()) set.add(it.trimEnd('/')) }
        return set.toList()
    }

    fun check(context: Context, force: Boolean = false, manual: Boolean = false) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong(KEY_LAST, 0) < CHECK_INTERVAL_MS) return
        sp.edit().putLong(KEY_LAST, now).apply()

        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                if (manual) main.post { checkState.value = CheckUiState.Checking }
                val currentVersion = getCurrentVersionCode(context)
                var updated: RemoteVersion? = null
                var anySuccess = false
                outer@ for (base in SECURE_UPDATE_URLS) {
                    for (p in VERSION_PATHS) {
                        when (val r = fetchVersion(base, p, currentVersion)) {
                            is FetchResult.Updated -> { updated = r.rv; break@outer }
                            // 该路径版本不比本地新：继续试下一个路径/服务器（可能静态清单更新）
                            is FetchResult.UpToDate -> anySuccess = true
                            is FetchResult.Fail -> { /* 试下一个 */ }
                        }
                    }
                }
                when {
                    updated != null -> {
                        if (manual) {
                            main.post { checkState.value = CheckUiState.HasUpdate(updated) }
                        } else if (!isIgnored(context, updated.versionCode)) {
                            main.post { pendingUpdate.value = updated }
                        }
                        // 自动检测命中但已被"忽略此版本"：保持静默
                    }
                    anySuccess -> {
                        if (manual) main.post { checkState.value = CheckUiState.UpToDate }
                        // 自动检测：已最新则静默
                    }
                    else -> {
                        if (manual) main.post {
                            checkState.value = CheckUiState.Error(context.getString(R.string.update_secure_service_unavailable))
                        }
                        // 自动检测：全部失败则静默
                    }
                }
            } catch (_: Exception) {
                if (manual) main.post {
                    checkState.value = CheckUiState.Error(context.getString(R.string.update_secure_service_unavailable))
                }
            }
        }.start()
    }

    /** 从单个 baseUrl + path 拉取并解析版本；按结果分类 */
    private fun fetchVersion(baseUrl: String, path: String, currentVersion: Long): FetchResult {
        return try {
            val version = parseVersion(SecureUpdateTransport.readManifest(baseUrl + path), baseUrl)
            if (version.versionCode <= currentVersion) FetchResult.UpToDate
            else FetchResult.Updated(version)
        } catch (_: Exception) {
            FetchResult.Fail
        }
    }

    internal fun parseVersion(body: String, baseUrl: String): RemoteVersion {
        val json = JSONObject(body)
        val code = json.optLong("versionCode", 0L)
        if (code <= 0L) throw UpdateSecurity.Rejected(UpdateSecurity.Failure.INVALID_MANIFEST)
        val hash = UpdateSecurity.requireSha256(json.optString("sha256", ""))
        val url = UpdateSecurity.resolveApkUrl(baseUrl, json.optString("apkUrl", ""))
        val notes = json.optJSONArray("releaseNotes")
        return RemoteVersion(
            versionCode = code,
            versionName = json.optString("versionName", "").take(100),
            apkUrl = url,
            sha256 = hash,
            forceUpdate = json.optBoolean("forceUpdate", false),
            title = json.optString("title", "").take(200),
            releaseNotes = if (notes == null) emptyList() else
                (0 until minOf(notes.length(), 50)).map { notes.optString(it, "").take(500) },
            publishDate = json.optString("publishDate", "").take(100),
            source = baseUrl,
        )
    }

    private sealed class FetchResult {
        data class Updated(val rv: RemoteVersion) : FetchResult()
        data object UpToDate : FetchResult()
        data object Fail : FetchResult()
    }

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.longVersionCode
        } catch (_: Exception) { 0L }
    }

    /** 忽略某个版本（自动弹窗不再提示，直到该版本被更新或用户手动检测） */
    fun ignoreVersion(context: Context, versionCode: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_IGNORED, versionCode).apply()
    }

    /** A user-requested download must remain retryable after the process is interrupted. */
    internal fun allowDownloadRetry(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST).apply()
    }

    private fun isIgnored(context: Context, versionCode: Long): Boolean =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all[KEY_IGNORED] as? Number)?.toLong() == versionCode
}
