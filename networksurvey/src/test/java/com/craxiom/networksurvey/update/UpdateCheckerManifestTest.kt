package com.craxiom.networksurvey.update

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateCheckerManifestTest {
    @Test
    fun rejectsMissingSha256EvenWhenLegacyMd5IsPresent() {
        for (md5 in listOf<String?>(null, "a".repeat(32))) {
            val body = manifest {
                remove("sha256")
                if (md5 != null) put("md5", md5)
            }

            val rejected = assertThrows(UpdateSecurity.Rejected::class.java) {
                UpdateChecker.parseVersion(body, BASE)
            }
            assertEquals(UpdateSecurity.Failure.INVALID_MANIFEST, rejected.failure)
        }
    }

    @Test
    fun rejectsMalformedSha256BeforeReturningARelease() {
        for (hash in listOf("", "a".repeat(32), "a".repeat(63), "z".repeat(64))) {
            val rejected = assertThrows(UpdateSecurity.Rejected::class.java) {
                UpdateChecker.parseVersion(manifest { put("sha256", hash) }, BASE)
            }
            assertEquals(UpdateSecurity.Failure.INVALID_MANIFEST, rejected.failure)
        }
    }

    @Test
    fun rejectsHttpApkEvenWithAValidDigest() {
        val rejected = assertThrows(UpdateSecurity.Rejected::class.java) {
            UpdateChecker.parseVersion(
                manifest { put("apkUrl", "http://cdn.example.com/app.apk") },
                BASE,
            )
        }

        assertEquals(UpdateSecurity.Failure.UNSAFE_URL, rejected.failure)
    }

    @Test
    fun rejectsMissingZeroAndNegativeVersionCodes() {
        val invalidBodies = listOf(
            manifest { remove("versionCode") },
            manifest { put("versionCode", 0L) },
            manifest { put("versionCode", -1L) },
        )

        for (body in invalidBodies) {
            val rejected = assertThrows(UpdateSecurity.Rejected::class.java) {
                UpdateChecker.parseVersion(body, BASE)
            }
            assertEquals(UpdateSecurity.Failure.INVALID_MANIFEST, rejected.failure)
        }
    }

    @Test
    fun preservesLongVersionCodeAndNormalizesDigest() {
        val version = (1L shl 33) + 17L
        val release = UpdateChecker.parseVersion(
            manifest {
                put("versionCode", version)
                put("sha256", SHA256.uppercase())
                put("forceUpdate", true)
            },
            BASE,
        )

        assertEquals(version, release.versionCode)
        assertEquals(SHA256, release.sha256)
        assertTrue(release.forceUpdate)
        assertEquals(BASE, release.source)
    }

    @Test
    fun resolvesApkUrlWithRfcRelativePathSemantics() {
        val locations = mapOf(
            "downloads/app.apk" to "https://updates.example.com/nsrebuild/downloads/app.apk",
            "/downloads/app.apk" to "https://updates.example.com/downloads/app.apk",
            "../downloads/app.apk" to "https://updates.example.com/downloads/app.apk",
            "//cdn.example.com/app.apk?channel=test" to
                    "https://cdn.example.com/app.apk?channel=test",
        )

        for ((location, expected) in locations) {
            val release = UpdateChecker.parseVersion(manifest { put("apkUrl", location) }, BASE)
            assertEquals(location, expected, release.apkUrl)
        }
    }

    @Test
    fun refusesInsecureManifestBaseEvenForAnAbsoluteHttpsApk() {
        val rejected = assertThrows(UpdateSecurity.Rejected::class.java) {
            UpdateChecker.parseVersion(
                manifest { put("apkUrl", "https://cdn.example.com/app.apk") },
                "http://updates.example.com/nsrebuild",
            )
        }

        assertEquals(UpdateSecurity.Failure.UNSAFE_URL, rejected.failure)
    }

    @Test
    fun boundsReleaseDisplayFieldsAndNoteCount() {
        val release = UpdateChecker.parseVersion(
            manifest {
                put("versionName", "v".repeat(300))
                put("title", "t".repeat(600))
                put("publishDate", "d".repeat(300))
                put("releaseNotes", JSONArray((0 until 60).map { "$it:" + "n".repeat(600) }))
            },
            BASE,
        )

        assertEquals("v".repeat(100), release.versionName)
        assertEquals("t".repeat(200), release.title)
        assertEquals("d".repeat(100), release.publishDate)
        assertEquals(50, release.releaseNotes.size)
        assertTrue(release.releaseNotes.all { it.length == 500 })
        assertTrue(release.releaseNotes.first().startsWith("0:"))
        assertTrue(release.releaseNotes.last().startsWith("49:"))
    }

    @Test
    fun absentOptionalMetadataGetsSafeDefaults() {
        val release = UpdateChecker.parseVersion(manifest(), BASE)

        assertEquals("", release.versionName)
        assertEquals("", release.title)
        assertEquals("", release.publishDate)
        assertTrue(release.releaseNotes.isEmpty())
        assertFalse(release.forceUpdate)
    }

    private fun manifest(edit: JSONObject.() -> Unit = {}): String =
        JSONObject()
            .put("versionCode", 121002L)
            .put("apkUrl", "downloads/app.apk")
            .put("sha256", SHA256)
            .apply(edit)
            .toString()

    private companion object {
        const val BASE = "https://updates.example.com/nsrebuild"
        const val SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
