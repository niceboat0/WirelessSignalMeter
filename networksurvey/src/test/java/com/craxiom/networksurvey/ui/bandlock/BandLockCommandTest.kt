package com.craxiom.networksurvey.ui.bandlock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLockCommandTest {
    @Test
    fun normalizesAndDeduplicatesBandSelections() {
        val cmd = BandLockCommand.apply(LockRadio.Lte, LockTarget.Bands, "b3, 1;3 3", "", "", 30)
        assertEquals(listOf("lte-band", "1,3"), cmd.arguments)
        /* Bands 1..32 go out instantly through the CM frame; no modem restart. */
        assertTrue(!cmd.restartsModem)
        /* Mixed selections above 32 still ride the EFS+restart route. */
        val high = BandLockCommand.apply(LockRadio.Lte, LockTarget.Bands, "B41, 1;3 3", "", "", 30)
        assertEquals(listOf("lte-band", "1,3,41"), high.arguments)
        assertTrue(high.restartsModem)
    }

    @Test
    fun keepsModemRestartForHighLteBandsNrAndCellLocks() {
        assertTrue(BandLockCommand.apply(LockRadio.Lte, LockTarget.Bands, "40,41", "", "", 30).restartsModem)
        assertTrue(BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30).restartsModem)
        assertTrue(BandLockCommand.apply(LockRadio.Lte, LockTarget.Cell, "", "100", "3", 30).restartsModem)
        assertTrue(BandLockCommand.apply(LockRadio.Lte, LockTarget.Frequency, "", "100", "", 30).restartsModem)
    }

    @Test
    fun preservesZeroLteChannelAndPciAndEncodesNrScs() {
        assertEquals(listOf("lte-cell", "0:0"),
            BandLockCommand.apply(LockRadio.Lte, LockTarget.Cell, "", "0", "0", 30).arguments)
        assertEquals(listOf("nr-cell", "78", "627264", "1007", "30"),
            BandLockCommand.apply(LockRadio.Nr, LockTarget.Cell, "78", "627264", "1007", 30).arguments)
        assertEquals(listOf("nr-freq", "3279165", "120"),
            BandLockCommand.apply(LockRadio.Nr, LockTarget.Frequency, "", "3279165", "", 120).arguments)
    }

    @Test
    fun rejectsEmptySelectionsInjectionAndOutOfRangeValues() {
        fun invalid(radio: LockRadio, target: LockTarget, band: String, frequency: String, pci: String, scs: Int = 30) {
            assertTrue(runCatching { BandLockCommand.apply(radio, target, band, frequency, pci, scs) }.isFailure)
        }
        invalid(LockRadio.Lte, LockTarget.Bands, "", "", "")
        invalid(LockRadio.Lte, LockTarget.Bands, "65", "", "")
        invalid(LockRadio.Nr, LockTarget.Bands, "513", "", "")
        invalid(LockRadio.Nr, LockTarget.Bands, "78;reboot", "", "")
        invalid(LockRadio.Lte, LockTarget.Cell, "", "262144", "0")
        invalid(LockRadio.Lte, LockTarget.Cell, "", "100", "504")
        invalid(LockRadio.Nr, LockTarget.Cell, "78", "627264", "1008")
        invalid(LockRadio.Nr, LockTarget.Cell, "78", "0", "0")
        invalid(LockRadio.Nr, LockTarget.Cell, "78", "627264", "0", 240)
        invalid(LockRadio.Nr, LockTarget.Cell, "78", "627264", "-1")
        invalid(LockRadio.Nr, LockTarget.Cell, "78", "999999999999999999", "0")
    }

    @Test
    fun usesSeparateRestoreCommandsAndFixedModeNames() {
        assertEquals(listOf("nr-band-restore"), BandLockCommand.restore(LockRadio.Nr, LockTarget.Bands).arguments)
        assertEquals(listOf("lte-freq-clear"), BandLockCommand.restore(LockRadio.Lte, LockTarget.Frequency).arguments)
        assertEquals(listOf("nr-cell-clear"), BandLockCommand.restore(LockRadio.Nr, LockTarget.Cell).arguments)
        assertEquals(listOf("mode", "auto"), BandLockCommand.mode(LockNetworkMode.Auto).arguments)
    }

    @Test
    fun probeCarriesDeviceIdentityForMultiDeviceRouting() {
        val cmd = BandLockCommand.probe("SM-S9370", "SM8750")
        assertEquals(listOf("probe", "SM-S9370", "SM8750"), cmd.arguments)
        assertTrue(!cmd.restartsModem)
    }

    @Test
    fun consoleSplitsRawCommandLineAndTreatsProbeAsReadOnly() {
        assertEquals(listOf("nr-band", "1,78,79"),
            BandLockCommand.console("  nr-band  1,78,79  ").arguments)
        assertTrue(BandLockCommand.console("nr-band 78").restartsModem)
        assertEquals(listOf("at", "/dev/smd0", "AT+CGMR"),
            BandLockCommand.console("at /dev/smd0 AT+CGMR").arguments)
        assertTrue(BandLockCommand.console("at /dev/smd0 AT+CGMR").restartsModem)
        assertTrue(!BandLockCommand.console("probe").restartsModem)
        assertTrue(runCatching { BandLockCommand.console("   ") }.isFailure)
    }

    @Test
    fun appliesInstantLteLockBeforeSelectingLteMode() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val result = BandLockCommand.apply(LockRadio.Lte, LockTarget.Bands, "3", "", "", 30)
            .executeWithMode(pause = { error("Instant lock should not wait for a restart") }) {
                commands.add(it.arguments)
                BandLockOutcome(0, "RESULT accepted\n")
            }
        assertEquals(listOf(listOf("lte-band", "3"), listOf("mode", "lte")), commands)
        assertEquals(true, result.modeConfirmed)
    }

    @Test
    fun switchesToNrAfterRestartAndRetriesUnavailableChannel() = runBlocking {
        val steps = mutableListOf<String>()
        val outcomes = ArrayDeque(listOf(
            BandLockOutcome(0, "RESULT restart_sent\n"), BandLockOutcome(2, ""), BandLockOutcome(0, "RESULT accepted\n"),
        ))
        val result = BandLockCommand.apply(LockRadio.Nr, LockTarget.Frequency, "", "627264", "", 30)
            .executeWithMode(pause = { steps.add("wait $it") }, onModemRestartRequested = { steps.add("recover") }) {
                steps.add(it.arguments.joinToString(" "))
                outcomes.removeFirst()
            }
        assertEquals(listOf("nr-freq 627264 30", "recover", "wait 5000", "mode nr", "wait 2000", "mode nr"), steps)
        assertEquals(true, result.modeConfirmed)
    }

    @Test
    fun failedLockClearingAndConsoleDoNotSwitchModes() = runBlocking {
        val lock = BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30)
        val scenarios = listOf(
            lock to BandLockOutcome(1, "RESULT restart_sent\n"),
            BandLockCommand.console("nr-band 78") to BandLockOutcome(0, "RESULT restart_sent\n"),
        )
        for ((command, outcome) in scenarios) {
            val commands = mutableListOf<BandLockCommand>()
            val result = command.executeWithMode(pause = { error("No mode follow-up expected") }) {
                commands.add(it)
                outcome
            }
            assertEquals(listOf(command), commands)
            assertEquals(null, result.modeConfirmed)
        }
    }

    @Test
    fun restoreReturnsToAutomaticModeAfterTheModemRestart() = runBlocking {
        val restore = BandLockCommand.restore(LockRadio.Nr, LockTarget.Bands)
        val commands = mutableListOf<List<String>>()
        val result = restore.executeWithMode(
            pause = { if (it != 5_000L) error("Unexpected wait $it") },
            onModemRestartRequested = {},
        ) {
            commands += it.arguments
            if (it == restore) BandLockOutcome(0, "RESULT restart_sent\n")
            else BandLockOutcome(0, "RESULT accepted\n")
        }
        assertEquals(listOf(listOf("nr-band-restore"), listOf("mode", "auto")), commands)
        assertEquals(true, result.modeConfirmed)
    }

    @Test
    fun restoreDoesNotConfirmWhenAutomaticModeIsRejected() = runBlocking {
        val restore = BandLockCommand.restore(LockRadio.Nr, LockTarget.Bands)
        val result = restore.executeWithMode(
            pause = {},
            onModemRestartRequested = {},
        ) {
            if (it == restore) BandLockOutcome(0, "RESULT accepted\n")
            else BandLockOutcome(7, "No recognized modem acknowledgement.\n")
        }
        assertEquals(false, result.modeConfirmed)
    }

    @Test
    fun unverifiedBaselineIsNotAWriteOrRestart() {
        val outcome = BandLockOutcome(1, "RESULT baseline_unverified\n")
        assertTrue(outcome.baselineUnverified)
        assertFalse(outcome.accepted)
        assertFalse(outcome.restartRequested)
    }

    @Test
    fun reportsUnconfirmedModeWithoutRetryingAModeCommandThatWasSent() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val lock = BandLockCommand.apply(LockRadio.Lte, LockTarget.Cell, "", "100", "3", 30)
        val waits = mutableListOf<Long>()
        val result = lock.executeWithMode(pause = { waits.add(it) }) {
            commands.add(it.arguments)
            if (it == lock) BandLockOutcome(0, "RESULT restart_sent\n") else BandLockOutcome(1, "No acknowledgement")
        }
        assertEquals(listOf(listOf("lte-cell", "100:3"), listOf("mode", "lte")), commands)
        assertEquals(listOf(5_000L), waits)
        assertTrue(result.outcome.accepted)
        assertEquals(false, result.modeConfirmed)
    }

    @Test
    fun stopsRetryingIfRestartedChannelNeverReturns() = runBlocking {
        val lock = BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30)
        var modeAttempts = 0
        val result = lock.executeWithMode(pause = {}) {
            if (it == lock) BandLockOutcome(0, "RESULT restart_sent\n") else {
                modeAttempts++
                BandLockOutcome(2, "")
            }
        }
        assertEquals(6, modeAttempts)
        assertEquals(false, result.modeConfirmed)
    }

    @Test
    fun qmiModeCommandBuildsExpectedArguments() {
        assertEquals(listOf("mode", "auto"), QmiLockCommand.mode(LockNetworkMode.Auto))
        assertEquals(listOf("mode", "lte"), QmiLockCommand.mode(LockNetworkMode.Lte))
        assertEquals(listOf("mode", "nr"), QmiLockCommand.mode(LockNetworkMode.Nr))
    }

    @Test
    fun prefersQmiModeAfterLockAndSkipsDiagModeWhenQmiSucceeds() = runBlocking {
        val diagCommands = mutableListOf<List<String>>()
        val qmiModes = mutableListOf<LockNetworkMode>()
        val result = BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "1", "", "", 30)
            .executeWithMode(
                pause = {},
                executeQmiMode = { mode ->
                    qmiModes.add(mode)
                    QmiLockOutcome(0, "VERDICT ok\n", "ok", reachable = true)
                },
            ) {
                diagCommands.add(it.arguments)
                BandLockOutcome(0, "RESULT restart_sent\n")
            }
        assertEquals(listOf(listOf("nr-band", "1")), diagCommands)
        assertEquals(listOf(LockNetworkMode.Nr), qmiModes)
        assertEquals(true, result.modeConfirmed)
    }

    @Test
    fun fallsBackToDiagModeWhenQmiModeFailsAfterLock() = runBlocking {
        val diagCommands = mutableListOf<List<String>>()
        val qmiModes = mutableListOf<LockNetworkMode>()
        val diagOutcomes = ArrayDeque(listOf(
            BandLockOutcome(0, "RESULT restart_sent\n"),
            BandLockOutcome(2, ""),
            BandLockOutcome(0, "RESULT accepted\n"),
        ))
        val result = BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "78", "", "", 30)
            .executeWithMode(
                pause = {},
                executeQmiMode = { mode ->
                    qmiModes.add(mode)
                    QmiLockOutcome(1, "QMI mode qmi_error=3\nVERDICT rejected\n", "rejected", reachable = true, errorCode = 3)
                },
            ) {
                diagCommands.add(it.arguments)
                diagOutcomes.removeFirst()
            }
        assertEquals(listOf(LockNetworkMode.Nr), qmiModes)
        assertEquals(listOf(listOf("nr-band", "78"), listOf("mode", "nr"), listOf("mode", "nr")), diagCommands)
        assertEquals(true, result.modeConfirmed)
    }

    @Test
    fun standaloneModePrefersQmiAndFallsBackToDiagWhenUnavailable() = runBlocking {
        val diagCalls = mutableListOf<List<String>>()
        val ok = BandLockCommand.mode(LockNetworkMode.Nr).executeWithMode(
            executeQmiMode = { QmiLockOutcome(0, "VERDICT ok\n", "ok", reachable = true) },
        ) {
            diagCalls.add(it.arguments)
            BandLockOutcome(0, "RESULT accepted\n")
        }
        assertTrue(ok.outcome.accepted)
        assertEquals(null, ok.modeConfirmed)
        assertTrue(diagCalls.isEmpty())

        val fallback = BandLockCommand.mode(LockNetworkMode.Auto).executeWithMode(
            executeQmiMode = { QmiLockOutcome(3, "QMI mode fail=lookup\n", "unavailable", reachable = false) },
        ) {
            diagCalls.add(it.arguments)
            BandLockOutcome(0, "RESULT accepted\n")
        }
        assertTrue(fallback.outcome.accepted)
        assertEquals(listOf(listOf("mode", "auto")), diagCalls)
    }
}
