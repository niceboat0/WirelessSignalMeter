package com.craxiom.networksurvey.ui.bandlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BandLockVerificationTest {
    private val identity = DeviceIdentity("SM-S9370", "SM8750", "fp/release-keys")
    private val otherDevice = DeviceIdentity("SM-S9370", "SM8750", "fp/other-keys")

    @Test
    fun cacheIsDisplayOnlyAndNeverAuthorizesWrites() {
        val key = BandLockVerification.cacheKey(120, identity.fingerprint, identity.soc)
        assertFalse(BandLockVerification.cacheApplies(null, key))
        assertFalse(BandLockVerification.cacheApplies("probe:119:${identity.fingerprint}:${identity.soc}", key))
        assertTrue(BandLockVerification.cacheApplies(key, key))

        val encoded = BandLockVerification.encodeCache(
            CachedProbe("samsung-diag-router", listOf("lte_bandpref" to true), true, true, true),
            true, true, true,
        )
        val cached = BandLockVerification.parseCachedProbe(encoded!!)
        assertEquals(true, cached?.readyHint)
        assertEquals(true, cached?.lteBackupHint)
        val legacy = BandLockVerification.parseCachedProbe(
            """{"ready":true,"channel":"samsung-diag-router","caps":{"lte_bandpref":true},"lte_backup":true,"nr_backup":true}""",
        )
        assertEquals(true, legacy?.readyHint)
        assertEquals(true, legacy?.lteBackupHint)
        assertEquals("samsung-diag-router", legacy?.channel)
        assertEquals(
            WriteDecision.NotLiveConfirmed,
            BandLockVerification.decideWrite(
                LockAvailability.Cached, false, LockWriteKind.RestoreLteBands,
                BackupFileState.Usable, identity, identity,
            ),
        )
        assertEquals(
            WriteDecision.NotLiveConfirmed,
            BandLockVerification.decideWrite(LockAvailability.Cached, false, LockWriteKind.Apply),
        )
        assertEquals(
            WriteDecision.Allowed,
            BandLockVerification.decideWrite(LockAvailability.Cached, false, LockWriteKind.Probe),
        )
    }

    @Test
    fun firmwareOrInterfaceIdentityChangeDropsCache() {
        val current = BandLockVerification.cacheKey(120, "fp/new", "SM8750")
        assertFalse(
            BandLockVerification.cacheApplies(
                BandLockVerification.cacheKey(120, "fp/old", "SM8750"), current,
            ),
        )
        assertFalse(
            BandLockVerification.cacheApplies(
                BandLockVerification.cacheKey(120, "fp/new", "SM8550"), current,
            ),
        )
    }

    @Test
    fun inspectsMissingCorruptUnreadableAndUsableBackupFiles() {
        val dir = Files.createTempDirectory("wsm-lock-backup").toFile()
        try {
            val missing = File(dir, BandLockVerification.LTE_BACKUP)
            assertEquals(BackupFileState.Missing, BandLockVerification.inspectBackup(missing, 8))

            missing.writeBytes(ByteArray(7) { 1 })
            assertEquals(BackupFileState.UnexpectedSize, BandLockVerification.inspectBackup(missing, 8))

            missing.writeBytes(ByteArray(8))
            assertEquals(BackupFileState.Empty, BandLockVerification.inspectBackup(missing, 8))

            missing.writeBytes(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
            assertEquals(BackupFileState.Usable, BandLockVerification.inspectBackup(missing, 8))

            val folder = File(dir, "not-a-file").apply { mkdir() }
            assertEquals(BackupFileState.NotRegular, BandLockVerification.inspectBackup(folder, 8))

            assertEquals(
                BackupFileState.Unreadable,
                BandLockVerification.inspectBackup(FileView(true, true, false, null), 8),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lteRestoreRequiresUsableBackupAfterLiveProbe() {
        val ready = LockAvailability.Ready
        assertEquals(
            WriteDecision.BackupMissing,
            BandLockVerification.decideWrite(ready, false, LockWriteKind.RestoreLteBands, BackupFileState.Missing),
        )
        assertEquals(
            WriteDecision.BackupUnreadable,
            BandLockVerification.decideWrite(ready, false, LockWriteKind.RestoreLteBands, BackupFileState.Unreadable),
        )
        assertEquals(
            WriteDecision.BackupCorrupt,
            BandLockVerification.decideWrite(ready, false, LockWriteKind.RestoreLteBands, BackupFileState.Empty),
        )
        assertEquals(
            WriteDecision.Allowed,
            BandLockVerification.decideWrite(ready, false, LockWriteKind.RestoreLteBands, BackupFileState.Usable),
        )
    }

    @Test
    fun backupDisappearingAfterProbeBlocksRestore() {
        val dir = Files.createTempDirectory("wsm-lock-disappear").toFile()
        try {
            val file = File(dir, BandLockVerification.LTE_BACKUP)
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            assertEquals(BackupFileState.Usable, BandLockVerification.inspectBackup(file, 8))
            assertTrue(file.delete())
            val after = BandLockVerification.inspectBackup(file, 8)
            assertEquals(BackupFileState.Missing, after)
            assertEquals(
                WriteDecision.BackupMissing,
                BandLockVerification.decideWrite(LockAvailability.Ready, false, LockWriteKind.RestoreLteBands, after),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun originMismatchBlocksRestoreWithoutPretendingSuccess() {
        val usable = BackupFileState.Usable
        assertEquals(
            WriteDecision.BackupMismatch,
            BandLockVerification.decideWrite(
                LockAvailability.Ready, false, LockWriteKind.RestoreLteBands, usable, identity, otherDevice,
            ),
        )
        assertEquals(
            WriteDecision.Allowed,
            BandLockVerification.decideWrite(
                LockAvailability.Ready, false, LockWriteKind.RestoreLteBands, usable, identity, identity,
            ),
        )
        assertEquals(
            WriteDecision.Allowed,
            BandLockVerification.decideWrite(
                LockAvailability.Ready, false, LockWriteKind.RestoreLteBands, usable, null, identity,
            ),
        )
        val encoded = BandLockVerification.encodeOrigin(identity)
        assertEquals(identity, BandLockVerification.parseOrigin(encoded!!))
        assertNull(BandLockVerification.encodeOrigin(identity.copy(fingerprint = "bad\nfp")))
    }

    @Test
    fun rootRevocationIsDetectedFromHelperOutput() {
        assertTrue(BandLockVerification.rootRevoked("Root authorization is required.\n"))
        assertFalse(BandLockVerification.rootRevoked("RESULT ready\n"))
    }

    @Test
    fun busyAndRepeatedClicksDoNotAuthorizeASecondWrite() {
        val first = BandLockVerification.decideWrite(
            LockAvailability.Ready, false, LockWriteKind.RestoreLteBands, BackupFileState.Usable,
        )
        val second = BandLockVerification.decideWrite(
            LockAvailability.Ready, true, LockWriteKind.RestoreLteBands, BackupFileState.Usable,
        )
        val concurrentProbe = BandLockVerification.decideWrite(LockAvailability.Checking, true, LockWriteKind.Probe)
        assertEquals(WriteDecision.Allowed, first)
        assertEquals(WriteDecision.Busy, second)
        assertEquals(WriteDecision.Busy, concurrentProbe)
        assertEquals(
            WriteDecision.Busy,
            BandLockVerification.decideWrite(LockAvailability.Ready, true, LockWriteKind.UnlockNrBands),
        )
    }

    @Test
    fun nrUnlockAndClearAndModeDoNotRequireABackup() {
        for (kind in listOf(LockWriteKind.UnlockNrBands, LockWriteKind.ClearLock, LockWriteKind.ChangeMode, LockWriteKind.Apply)) {
            assertEquals(
                "$kind",
                WriteDecision.Allowed,
                BandLockVerification.decideWrite(LockAvailability.Ready, false, kind, BackupFileState.Missing),
            )
        }
        assertEquals(
            LockWriteKind.UnlockNrBands,
            BandLockVerification.writeKind(BandLockCommand.restore(LockRadio.Nr, LockTarget.Bands)),
        )
        assertEquals(
            LockWriteKind.RestoreLteBands,
            BandLockVerification.writeKind(BandLockCommand.restore(LockRadio.Lte, LockTarget.Bands)),
        )
        assertEquals(
            LockWriteKind.ClearLock,
            BandLockVerification.writeKind(BandLockCommand.restore(LockRadio.Lte, LockTarget.Frequency)),
        )
        assertEquals(
            LockWriteKind.ClearLock,
            BandLockVerification.writeKind(BandLockCommand.restore(LockRadio.Nr, LockTarget.Cell)),
        )
    }

    @Test
    fun backupCaptionsDoNotReportRestorableFromCacheAlone() {
        assertEquals(
            BackupCaption.CachedHint,
            BandLockVerification.backupCaption(LockAvailability.Cached, LockRadio.Lte, confirmed = false, cachedHint = true),
        )
        assertEquals(
            BackupCaption.Confirmed,
            BandLockVerification.backupCaption(LockAvailability.Ready, LockRadio.Lte, confirmed = true, cachedHint = true),
        )
        assertEquals(
            BackupCaption.NrUnlockWithoutBackup,
            BandLockVerification.backupCaption(LockAvailability.Ready, LockRadio.Nr, confirmed = false, cachedHint = false),
        )
        assertNotEquals(
            BackupCaption.Confirmed,
            BandLockVerification.backupCaption(LockAvailability.Cached, LockRadio.Lte, confirmed = false, cachedHint = true),
        )
    }
}
