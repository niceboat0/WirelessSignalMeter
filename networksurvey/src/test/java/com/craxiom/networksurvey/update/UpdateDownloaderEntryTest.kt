package com.craxiom.networksurvey.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.provider.Settings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloaderEntryTest {
    private lateinit var application: Application
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        preferences = application.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putLong("last_check_ts", LAST_CHECK)
            .putLong("ignored_version_code", 77L)
            .commit()
    }

    @After
    fun drainUiNotifications() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun httpApkIsRejectedBeforePermissionSettingsOrRetryStateChange() {
        val started = UpdateDownloader.download(
            application,
            version(apkUrl = "http://updates.example.com/app.apk"),
        )

        assertFalse(started)
        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(LAST_CHECK, preferences.getLong("last_check_ts", 0L))
        assertNoDownloadStarted()
    }

    @Test
    fun malformedDigestIsRejectedBeforePermissionSettingsOrRetryStateChange() {
        val started = UpdateDownloader.download(application, version(sha256 = "a".repeat(32)))

        assertFalse(started)
        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(LAST_CHECK, preferences.getLong("last_check_ts", 0L))
        assertNoDownloadStarted()
    }

    @Test
    fun missingInstallPermissionOpensOnlyCurrentPackageSettingsAndKeepsRetryAvailable() {
        val started = UpdateDownloader.download(application, version())

        assertFalse(started)
        val settingsIntent = shadowOf(application).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settingsIntent?.action)
        assertEquals("package:${application.packageName}", settingsIntent?.dataString)
        assertTrue((settingsIntent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertNull(shadowOf(application).nextStartedActivity)
        assertFalse(preferences.contains("last_check_ts"))
        assertEquals(77L, preferences.getLong("ignored_version_code", 0L))
        assertNoDownloadStarted()
    }

    @Test
    fun unavailablePermissionSettingsStillReturnsWithoutDownloadingAndAllowsRetry() {
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("No package-install settings activity")
            }
        }

        assertFalse(UpdateDownloader.download(context, version()))
        assertNull(shadowOf(application).nextStartedActivity)
        assertFalse(preferences.contains("last_check_ts"))
        assertNoDownloadStarted()
    }

    private fun assertNoDownloadStarted() {
        assertNull(shadowOf(application).nextStartedService)
        assertFalse(File(application.cacheDir, "verified-updates").exists())
    }

    private fun version(
        apkUrl: String = "https://updates.example.com/app.apk",
        sha256: String = SHA256,
    ) = RemoteVersion(
        versionCode = 121002L,
        versionName = "test",
        apkUrl = apkUrl,
        sha256 = sha256,
        forceUpdate = false,
        title = "",
        releaseNotes = emptyList(),
        publishDate = "",
    )

    private companion object {
        const val LAST_CHECK = 123456789L
        const val SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
