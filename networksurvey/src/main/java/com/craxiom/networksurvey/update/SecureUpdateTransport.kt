package com.craxiom.networksurvey.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal object SecureUpdateTransport {
    private const val MAX_REDIRECTS = 5

    fun open(
        value: String,
        connect: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection },
    ): HttpsURLConnection {
        var uri = UpdateSecurity.requireHttpsUrl(value)
        for (redirect in 0..MAX_REDIRECTS) {
            val connection = connect(uri).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 15_000
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Missing update redirect location")
                    if (redirect == MAX_REDIRECTS) throw IOException("Too many update redirects")
                    uri = try {
                        UpdateSecurity.requireHttpsUrl(uri.resolve(location).toString())
                    } catch (_: IllegalArgumentException) {
                        throw UpdateSecurity.Rejected(UpdateSecurity.Failure.UNSAFE_URL)
                    }
                    connection.disconnect()
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException("Update HTTP status $status")
                return connection
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Too many update redirects")
    }

    fun readManifest(
        value: String,
        connect: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection },
    ): String {
        val connection = open(value, connect)
        try {
            if (connection.contentLengthLong > UpdateSecurity.MAX_MANIFEST_BYTES) {
                throw UpdateSecurity.Rejected(UpdateSecurity.Failure.TOO_LARGE)
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) {
                        throw UpdateSecurity.Rejected(UpdateSecurity.Failure.TIMED_OUT)
                    }
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > UpdateSecurity.MAX_MANIFEST_BYTES) {
                        throw UpdateSecurity.Rejected(UpdateSecurity.Failure.TOO_LARGE)
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
