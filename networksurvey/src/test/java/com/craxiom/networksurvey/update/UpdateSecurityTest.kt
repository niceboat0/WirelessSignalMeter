package com.craxiom.networksurvey.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSecurityTest {
    @Test
    fun acceptsHttpsUrlsWithQueriesAndValidPorts() {
        val url = "https://updates.example.com/app.apk?token=abc%2Fdef"
        assertEquals(URI(url), UpdateSecurity.requireHttpsUrl(url))
        assertEquals(443, UpdateSecurity.requireHttpsUrl("https://updates.example.com:443/app.apk").port)
        assertEquals(8443, UpdateSecurity.requireHttpsUrl("https://updates.example.com:8443/app.apk").port)
    }

    @Test
    fun rejectsNonHttpsSchemes() {
        for (url in listOf(
            "http://updates.example.com/app.apk",
            "file:///tmp/app.apk",
            "ftp://updates.example.com/app.apk",
            "content://downloads/app.apk",
            "//updates.example.com/app.apk",
        )) {
            assertThrows(url, IOException::class.java) {
                UpdateSecurity.requireHttpsUrl(url)
            }
        }
    }

    @Test
    fun rejectsMissingHostsAndInvalidPorts() {
        for (url in listOf(
            "",
            "https://",
            "https:/app.apk",
            "https:///app.apk",
            "https://updates.example.com:0/app.apk",
            "https://updates.example.com:65536/app.apk",
            "https://updates.example.com:invalid/app.apk",
        )) {
            assertThrows(url, IOException::class.java) {
                UpdateSecurity.requireHttpsUrl(url)
            }
        }
    }

    @Test
    fun rejectsCredentialsAndFragmentsIncludingEmptyOnes() {
        for (url in listOf(
            "https://user:password@updates.example.com/app.apk",
            "https://user@updates.example.com/app.apk",
            "https://@updates.example.com/app.apk",
            "https://updates.example.com/app.apk#download",
            "https://updates.example.com/app.apk#",
        )) {
            assertThrows(url, IOException::class.java) {
                UpdateSecurity.requireHttpsUrl(url)
            }
        }
    }

    @Test
    fun resolvesApkPathsUsingRfcDirectoryAndRootSemantics() {
        assertEquals(
            "https://updates.example.com/nsrebuild/downloads/app.apk",
            UpdateSecurity.resolveApkUrl(BASE_URL, "downloads/app.apk"),
        )
        assertEquals(
            "https://updates.example.com/nsrebuild/downloads/app.apk",
            UpdateSecurity.resolveApkUrl("$BASE_URL/", "downloads/app.apk"),
        )
        assertEquals(
            "https://updates.example.com/downloads/app.apk",
            UpdateSecurity.resolveApkUrl(BASE_URL, "/downloads/app.apk"),
        )
        assertEquals(
            "https://updates.example.com/downloads/app.apk",
            UpdateSecurity.resolveApkUrl(BASE_URL, "../downloads/app.apk"),
        )
    }

    @Test
    fun resolvesAbsoluteAndSchemeRelativeHttpsLocations() {
        val target = "https://cdn.example.com/releases/app.apk?token=abc"
        assertEquals(target, UpdateSecurity.resolveApkUrl(BASE_URL, target))
        assertEquals(
            target,
            UpdateSecurity.resolveApkUrl(BASE_URL, "//cdn.example.com/releases/app.apk?token=abc"),
        )
    }

    @Test
    fun rejectsInsecureBasesAndResolvedLocations() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.resolveApkUrl("http://updates.example.com/nsrebuild", "app.apk")
        }
        for (location in listOf(
            "http://cdn.example.com/app.apk",
            "file:///tmp/app.apk",
            "https://user:password@cdn.example.com/app.apk",
            "https://cdn.example.com/app.apk#download",
        )) {
            assertThrows(location, IOException::class.java) {
                UpdateSecurity.resolveApkUrl(BASE_URL, location)
            }
        }
    }

    @Test
    fun normalizesValidSha256ToLowercase() {
        assertEquals(ABC_SHA256, UpdateSecurity.requireSha256(ABC_SHA256))
        assertEquals(ABC_SHA256, UpdateSecurity.requireSha256(ABC_SHA256.uppercase()))
    }

    @Test
    fun rejectsMissingAndMalformedSha256() {
        for (hash in listOf(
            "",
            " ",
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
            "sha256:$ABC_SHA256",
        )) {
            assertThrows(hash, IOException::class.java) {
                UpdateSecurity.requireSha256(hash)
            }
        }
    }

    @Test
    fun copiesVerifiedBytesAndReturnsTheirActualCount() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()

        val count = UpdateSecurity.copyVerified(ByteArrayInputStream(bytes), output, ABC_SHA256)

        assertEquals(3L, count)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun allowsAFileExactlyAtTheSizeLimit() {
        val output = ByteArrayOutputStream()

        assertEquals(
            3L,
            UpdateSecurity.copyVerified(
                ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
                output,
                ABC_SHA256,
                maxBytes = 3L,
            ),
        )
        assertEquals("abc", output.toString("UTF-8"))
    }

    @Test
    fun rejectsEmptyApksEvenWhenTheirDigestMatches() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(
                ByteArrayInputStream(byteArrayOf()),
                output,
                EMPTY_SHA256,
                maxBytes = 1L,
            )
        }
        assertEquals(0, output.size())
    }

    @Test
    fun verifiesAllChunksOfAMultiBufferStream() {
        val bytes = ByteArray(1_000_000) { 'a'.code.toByte() }
        val output = ByteArrayOutputStream()

        val count = UpdateSecurity.copyVerified(
            ByteArrayInputStream(bytes),
            output,
            MILLION_A_SHA256,
        )

        assertEquals(bytes.size.toLong(), count)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun rejectsStreamsExceedingTheLimitWithoutWritingPastIt() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(
                ByteArrayInputStream("abcd".toByteArray(Charsets.UTF_8)),
                output,
                ABC_SHA256,
                maxBytes = 3L,
            )
        }
        assertTrue(output.size() <= 3)
    }

    @Test
    fun rejectsDigestMismatchEvenWhenTheLengthMatches() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(
                ByteArrayInputStream("abd".toByteArray(Charsets.UTF_8)),
                ByteArrayOutputStream(),
                ABC_SHA256,
                maxBytes = 3L,
            )
        }
    }

    @Test
    fun neverTreatsAMissingDigestAsAnUncheckedCopy() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(
                ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
                ByteArrayOutputStream(),
                "",
            )
        }
    }

    @Test
    fun propagatesInputAndOutputFailures() {
        val brokenInput = object : InputStream() {
            override fun read(): Int = throw IOException("Input unavailable")
        }
        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(brokenInput, ByteArrayOutputStream(), ABC_SHA256)
        }

        val brokenOutput = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("Output unavailable")
            }
        }
        assertThrows(IOException::class.java) {
            UpdateSecurity.copyVerified(
                ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
                brokenOutput,
                ABC_SHA256,
            )
        }
    }

    @Test
    fun acceptsTheExpectedUpgradeWithTheInstalledSigner() {
        UpdateSecurity.verifyIdentity(identity(), identity(versionCode = NEXT_VERSION), NEXT_VERSION)
    }

    @Test
    fun comparesFullLongVersionCodesWithoutIntegerTruncation() {
        UpdateSecurity.verifyIdentity(
            identity(versionCode = 3_000_000_000L),
            identity(versionCode = 3_000_000_001L),
            3_000_000_001L,
        )
    }

    @Test
    fun rejectsAnApkForAnotherPackage() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.verifyIdentity(
                identity(),
                identity(packageName = "com.example.unrelated", versionCode = NEXT_VERSION),
                NEXT_VERSION,
            )
        }
    }

    @Test
    fun rejectsSameVersionAndRollbackApks() {
        for (versionCode in listOf(INSTALLED_VERSION, INSTALLED_VERSION - 1L)) {
            assertThrows(IOException::class.java) {
                UpdateSecurity.verifyIdentity(identity(), identity(versionCode = versionCode), versionCode)
            }
        }
    }

    @Test
    fun rejectsAnUpgradeDifferentFromTheSelectedRelease() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.verifyIdentity(
                identity(),
                identity(versionCode = NEXT_VERSION),
                NEXT_VERSION + 1L,
            )
        }
    }

    @Test
    fun rejectsDifferentSigningCertificates() {
        assertThrows(IOException::class.java) {
            UpdateSecurity.verifyIdentity(
                identity(),
                identity(versionCode = NEXT_VERSION, signerDigests = setOf(SIGNER_B)),
                NEXT_VERSION,
            )
        }
    }

    @Test
    fun rejectsEmptySignerSetsIncludingTwoMatchingEmptySets() {
        for ((installedSigners, archiveSigners) in listOf(
            emptySet<String>() to setOf(SIGNER_A),
            setOf(SIGNER_A) to emptySet<String>(),
            emptySet<String>() to emptySet<String>(),
        )) {
            assertThrows(IOException::class.java) {
                UpdateSecurity.verifyIdentity(
                    identity(signerDigests = installedSigners),
                    identity(versionCode = NEXT_VERSION, signerDigests = archiveSigners),
                    NEXT_VERSION,
                )
            }
        }
    }

    @Test
    fun acceptsTheSameMultipleSignersRegardlessOfOrder() {
        UpdateSecurity.verifyIdentity(
            identity(signerDigests = linkedSetOf(SIGNER_A, SIGNER_B)),
            identity(versionCode = NEXT_VERSION, signerDigests = linkedSetOf(SIGNER_B, SIGNER_A)),
            NEXT_VERSION,
        )
    }

    @Test
    fun rejectsPartialAndOverlappingSignerSets() {
        for ((installedSigners, archiveSigners) in listOf(
            setOf(SIGNER_A, SIGNER_B) to setOf(SIGNER_A),
            setOf(SIGNER_A) to setOf(SIGNER_A, SIGNER_B),
            setOf(SIGNER_A, SIGNER_B) to setOf(SIGNER_B, SIGNER_C),
        )) {
            assertThrows(IOException::class.java) {
                UpdateSecurity.verifyIdentity(
                    identity(signerDigests = installedSigners),
                    identity(versionCode = NEXT_VERSION, signerDigests = archiveSigners),
                    NEXT_VERSION,
                )
            }
        }
    }

    private fun identity(
        packageName: String = PACKAGE_NAME,
        versionCode: Long = INSTALLED_VERSION,
        signerDigests: Set<String> = setOf(SIGNER_A),
    ) = UpdateSecurity.ApkIdentity(packageName, versionCode, signerDigests)

    private companion object {
        const val BASE_URL = "https://updates.example.com/nsrebuild"
        const val PACKAGE_NAME = "com.wireless.signalmeter.new"
        const val INSTALLED_VERSION = 120002L
        const val NEXT_VERSION = 121002L
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val MILLION_A_SHA256 = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        val SIGNER_A = "1".repeat(64)
        val SIGNER_B = "2".repeat(64)
        val SIGNER_C = "3".repeat(64)
    }
}
