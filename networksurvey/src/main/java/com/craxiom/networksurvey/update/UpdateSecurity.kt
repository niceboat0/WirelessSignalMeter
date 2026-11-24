package com.craxiom.networksurvey.update

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object UpdateSecurity {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024L * 1024

    enum class Failure {
        UNSAFE_URL, INVALID_MANIFEST, HASH_MISMATCH, PACKAGE_MISMATCH,
        VERSION_MISMATCH, SIGNATURE_MISMATCH, TOO_LARGE, TIMED_OUT,
    }

    class Rejected(val failure: Failure) : IOException(failure.name)

    data class ApkIdentity(
        val packageName: String,
        val versionCode: Long,
        val signerDigests: Set<String>,
    )

    fun requireHttpsUrl(value: String): URI {
        val uri = try {
            URI(value).parseServerAuthority()
        } catch (_: URISyntaxException) {
            throw Rejected(Failure.UNSAFE_URL)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) throw Rejected(Failure.UNSAFE_URL)
        return uri
    }

    fun resolveApkUrl(base: String, value: String): String {
        if (value.isBlank()) throw Rejected(Failure.UNSAFE_URL)
        val resolved = try {
            requireHttpsUrl(base.trimEnd('/') + "/").resolve(value)
        } catch (_: IllegalArgumentException) {
            throw Rejected(Failure.UNSAFE_URL)
        }
        return requireHttpsUrl(resolved.toString()).toString()
    }

    fun requireSha256(value: String): String {
        if (!value.matches(Regex("[0-9a-fA-F]{64}"))) throw Rejected(Failure.INVALID_MANIFEST)
        return value.lowercase(Locale.ROOT)
    }

    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedSha256: String,
        maxBytes: Long = MAX_APK_BYTES,
    ): Long {
        val expected = requireSha256(expectedSha256)
        val digest = MessageDigest.getInstance("SHA-256")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) {
                throw Rejected(Failure.TIMED_OUT)
            }
            val count = input.read(buffer)
            if (count == -1) break
            if (count.toLong() > maxBytes - total) throw Rejected(Failure.TOO_LARGE)
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            total += count
        }
        if (total == 0L || !MessageDigest.isEqual(
                digest.digest(),
                expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
        ) throw Rejected(Failure.HASH_MISMATCH)
        return total
    }

    fun verifyIdentity(installed: ApkIdentity, archive: ApkIdentity, expectedVersion: Long) {
        if (archive.packageName != installed.packageName) throw Rejected(Failure.PACKAGE_MISMATCH)
        if (archive.versionCode != expectedVersion || archive.versionCode <= installed.versionCode) {
            throw Rejected(Failure.VERSION_MISMATCH)
        }
        if (installed.signerDigests.isEmpty() || archive.signerDigests != installed.signerDigests) {
            throw Rejected(Failure.SIGNATURE_MISMATCH)
        }
    }
}
