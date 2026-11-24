package com.craxiom.networksurvey.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.craxiom.networksurvey.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Keeps the APK private until transport, digest and installed-app identity checks pass.
 */
object UpdateDownloader {
    private data class ReadyUpdate(val file: File, val version: RemoteVersion)
    @Volatile private var readyUpdate: ReadyUpdate? = null
    private val inProgress = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val RETAIN_MS = 24L * 60 * 60 * 1000

    fun isReady(version: RemoteVersion): Boolean {
        val ready = readyUpdate ?: return false
        return ready.version.versionCode == version.versionCode &&
            ready.version.sha256 == version.sha256 && ready.file.isFile
    }

    /** False keeps the dialog available, including while installation permission is requested. */
    fun download(context: Context, version: RemoteVersion): Boolean {
        val app = context.applicationContext
        try {
            UpdateSecurity.requireHttpsUrl(version.apkUrl)
            UpdateSecurity.requireSha256(version.sha256)
        } catch (_: UpdateSecurity.Rejected) {
            show(app, R.string.update_validation_failed)
            return false
        }
        UpdateChecker.allowDownloadRetry(app)

        if (!app.packageManager.canRequestPackageInstalls()) {
            show(app, R.string.update_install_permission)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (error: RuntimeException) {
                Timber.w(error, "Could not open update installation settings")
                show(app, R.string.update_install_failed)
            }
            return false
        }
        val ready = readyUpdate
        if (ready != null && isReady(version)) {
            try {
                // This path runs only after a foreground user click, never from the download thread.
                install(context, ready.file)
            } catch (error: RuntimeException) {
                Timber.w(error, "Could not open the verified update installer")
                show(app, R.string.update_install_failed)
            }
            return false // Keep the prompt available when the installer is cancelled.
        }
        if (!inProgress.compareAndSet(false, true)) {
            show(app, R.string.update_download_in_progress)
            return false
        }

        show(app, R.string.update_download_started)
        executor.execute { downloadVerified(app, version) }
        return true
    }

    private fun downloadVerified(context: Context, version: RemoteVersion) {
        var partial: File? = null
        var verified: File? = null
        try {
            val directory = File(context.cacheDir, "verified-updates")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
            val staleBefore = System.currentTimeMillis() - RETAIN_MS
            directory.listFiles()?.filter {
                it.name.startsWith("update-") && it.extension in setOf("part", "apk") &&
                    it.lastModified() < staleBefore
            }?.forEach { it.delete() }

            val downloadFile = File.createTempFile("update-", ".part", directory)
            partial = downloadFile
            val connection = SecureUpdateTransport.open(version.apkUrl)
            try {
                if (connection.contentLengthLong > UpdateSecurity.MAX_APK_BYTES) {
                    throw UpdateSecurity.Rejected(UpdateSecurity.Failure.TOO_LARGE)
                }
                connection.inputStream.use { input ->
                    downloadFile.outputStream().use { output ->
                        UpdateSecurity.copyVerified(input, output, version.sha256)
                    }
                }
            } finally {
                connection.disconnect()
            }
            ApkUpdateVerifier.verify(context, downloadFile, version.versionCode)

            val apk = File(directory, downloadFile.name.removeSuffix(".part") + ".apk")
            if (!downloadFile.renameTo(apk)) throw IOException("Cannot finalize verified update")
            partial = null
            verified = apk
            if (!main.post {
                readyUpdate = ReadyUpdate(apk, version)
                inProgress.set(false)
                UpdateChecker.pendingUpdate.value = version
                show(context, R.string.update_ready_to_install)
            }) {
                throw IOException("Could not present the verified update")
            }
            verified = null
        } catch (error: Exception) {
            Timber.w(error, "App update failed")
            show(
                context,
                if (error is UpdateSecurity.Rejected) R.string.update_validation_failed
                else R.string.update_download_failed,
            )
            main.post {
                inProgress.set(false)
                UpdateChecker.pendingUpdate.value = version
            }
        } finally {
            partial?.delete()
            verified?.delete()
        }
    }

    private fun install(context: Context, file: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun show(context: Context, @StringRes message: Int) {
        main.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
