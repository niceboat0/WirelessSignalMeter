package com.craxiom.networksurvey.ui.bandlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLockProfileTest {
    @Test
    fun verifiedSamsungWhenChannelExists() {
        val profile = BandLockProfiles.classify("samsung", "SM-S9370", "SM8750", "sun", "samsung-diag-router", 3)
        assertEquals(LockSupportLevel.Verified, profile.level)
        assertEquals("qualcomm-diag", profile.adapter)
        assertTrue(profile.canLock)
    }

    @Test
    fun failedProbeIsUnsupportedEvenOnVerifiedModel() {
        val profile = BandLockProfiles.classify("samsung", "SM-S9370", "SM8750", "sun", "", 0)
        assertEquals(LockSupportLevel.Unsupported, profile.level)
        assertFalse(profile.canLock)
        assertEquals("no_diag_channel", profile.reason)
    }

    @Test
    fun otherQualcommModelIsProbeOnly() {
        val profile = BandLockProfiles.classify("Xiaomi", "2211133C", "SM8550", "kalama", "diag-seqpacket", 2)
        assertEquals(LockSupportLevel.ProbeOnly, profile.level)
        assertTrue(profile.canLock)
        assertEquals("unverified_model", profile.reason)
    }

    @Test
    fun verifiedModelWithoutLockCapabilityIsUnsupported() {
        val profile = BandLockProfiles.classify("samsung", "SM-S9370", "SM8750", "sun", "samsung-diag-router", 0)
        assertEquals(LockSupportLevel.Unsupported, profile.level)
        assertFalse(profile.canLock)
        assertEquals("no_lock_capability", profile.reason)
    }

    @Test
    fun mediatekIsUnsupported() {
        val profile = BandLockProfiles.classify("Xiaomi", "21091116C", "MT6893", "mt6893", null, 0)
        assertEquals(LockSupportLevel.Unsupported, profile.level)
        assertEquals("mediatek_unsupported", profile.reason)
        assertFalse(profile.canLock)
    }

    @Test
    fun mediatekStaysUnsupportedIfAChannelNameAppears() {
        val profile = BandLockProfiles.classify("Xiaomi", "21091116C", "MT6893", "dimensity", "diag-stream", 1)
        assertEquals(LockSupportLevel.Unsupported, profile.level)
        assertEquals("mediatek_unsupported", profile.reason)
    }
}
