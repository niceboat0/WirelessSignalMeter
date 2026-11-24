package com.craxiom.networksurvey.update

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureUpdateTransportTest {
    @Test
    fun rejectsAnInsecureInitialUrlWithoutOpeningAnyConnection() {
        val routes = Routes(emptyList())

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open("http://updates.example.com/version.json", routes::connect)
        }

        assertTrue(routes.requested.isEmpty())
    }

    @Test
    fun resolvesRelativeRedirectsAgainstTheCurrentRequestPath() {
        val initial = FakeConnection(
            "https://updates.example.com/releases/latest/version.json",
            status = 302,
            location = "../stable/version.json",
        )
        val target = FakeConnection("https://updates.example.com/releases/stable/version.json")
        val routes = Routes(listOf(initial, target))

        val result = SecureUpdateTransport.open(initial.url.toString(), routes::connect)

        assertSame(target, result)
        assertEquals(listOf(initial.url.toString(), target.url.toString()), routes.requested)
        assertEquals(1, initial.disconnectCount)
        assertFalse(initial.bodyOpened)
        assertEquals(0, target.disconnectCount)
        result.disconnect()
    }

    @Test
    fun preservesHttpsForSchemeRelativeRedirects() {
        val initial = FakeConnection(
            START_URL,
            status = 307,
            location = "//cdn.example.com/version.json",
        )
        val target = FakeConnection("https://cdn.example.com/version.json")
        val routes = Routes(listOf(initial, target))

        val result = SecureUpdateTransport.open(START_URL, routes::connect)

        assertSame(target, result)
        assertEquals(listOf(START_URL, target.url.toString()), routes.requested)
        assertEquals(1, initial.disconnectCount)
        result.disconnect()
    }

    @Test
    fun rejectsHttpsDowngradesWithoutConnectingToHttp() {
        val initial = FakeConnection(
            START_URL,
            status = 302,
            location = "http://untrusted.example.com/version.json",
        )
        val routes = Routes(listOf(initial))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(START_URL, routes::connect)
        }

        assertEquals(listOf(START_URL), routes.requested)
        assertEquals(1, initial.disconnectCount)
        assertFalse(initial.bodyOpened)
    }

    @Test
    fun rejectsCredentialsAndFragmentsInRedirectTargets() {
        for (location in listOf(
            "https://user:password@cdn.example.com/version.json",
            "https://cdn.example.com/version.json#fragment",
        )) {
            val initial = FakeConnection(START_URL, status = 303, location = location)
            val routes = Routes(listOf(initial))

            assertThrows(location, IOException::class.java) {
                SecureUpdateTransport.open(START_URL, routes::connect)
            }

            assertEquals(listOf(START_URL), routes.requested)
            assertEquals(1, initial.disconnectCount)
        }
    }

    @Test
    fun rejectsMalformedRedirectLocationsAndDisconnects() {
        val initial = FakeConnection(START_URL, status = 308, location = "https://bad host/version.json")
        val routes = Routes(listOf(initial))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(START_URL, routes::connect)
        }

        assertEquals(listOf(START_URL), routes.requested)
        assertEquals(1, initial.disconnectCount)
    }

    @Test
    fun rejectsRedirectsWithoutALocationAndDisconnects() {
        val initial = FakeConnection(START_URL, status = 302)
        val routes = Routes(listOf(initial))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(START_URL, routes::connect)
        }

        assertEquals(1, initial.disconnectCount)
        assertFalse(initial.bodyOpened)
    }

    @Test
    fun allowsExactlyFiveRedirects() {
        val connections = redirectChain(redirects = 5)
        val routes = Routes(connections)

        val result = SecureUpdateTransport.open(connections.first().url.toString(), routes::connect)

        assertSame(connections.last(), result)
        assertEquals(connections.map { it.url.toString() }, routes.requested)
        assertTrue(connections.dropLast(1).all { it.disconnectCount == 1 })
        assertEquals(0, connections.last().disconnectCount)
        result.disconnect()
    }

    @Test
    fun rejectsASixthRedirectBeforeOpeningItsTarget() {
        val connections = redirectChain(redirects = 6)
        val routes = Routes(connections)

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(connections.first().url.toString(), routes::connect)
        }

        assertEquals(connections.take(6).map { it.url.toString() }, routes.requested)
        assertTrue(connections.take(6).all { it.disconnectCount == 1 })
        assertEquals(0, connections.last().disconnectCount)
        assertFalse(connections.last().bodyOpened)
    }

    @Test
    fun rejectsNonSuccessResponsesAndDisconnects() {
        val connection = FakeConnection(START_URL, status = 404)
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(START_URL, routes::connect)
        }

        assertEquals(1, connection.disconnectCount)
        assertFalse(connection.bodyOpened)
    }

    @Test
    fun disconnectsWhenReadingTheResponseCodeFails() {
        val connection = FakeConnection(START_URL, responseFailure = IOException("Disconnected"))
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.open(START_URL, routes::connect)
        }

        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun rejectsAnOversizedManifestHeaderWithoutOpeningTheBody() {
        val connection = FakeConnection(
            START_URL,
            reportedLength = UpdateSecurity.MAX_MANIFEST_BYTES + 1L,
        )
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.readManifest(START_URL, routes::connect)
        }

        assertEquals(1, connection.disconnectCount)
        assertFalse(connection.bodyOpened)
    }

    @Test
    fun limitsUnknownLengthBodiesAndClosesThemOnFailure() {
        val connection = FakeConnection(
            START_URL,
            input = ByteArrayInputStream(ByteArray(UpdateSecurity.MAX_MANIFEST_BYTES.toInt() + 1)),
            reportedLength = -1L,
        )
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.readManifest(START_URL, routes::connect)
        }

        assertTrue(connection.bodyOpened)
        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun allowsAManifestBodyExactlyAtTheLimit() {
        val bytes = ByteArray(UpdateSecurity.MAX_MANIFEST_BYTES.toInt()) { 'a'.code.toByte() }
        val connection = FakeConnection(START_URL, input = ByteArrayInputStream(bytes))
        val routes = Routes(listOf(connection))

        val result = SecureUpdateTransport.readManifest(START_URL, routes::connect)

        assertEquals(bytes.size, result.length)
        assertTrue(result.all { it == 'a' })
        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun readsUtf8BodiesAndDisablesImplicitRedirectsAndCompression() {
        val manifest = "{\"title\":\"\u4e2d\u6587\",\"versionCode\":121002}"
        val connection = FakeConnection(
            START_URL,
            input = ByteArrayInputStream(manifest.toByteArray(Charsets.UTF_8)),
        )
        val routes = Routes(listOf(connection))

        assertEquals(manifest, SecureUpdateTransport.readManifest(START_URL, routes::connect))

        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(8_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun closesAndDisconnectsWhenReadingTheBodyFails() {
        val brokenInput = object : InputStream() {
            override fun read(): Int = throw IOException("Body unavailable")
        }
        val connection = FakeConnection(START_URL, input = brokenInput)
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.readManifest(START_URL, routes::connect)
        }

        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun disconnectsWhenClosingTheBodyFails() {
        val brokenClose = object : ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)) {
            override fun close() {
                throw IOException("Close failed")
            }
        }
        val connection = FakeConnection(START_URL, input = brokenClose)
        val routes = Routes(listOf(connection))

        assertThrows(IOException::class.java) {
            SecureUpdateTransport.readManifest(START_URL, routes::connect)
        }

        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnectCount)
    }

    private fun redirectChain(redirects: Int): List<FakeConnection> =
        (0..redirects).map { index ->
            FakeConnection(
                "https://updates.example.com/redirect/$index",
                status = if (index < redirects) 302 else 200,
                location = if (index < redirects) (index + 1).toString() else null,
            )
        }

    private class Routes(connections: List<FakeConnection>) {
        private val byUrl = connections.associateBy { it.url.toString() }
        val requested = mutableListOf<String>()

        fun connect(uri: URI): HttpsURLConnection {
            requested.add(uri.toString())
            return checkNotNull(byUrl[uri.toString()]) { "Unexpected connection: $uri" }
        }
    }

    private class TrackingInputStream(input: InputStream) : FilterInputStream(input) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeConnection(
        value: String,
        private val status: Int = 200,
        private val location: String? = null,
        input: InputStream = ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)),
        private val reportedLength: Long = -1L,
        private val responseFailure: IOException? = null,
    ) : HttpsURLConnection(URL(value)) {
        val body = TrackingInputStream(input)
        var bodyOpened = false
            private set
        var disconnectCount = 0
            private set

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getHeaderField(name: String?): String? =
            if ("Location".equals(name, ignoreCase = true)) location else null

        override fun getContentLengthLong(): Long = reportedLength

        override fun getInputStream(): InputStream {
            bodyOpened = true
            return body
        }

        override fun connect() = Unit

        override fun disconnect() {
            disconnectCount++
        }

        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private companion object {
        const val START_URL = "https://updates.example.com/version.json"
    }
}
