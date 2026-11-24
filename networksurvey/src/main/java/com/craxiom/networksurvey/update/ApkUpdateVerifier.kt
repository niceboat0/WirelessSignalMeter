package com.craxiom.networksurvey.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

internal object ApkUpdateVerifier {
    fun verify(context: Context, file: File, expectedVersion: Long) {
        val manager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateSecurity.Rejected(UpdateSecurity.Failure.PACKAGE_MISMATCH)
        UpdateSecurity.verifyIdentity(identity(installed), identity(archive), expectedVersion)
    }

    private fun identity(info: PackageInfo) = UpdateSecurity.ApkIdentity(
        info.packageName,
        info.longVersionCode,
        info.signingInfo?.apkContentsSigners?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }?.toSet().orEmpty(),
    )
}
