package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.ui.bandlock.BandLockProfile
import com.craxiom.networksurvey.ui.bandlock.BandLockProfiles
import com.craxiom.networksurvey.ui.bandlock.LockSupportLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {
    private val unsupportedLock = BandLockProfile(LockSupportLevel.Unsupported, "unknown", reason = "no_diag_channel")

    @Test
    fun nonQualcommKeepsTelephonyAndRejectsLock() {
        val snap = DeviceCapabilities.snapshot(
            sdk = 36,
            supportedAbis = arrayOf("arm64-v8a"),
            manufacturer = "Xiaomi",
            model = "21091116C",
            soc = "MT6893",
            hardware = "mt6893",
            simCount = 1,
            selectedSubscriptionId = 2,
            telephonyPermission = true,
            collectorStatus = RootDiagStatus.Unsupported,
            collectorError = 2,
            imsProviders = listOf(ImsProviderStatus("mediatek", "unsupported", false, "no_backend")),
            bandLock = BandLockProfiles.classify("Xiaomi", "21091116C", "MT6893", "mt6893", null, 0),
        )
        assertEquals(CapabilityState.Available, snap.telephony.state)
        assertTrue(snap.basicSignalAvailable)
        assertEquals(CapabilityState.Unsupported, snap.qualcommDiag.state)
        assertEquals("no_libdiag", snap.qualcommDiag.reason)
        assertEquals(CapabilityState.Unsupported, snap.bandLock.state)
        assertEquals("mediatek_unsupported", snap.bandLock.reason)
        assertTrue(snap.imsSources.any { it.id == "mediatek" && it.state == CapabilityState.Unsupported })
        assertTrue(snap.imsSources.any { it.id == "android_telephony" })
    }

    @Test
    fun noRootLeavesAndroidSignalAvailable() {
        val snap = DeviceCapabilities.snapshot(
            34, arrayOf("arm64-v8a"), "samsung", "SM-S9370", "SM8750", "sun",
            1, 1, true, RootDiagStatus.Denied, null, emptyList(), unsupportedLock,
        )
        assertTrue(snap.basicSignalAvailable)
        assertEquals("root_denied", snap.root.reason)
        assertEquals(CapabilityState.Unavailable, snap.qualcommDiag.state)
        assertEquals("root_denied", snap.qualcommDiag.reason)
    }

    @Test
    fun permissionDeniedHidesTelephony() {
        val snap = DeviceCapabilities.snapshot(
            34, arrayOf("arm64-v8a"), "samsung", "SM-S9370", "SM8750", "sun",
            1, null, false, RootDiagStatus.Unavailable, null, emptyList(), unsupportedLock,
        )
        assertFalse(snap.basicSignalAvailable)
        assertEquals("permission_denied", snap.telephony.reason)
    }

    @Test
    fun otherAbiIsUnsupportedForDiagButTelephonyWorks() {
        val snap = DeviceCapabilities.snapshot(
            33, arrayOf("armeabi-v7a"), "Google", "sdk", "goldfish", "ranchu",
            1, 1, true, RootDiagStatus.Unavailable, null, emptyList(), unsupportedLock,
        )
        assertTrue(snap.basicSignalAvailable)
        assertFalse(snap.abiSupported)
        assertEquals("abi_unsupported", snap.qualcommDiag.reason)
        assertEquals(CapabilityState.Unsupported, snap.qualcommDiag.state)
    }

    @Test
    fun dualSimKeepsAndroidDisplayWhileDiagCanRun() {
        assertFalse(DeviceCapabilities.displayUsesDiag(2, "NR", true))
        assertTrue(DeviceCapabilities.displayUsesDiag(1, "NR", true))
        assertFalse(DeviceCapabilities.displayUsesDiag(1, "GSM", true))
        assertFalse(DeviceCapabilities.displayUsesDiag(1, "NR", false))
    }

    @Test
    fun samsungLogcatUnavailableIsNotUnregistered() {
        val items = DeviceCapabilities.imsItems(
            listOf(ImsProviderStatus("samsung_logcat", "unavailable", false, "no_samsung_ims_tags")),
            sdk = 36,
        )
        val samsung = items.first { it.id == "samsung_logcat" }
        assertEquals(CapabilityState.Unavailable, samsung.state)
        assertEquals("no_samsung_ims_tags", samsung.reason)
        assertFalse(samsung.reason.contains("unregistered") || samsung.reason.contains("not_registered"))
    }

    @Test
    fun verifiedSamsungDiagAndLock() {
        val lock = BandLockProfiles.classify("samsung", "SM-S9370", "SM8750", "sun", "samsung-diag-router", 3)
        val snap = DeviceCapabilities.snapshot(
            36, arrayOf("arm64-v8a"), "samsung", "SM-S9370", "SM8750", "sun",
            1, 1, true, RootDiagStatus.Running, 0,
            listOf(ImsProviderStatus("samsung_logcat", "available", true, "sipmsg")),
            lock,
        )
        assertEquals(CapabilityState.Available, snap.qualcommDiag.state)
        assertEquals(CapabilityState.Available, snap.bandLock.state)
        assertEquals("verified", snap.bandLock.reason)
    }
}

class CollectorRestartPolicyTest {
    @Test
    fun modemRecoveryRestartsWhileARecordingIsOpen() {
        assertEquals("restarting_collector", CollectorRestartPolicy.modemRecoveryPhase(true, 1, 0))
        assertEquals("packets_resumed", CollectorRestartPolicy.modemRecoveryPhase(false, 1, 0))
        assertEquals("still_stalled", CollectorRestartPolicy.modemRecoveryPhase(
            true, CollectorRestartPolicy.MODEM_RECOVERY_CHECKS, 0,
        ))
    }

    @Test
    fun modemRecoveryOnlyRestartsWhenStatusOrPacketStreamIsStale() {
        assertFalse(CollectorRestartPolicy.needsModemRecovery(true, 1_000, 2_000, 12_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(true, 1_000, 38_000, 36_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(true, 6_000, 2_000, 12_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(false, 1_000, 2_000, 12_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(true, 1_000, null, 12_000))
    }

    @Test
    fun aShortBurstAfterRestartDoesNotPreventLaterRecovery() {
        assertFalse(CollectorRestartPolicy.needsModemRecovery(true, 1_000, 4_000, 12_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(true, 1_000, 16_000, 24_000))
        assertTrue(CollectorRestartPolicy.needsModemRecovery(true, 1_000, 4_000, 4_000))
    }

    @Test
    fun doesNotRetryDeniedOrMissingLibrary() {
        assertFalse(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Denied, null, false, 0))
        assertFalse(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unavailable, null, false, 0))
        assertFalse(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unsupported, 2, false, 0))
        assertFalse(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unsupported, 3, true, 0))
    }

    @Test
    fun retriesTransientInitAndDropAfterRunning() {
        assertTrue(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unsupported, 5, false, 0))
        assertTrue(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unsupported, null, true, 0))
        assertEquals(2_000L, CollectorRestartPolicy.delayMs(0))
        assertEquals(4_000L, CollectorRestartPolicy.delayMs(1))
        assertEquals(32_000L, CollectorRestartPolicy.delayMs(4))
        assertEquals(60_000L, CollectorRestartPolicy.delayMs(5))
        assertFalse(CollectorRestartPolicy.shouldRetry(RootDiagStatus.Unsupported, 5, false, CollectorRestartPolicy.MAX_ATTEMPTS))
    }
}

class AndroidImsRegistrationTest {
    @Test
    fun mapsRegistrationStates() {
        assertEquals("registered", AndroidImsRegistration.snapshot(1, 2, null).state)
        assertEquals("not_registered", AndroidImsRegistration.snapshot(1, 0, null).state)
        assertEquals("unavailable", AndroidImsRegistration.snapshot(1, null, "permission_denied").state)
    }

    @Test
    fun lowApiAndEmptySubscriptionsAreUnavailableNotUnregistered() {
        val low = AndroidImsRegistration.providerStatus(emptyList(), sdk = 29)
        assertEquals("unavailable", low.status)
        assertEquals("api_too_low", low.detail)
        val empty = AndroidImsRegistration.providerStatus(emptyList(), sdk = 36)
        assertEquals("no_subscription", empty.detail)
    }

    @Test
    fun registeredOnOneSlotCountsAsAvailable() {
        val status = AndroidImsRegistration.providerStatus(
            listOf(
                AndroidImsRegistration.snapshot(1, 0, null),
                AndroidImsRegistration.snapshot(2, 2, null),
            ),
            sdk = 36,
        )
        assertEquals("available", status.status)
        assertEquals("registered", status.detail)
        assertEquals(true, status.available)
    }
}
