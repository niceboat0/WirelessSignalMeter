package com.craxiom.networksurvey.update

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

data class GitHubRelease(
    val versionName: String,
    val releasePageUrl: String,
    val apkUrl: String?,
    val releaseNotes: String,
    val publishedAt: String,
)

/** Manual release discovery; APK links are opened by the user outside the legacy installer. */
object GitHubReleaseChecker {
    const val RELEASES_URL = "https://github.com/niceboat0/WirelessSignalMeter/releases"
    const val ISSUES_URL = "https://github.com/niceboat0/WirelessSignalMeter/issues"
    private const val API_URL =
        "https://api.github.com/repos/niceboat0/WirelessSignalMeter/releases/latest"
    private const val REPOSITORY_PATH = "/niceboat0/WirelessSignalMeter/"
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){0,3}")
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    sealed interface Result {
        data class Available(val release: GitHubRelease) : Result
        data class UpToDate(val versionName: String) : Result
        data object NoPublishedRelease : Result
        data class Error(val reason: Failure) : Result
    }

    enum class Failure { NETWORK, RATE_LIMITED, SERVER, INVALID_RESPONSE }

    suspend fun check(currentVersionName: String): Result = check(currentVersionName, client)

    internal suspend fun check(currentVersionName: String, calls: Call.Factory): Result {
        if (versionParts(currentVersionName, local = true) == null) {
            return Result.Error(Failure.INVALID_RESPONSE)
        }
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "WirelessSignalMeter-Android")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(Result.Error(Failure.NETWORK))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            when (it.code) {
                                200 -> parseRelease(readBody(it), currentVersionName)
                                404 -> Result.NoPublishedRelease
                                403, 429 -> Result.Error(Failure.RATE_LIMITED)
                                else -> Result.Error(Failure.SERVER)
                            }
                        }
                    } catch (_: JSONException) {
                        Result.Error(Failure.INVALID_RESPONSE)
                    } catch (_: IllegalArgumentException) {
                        Result.Error(Failure.INVALID_RESPONSE)
                    } catch (_: IOException) {
                        Result.Error(Failure.NETWORK)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: throw IllegalArgumentException("Missing release metadata")
        require(body.contentLength() <= UpdateSecurity.MAX_MANIFEST_BYTES) {
            "Release metadata is too large"
        }
        val source = body.source()
        require(!source.request(UpdateSecurity.MAX_MANIFEST_BYTES + 1)) {
            "Release metadata is too large"
        }
        return source.readUtf8()
    }

    internal fun parseRelease(body: String, currentVersionName: String): Result {
        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            return Result.NoPublishedRelease
        }
        val tag = json.optString("tag_name").trim()
        if (tag.length > 100) return Result.Error(Failure.INVALID_RESPONSE)
        // A mistagged prerelease must not be offered as a stable update.
        if ('-' in tag.substringBefore('+')) {
            return if (versionParts(tag.substringBefore('-')) != null) Result.NoPublishedRelease
            else Result.Error(Failure.INVALID_RESPONSE)
        }
        val remote = versionParts(tag) ?: return Result.Error(Failure.INVALID_RESPONSE)
        val local = versionParts(currentVersionName, local = true)
            ?: return Result.Error(Failure.INVALID_RESPONSE)
        val page = repositoryUrl(json.optString("html_url"), "releases/tag/")
            ?.takeIf { URI(it).path == REPOSITORY_PATH + "releases/tag/" + tag }
            ?: return Result.Error(Failure.INVALID_RESPONSE)
        val versionName = tag.removePrefix("v").removePrefix("V")
        if (compare(remote, local) <= 0) return Result.UpToDate(versionName)

        val assets = json.optJSONArray("assets")
        val candidates = if (assets == null) emptyList() else
            (0 until assets.length()).mapNotNull { index ->
                val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                if (asset.optString("state") != "uploaded") return@mapNotNull null
                val size = asset.optLong("size", 0)
                if (size !in 1..UpdateSecurity.MAX_APK_BYTES) return@mapNotNull null
                val originalName = asset.optString("name")
                val name = originalName.lowercase(Locale.ROOT)
                val rank = apkRank(name) ?: return@mapNotNull null
                val url = repositoryUrl(asset.optString("browser_download_url"), "releases/download/")
                    ?.takeIf {
                        URI(it).path == REPOSITORY_PATH + "releases/download/" + tag + "/" + originalName
                    }
                    ?: return@mapNotNull null
                Triple(rank, name, url)
            }
        val apk = candidates.sortedWith(compareBy({ it.first }, { it.second })).firstOrNull()?.third
        return Result.Available(
            GitHubRelease(
                versionName = versionName,
                releasePageUrl = page,
                apkUrl = apk,
                releaseNotes = if (json.isNull("body")) "" else json.optString("body").take(12_000),
                publishedAt = if (json.isNull("published_at")) "" else json.optString("published_at").take(100),
            ),
        )
    }

    private fun apkRank(name: String): Int? {
        if (!name.endsWith(".apk")) return null
        val tokens = name.split('-', '_', '.', ' ')
        if (tokens.any { it in setOf("debug", "dev", "androidtest", "unsigned", "unaligned") }) {
            return null
        }
        return when {
            "arm64" in tokens || "aarch64" in tokens -> 0
            "universal" in tokens -> 1
            else -> null
        }
    }

    private fun repositoryUrl(value: String, suffix: String): String? {
        val uri = try {
            UpdateSecurity.requireHttpsUrl(value)
        } catch (_: UpdateSecurity.Rejected) {
            return null
        }
        if (!uri.host.equals("github.com", ignoreCase = true) ||
            uri.port !in setOf(-1, 443) || uri.rawQuery != null ||
            uri.path.split('/').any { it == "." || it == ".." } ||
            !uri.rawPath.startsWith(REPOSITORY_PATH + suffix) ||
            uri.rawPath.length <= (REPOSITORY_PATH + suffix).length
        ) return null
        return uri.toString()
    }

    private fun versionParts(value: String, local: Boolean = false): List<BigInteger>? {
        if (value.length > 100) return null
        var version = value.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (local) version = version.substringBefore('-')
        if (!versionPattern.matches(version)) return null
        return version.split('.').map { it.toBigInteger() }
    }

    private fun compare(left: List<BigInteger>, right: List<BigInteger>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val order = left.getOrElse(index) { BigInteger.ZERO }
                .compareTo(right.getOrElse(index) { BigInteger.ZERO })
            if (order != 0) return order
        }
        return 0
    }
}
