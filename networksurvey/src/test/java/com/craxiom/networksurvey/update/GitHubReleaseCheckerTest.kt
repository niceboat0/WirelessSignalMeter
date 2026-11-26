package com.craxiom.networksurvey.update

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubReleaseCheckerTest {
    @Test
    fun comparesNumericVersionsAndPrefersArm64OverUniversal() {
        val result = parse(release("v1.10.0", assets = listOf(
            asset("app-1.10.0-universal-release.apk", tag = "v1.10.0"),
            asset("app-1.10.0-arm64-v8a-debug.apk", tag = "v1.10.0"),
            asset("app-1.10.0-arm64-v8a-release.apk", tag = "v1.10.0"),
            asset("app-1.10.0-x86_64-release.apk", tag = "v1.10.0"),
        )), "1.2.0") as GitHubReleaseChecker.Result.Available

        assertEquals("1.10.0", result.release.versionName)
        assertEquals(
            "$REPOSITORY/releases/download/v1.10.0/app-1.10.0-arm64-v8a-release.apk",
            result.release.apkUrl,
        )
        assertEquals("$REPOSITORY/releases/tag/v1.10.0", result.release.releasePageUrl)
    }

    @Test
    fun acceptsFourPartVersionsWithoutUsingAbiVersionCode() {
        for (tag in listOf("v1.2.0.1", "v1.3.0+build-1")) {
            assertTrue(parse(release(tag), "1.2.0") is GitHubReleaseChecker.Result.Available)
        }
    }

    @Test
    fun treatsEquivalentAndOlderVersionsAsUpToDate() {
        for ((remote, local) in listOf(
            "v1.2.0" to "1.2.0",
            "v1.2.0.0" to "1.2.0",
            "v1.2.0" to "1.2.1",
            "v1.2.0" to "1.2.0-dev",
            "v1.2.0" to "v1.2.0-debug+local",
        )) {
            assertTrue("$remote / $local", parse(release(remote), local) is GitHubReleaseChecker.Result.UpToDate)
        }
    }

    @Test
    fun ignoresDraftsAndPrereleasesIncludingMistaggedMetadata() {
        val versions = listOf(
            release("v1.3.0").put("draft", true),
            release("v1.3.0").put("prerelease", true),
            release("v1.3.0-rc.1"),
        )
        versions.forEach {
            assertEquals(GitHubReleaseChecker.Result.NoPublishedRelease, parse(it))
        }
    }

    @Test
    fun keepsReleasePageWhenNoCompatibleApkWasPublished() {
        val result = parse(release(assets = emptyList())) as GitHubReleaseChecker.Result.Available
        assertNull(result.release.apkUrl)
        assertEquals("$REPOSITORY/releases/tag/v1.3.0", result.release.releasePageUrl)
    }

    @Test
    fun fallsBackToUniversalAndRejectsDebugUnsignedAndUnfinishedAssets() {
        val result = parse(release(assets = listOf(
            asset("app-arm64-v8a-debug.apk"),
            asset("app-arm64-v8a-dev.apk"),
            asset("app-arm64-v8a-unsigned.apk"),
            asset("app-arm64-v8a-release.apk").put("state", "new"),
            asset("app-arm64-v8a-empty.apk").put("size", 0),
            asset("app-armeabi-v7a-release.apk"),
            asset("app-universal-release.apk"),
        ))) as GitHubReleaseChecker.Result.Available

        assertEquals("$DOWNLOAD/app-universal-release.apk", result.release.apkUrl)
    }

    @Test
    fun refusesReleasePagesOutsideTheExactHttpsRepository() {
        val urls = listOf(
            "http://github.com/niceboat0/WirelessSignalMeter/releases/tag/v1.3.0",
            "https://github.com/other/WirelessSignalMeter/releases/tag/v1.3.0",
            "https://github.com/niceboat0/WirelessSignalMeter-other/releases/tag/v1.3.0",
            "https://github.com.evil.example/niceboat0/WirelessSignalMeter/releases/tag/v1.3.0",
            "https://user@github.com/niceboat0/WirelessSignalMeter/releases/tag/v1.3.0",
            "https://github.com:8443/niceboat0/WirelessSignalMeter/releases/tag/v1.3.0",
            "$REPOSITORY/releases/tag/../../../../other",
            "$REPOSITORY/releases/tag/%2e%2e/%2e%2e/other",
            "$REPOSITORY/releases/tag/v1.3.0?redirect=other",
            "$REPOSITORY/releases/tag/v1.3.0#other",
            "$REPOSITORY/releases/tag/v1.2.1",
        )
        urls.forEach { url ->
            assertEquals(
                url,
                GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.INVALID_RESPONSE),
                parse(release().put("html_url", url)),
            )
        }
    }

    @Test
    fun untrustedAssetLinksDoNotEscapeToAnExternalDownload() {
        val urls = listOf(
            "http://github.com/niceboat0/WirelessSignalMeter/releases/download/v1.3.0/app-arm64.apk",
            "https://github.com/other/app/releases/download/v1.3.0/app-arm64.apk",
            "$REPOSITORY/releases/download/../../../../app-arm64.apk",
            "$DOWNLOAD/app-arm64.apk?redirect=other",
            "$REPOSITORY/releases/download/v1.2.1/app-arm64.apk",
        )
        urls.forEach { url ->
            val result = parse(release(assets = listOf(
                asset("app-arm64.apk").put("browser_download_url", url),
            ))) as GitHubReleaseChecker.Result.Available
            assertNull(url, result.release.apkUrl)
        }
    }

    @Test
    fun rejectsUnrecognizedVersionsAndBoundsReleaseNotes() {
        for (tag in listOf("", "latest", "release-1.3.0", "v1.3.x")) {
            assertFalse(parse(release(tag)) is GitHubReleaseChecker.Result.Available)
        }
        val result = parse(release().put("body", "a".repeat(20_000))) as GitHubReleaseChecker.Result.Available
        assertEquals(12_000, result.release.releaseNotes.length)
        val emptyNotes = parse(release().put("body", JSONObject.NULL)) as GitHubReleaseChecker.Result.Available
        assertEquals("", emptyNotes.release.releaseNotes)
    }

    @Test
    fun mapsMissingReleaseRateLimitAndServerResponses() = runBlocking {
        assertEquals(
            GitHubReleaseChecker.Result.NoPublishedRelease,
            GitHubReleaseChecker.check("1.2.0", responseClient(404)),
        )
        for (code in listOf(403, 429)) {
            assertEquals(
                GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.RATE_LIMITED),
                GitHubReleaseChecker.check("1.2.0", responseClient(code)),
            )
        }
        assertEquals(
            GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.SERVER),
            GitHubReleaseChecker.check("1.2.0", responseClient(503)),
        )
    }

    @Test
    fun malformedAndOversizedMetadataReturnInvalidResponse() = runBlocking {
        for (body in listOf("not json", "a".repeat(UpdateSecurity.MAX_MANIFEST_BYTES.toInt() + 1))) {
            assertEquals(
                GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.INVALID_RESPONSE),
                GitHubReleaseChecker.check("1.2.0", responseClient(200, body)),
            )
        }
    }

    @Test
    fun usesPublicGitHubLatestReleaseWithoutCredentials() = runBlocking {
        val calls = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(
                "https://api.github.com/repos/niceboat0/WirelessSignalMeter/releases/latest",
                request.url.toString(),
            )
            assertEquals("application/vnd.github+json", request.header("Accept"))
            assertEquals("2026-03-10", request.header("X-GitHub-Api-Version"))
            assertNull(request.header("Authorization"))
            response(request, 200, release().toString())
        }.build()

        assertTrue(GitHubReleaseChecker.check("1.2.0", calls) is GitHubReleaseChecker.Result.Available)
    }

    @Test
    fun returnsNetworkFailureWithoutExposingTransportDetails() = runBlocking {
        val calls = OkHttpClient.Builder().addInterceptor {
            throw IOException("Transport details must not become UI text")
        }.build()

        assertEquals(
            GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.NETWORK),
            GitHubReleaseChecker.check("1.2.0", calls),
        )
    }

    @Test
    fun cancellationCancelsTheHttpCallAndPropagates() = runBlocking {
        val enqueued = CompletableDeferred<Unit>()
        val call = WaitingCall(enqueued)
        var returned = false
        val job = launch {
            GitHubReleaseChecker.check("1.2.0", Call.Factory { call })
            returned = true
        }
        enqueued.await()
        job.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    private fun parse(json: JSONObject, current: String = "1.2.0") =
        GitHubReleaseChecker.parseRelease(json.toString(), current)

    private fun release(tag: String = "v1.3.0", assets: List<JSONObject> = listOf(asset("app-arm64-v8a-release.apk"))) =
        JSONObject()
            .put("tag_name", tag)
            .put("html_url", "$REPOSITORY/releases/tag/$tag")
            .put("draft", false)
            .put("prerelease", false)
            .put("body", "Release notes")
            .put("published_at", "2026-11-25T00:00:00Z")
            .put("assets", JSONArray(assets))

    private fun asset(name: String, tag: String = "v1.3.0") = JSONObject()
        .put("name", name)
        .put("state", "uploaded")
        .put("size", 25_000_000L)
        .put("browser_download_url", "$REPOSITORY/releases/download/$tag/$name")

    private fun responseClient(code: Int, body: String = "") =
        OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), code, body) }.build()

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test response")
        .body(body.toResponseBody())
        .build()

    private class WaitingCall(private val enqueued: CompletableDeferred<Unit>) : Call {
        private var cancelled = false
        private var callback: Callback? = null
        override fun request(): Request = Request.Builder().url("https://api.github.com/").build()
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.complete(Unit)
        }
        override fun cancel() {
            cancelled = true
            callback?.onFailure(this, IOException("Cancelled"))
        }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = WaitingCall(enqueued)
    }

    private companion object {
        const val REPOSITORY = "https://github.com/niceboat0/WirelessSignalMeter"
        const val DOWNLOAD = "$REPOSITORY/releases/download/v1.3.0"
    }
}
