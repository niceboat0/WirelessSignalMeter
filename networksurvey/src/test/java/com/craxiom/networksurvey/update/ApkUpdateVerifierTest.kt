package com.craxiom.networksurvey.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Exercises package metadata and current-signer extraction with controlled package-manager results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkUpdateVerifierTest {
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var candidate: File
    private lateinit var installedPackage: PackageInfo

    @Before
    fun setUp() {
        context = mock()
        packageManager = mock()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn(PACKAGE_NAME)
        candidate = File("candidate-update.part").absoluteFile
        installedPackage = PackageInfo().apply {
            packageName = PACKAGE_NAME
            setLongVersionCode(INSTALLED_VERSION)
            signingInfo = signers(SIGNER_A)
        }
        whenever(packageManager.getPackageInfo(eq(PACKAGE_NAME), any<PackageManager.PackageInfoFlags>()))
            .thenAnswer { installedPackage }
    }

    @Test
    fun acceptsMatchingPackageAndCurrentSignerWithLongVersionCode() {
        archive()

        ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
    }

    @Test
    fun rejectsAnotherPackageEvenWithMatchingSignerAndVersion() {
        archive(packageName = "com.example.unrelated")

        assertRejected(UpdateSecurity.Failure.PACKAGE_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    @Test
    fun rejectsPackageVersionDifferentFromSelectedRelease() {
        archive(versionCode = NEXT_VERSION + 1)

        assertRejected(UpdateSecurity.Failure.VERSION_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    @Test
    fun rejectsRollbackEvenWhenManifestAndArchiveVersionsAgree() {
        archive(versionCode = INSTALLED_VERSION - 1)

        assertRejected(UpdateSecurity.Failure.VERSION_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, INSTALLED_VERSION - 1)
        }
    }

    @Test
    fun rejectsDifferentCurrentSigningCertificate() {
        archive(signingInfo = signers(SIGNER_B))

        assertRejected(UpdateSecurity.Failure.SIGNATURE_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    @Test
    fun sharedSigningHistoryDoesNotAuthorizeAnotherCurrentSigner() {
        installed().signingInfo = signers(SIGNER_A).also {
            shadowOf(it).setPastSigningCertificates(arrayOf(SIGNER_OLD))
        }
        archive(signingInfo = signers(SIGNER_B).also {
            shadowOf(it).setPastSigningCertificates(arrayOf(SIGNER_OLD))
        })

        assertRejected(UpdateSecurity.Failure.SIGNATURE_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    @Test
    fun missingSigningInfoOnBothPackagesStillFailsClosed() {
        installed().signingInfo = null
        archive(signingInfo = null)

        assertRejected(UpdateSecurity.Failure.SIGNATURE_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    @Test
    fun acceptsSameMultipleCurrentSignersInEitherOrder() {
        installed().signingInfo = signers(SIGNER_A, SIGNER_B)
        archive(signingInfo = signers(SIGNER_B, SIGNER_A))

        ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
    }

    @Test
    fun missingArchiveMetadataFailsClosed() {
        assertRejected(UpdateSecurity.Failure.PACKAGE_MISMATCH) {
            ApkUpdateVerifier.verify(context, candidate, NEXT_VERSION)
        }
    }

    private fun installed(): PackageInfo = installedPackage

    private fun archive(
        packageName: String = context.packageName,
        versionCode: Long = NEXT_VERSION,
        signingInfo: SigningInfo? = signers(SIGNER_A),
    ) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            setLongVersionCode(versionCode)
            this.signingInfo = signingInfo
        }
        whenever(packageManager.getPackageArchiveInfo(
            eq(candidate.absolutePath), any<PackageManager.PackageInfoFlags>(),
        )).thenReturn(info)
    }

    private fun signers(vararg certificates: Signature): SigningInfo =
        Shadow.newInstanceOf(SigningInfo::class.java).also {
            shadowOf(it).setSignatures(certificates.copyOf())
        }

    private fun assertRejected(expected: UpdateSecurity.Failure, check: () -> Unit) {
        val rejected = assertThrows(UpdateSecurity.Rejected::class.java) { check() }
        assertEquals(expected, rejected.failure)
    }

    private companion object {
        const val INSTALLED_VERSION = (1L shl 33) + 17L
        const val NEXT_VERSION = INSTALLED_VERSION + 1L
        const val PACKAGE_NAME = "com.example.test"
        val SIGNER_A = Signature("01020304")
        val SIGNER_B = Signature("05060708")
        val SIGNER_OLD = Signature("090a0b0c")
    }
}
