package com.craxiom.networksurvey.ui.bandlock

import kotlinx.coroutines.runBlocking
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
    fun malformedLegacyCacheDoesNotRestoreCapabilities() {
        for (text in listOf(
            """{"ready":true,"channel":"diag","caps":{""",
            """{"ready":"true","channel":"diag","caps":{}}""",
            """{"ready":true,"channel":42,"caps":{}}""",
            """{"ready":true,"channel":"diag","caps":[]}""",
            """{"ready":true,"channel":"diag","caps":{"nv":"true"}}""",
            """{"ready":true,"channel":"diag","caps":{},"lte_backup":"true"}""",
        )) {
            assertNull(text, BandLockVerification.parseCachedProbe(text))
        }
        val escaped = """{"ready":true,"channel":"diag\u002dstream","caps":{"nv":true}}"""
        assertEquals("diag-stream", BandLockVerification.parseCachedProbe(escaped)?.channel)
    }

    @Test
    fun cachedWriteProbesOnceBeforeExecutingTheSelectedCommand() = runBlocking {
        var availability = LockAvailability.Cached
        val commands = mutableListOf<String>()
        repeat(2) {
            BandLockVerification.executeWhenConfirmed(
                availability, LockWriteKind.Apply,
                confirm = {
                    commands.add("probe")
                    availability = LockAvailability.Ready
                    true
                },
                execute = { commands.add("nr-band 78") },
            )
        }
        assertEquals(listOf("probe", "nr-band 78", "nr-band 78"), commands)
    }

    @Test
    fun failedLiveProbeCannotExecuteTheCachedWrite() = runBlocking {
        val commands = mutableListOf<String>()
        val result = BandLockVerification.executeWhenConfirmed(
            LockAvailability.Cached, LockWriteKind.Apply,
            confirm = { commands.add("probe"); false },
            execute = { commands.add("nr-band 78") },
        )
        assertNull(result)
        assertEquals(listOf("probe"), commands)
    }

    @Test
    fun uncheckedAndRevokedSessionsRequireAnExplicitProbe() = runBlocking {
        for (availability in listOf(LockAvailability.Unchecked, LockAvailability.Unavailable)) {
            val result = BandLockVerification.executeWhenConfirmed(
                availability, LockWriteKind.Apply,
                confirm = { error("Only cached sessions may probe on the first write") },
                execute = { error("Unconfirmed write must not execute") },
            )
            assertNull(result)
            var probes = 0
            BandLockVerification.executeWhenConfirmed(
                availability, LockWriteKind.Probe,
                confirm = { error("An explicit probe must not probe recursively") },
                execute = { probes++ },
            )
            assertEquals(1, probes)
        }
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
    fun protectedBackupRequiresLiveNativeValidationAndMatchingFileSize() {
        val protected = FileView(true, true, false, null, size = 8)
        assertEquals(BackupFileState.Unreadable, BandLockVerification.inspectBackup(protected, 8))
        assertEquals(
            BackupFileState.Usable,
            BandLockVerification.inspectBackup(protected, 8, nativeConfirmed = true),
        )
        assertEquals(
            BackupFileState.UnexpectedSize,
            BandLockVerification.inspectBackup(protected.copy(size = 7), 8, nativeConfirmed = true),
        )
        assertEquals(
            BackupFileState.Missing,
            BandLockVerification.inspectBackup(protected.copy(exists = false), 8, nativeConfirmed = true),
        )
        assertEquals(
            BackupFileState.Empty,
            BandLockVerification.inspectBackup(
                FileView(true, true, true, ByteArray(8)), 8, nativeConfirmed = true,
            ),
        )
        assertEquals(
            BackupFileState.UnexpectedSize,
            BandLockVerification.inspectBackup(
                FileView(true, true, true, ByteArray(7) { 1 }, size = 8), 8, nativeConfirmed = true,
            ),
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
        assertNull(BandLockVerification.encodeOrigin(identity.copy(fingerprint = "x".repeat(256))))
    }

    @Test
    fun rootRevocationIsDetectedFromHelperOutput() {
        assertTrue(BandLockVerification.rootRevoked("Root authorization is required.\n"))
        for (text in listOf("su: Permission denied", "su: Not allowed", "su: Authorization denied")) {
            assertTrue(text, BandLockVerification.rootRevoked(text))
        }
        assertFalse(BandLockVerification.rootRevoked("RESULT ready\n"))
        assertFalse(BandLockVerification.rootRevoked("EFS permission denied"))
        assertFalse(BandLockVerification.rootRevoked("NR_BAND readback=permission denied"))
        assertFalse(BandLockVerification.rootRevoked("No recognized modem acknowledgement."))
    }

    @Test
    fun conflictingBackupBlocksNewLocksButDoesNotBlockModeOrClear() {
        for (kind in listOf(LockWriteKind.Apply, LockWriteKind.RestoreLteBands, LockWriteKind.UnlockNrBands)) {
            assertEquals(
                WriteDecision.BackupMismatch,
                BandLockVerification.decideWrite(
                    LockAvailability.Ready, false, kind, BackupFileState.Usable, identity, otherDevice,
                ),
            )
        }
        for (kind in listOf(LockWriteKind.ChangeMode, LockWriteKind.ClearLock)) {
            assertEquals(
                WriteDecision.Allowed,
                BandLockVerification.decideWrite(
                    LockAvailability.Ready, false, kind, BackupFileState.Usable, identity, otherDevice,
                ),
            )
        }
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
            BackupCaption.Needed,
            BandLockVerification.backupCaption(LockAvailability.Ready, LockRadio.Nr, confirmed = false, cachedHint = false),
        )
        assertNotEquals(
            BackupCaption.Confirmed,
            BandLockVerification.backupCaption(LockAvailability.Cached, LockRadio.Lte, confirmed = false, cachedHint = true),
        )
    }
}
