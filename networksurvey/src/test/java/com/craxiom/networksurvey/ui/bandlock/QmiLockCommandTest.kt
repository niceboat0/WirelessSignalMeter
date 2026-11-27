package com.craxiom.networksurvey.ui.bandlock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmiLockCommandTest {
    private val lte = "0000000000000044"
    private val nr = "00".repeat(63) + "01"
    private val ok = QmiLockOutcome.read(0, "VERDICT ok\n", "")

    @Test
    fun nrLockPreservesTheReadBackLteMaskAndSwitchesRatInTheSameWrite() = runBlocking {
        val plan = requireNotNull(BandLockCommand.apply(LockRadio.Nr, LockTarget.Bands, "n1", "", "", 30).qmi)
        val steps = mutableListOf<List<String>>()
        val execution = plan.execute(beforeWrite = { steps.add(listOf("save-backend")) }) { command ->
            steps.add(command)
            if (command == QmiLockCommand.probe()) ok.copy(output = "QMI bands lte=0x$lte\nVERDICT ok\n") else ok
        }
        assertEquals(listOf(listOf("bands-get"), listOf("save-backend"), listOf("bands-set", lte, nr, "--mode", "nr")), steps)
        assertTrue(execution.modeConfirmed)
    }

    @Test
    fun nrLockWithoutAnLteReadbackDoesNotWriteOrFallBack() = runBlocking {
        val plan = QmiLockCommand.bandPlan(LockRadio.Nr, listOf(1))
        val execution = plan.execute(beforeWrite = { error("Must not write") }) { command ->
            assertEquals(QmiLockCommand.probe(), command)
            ok.copy(output = "QMI bands lte=0x123\nVERDICT ok\n")
        }
        assertFalse(execution.outcome.accepted)
        assertFalse(execution.mayFallbackToDiag)
    }

    @Test
    fun lteLockPreservesReportedNrMask() {
        val masks = QmiLockCommand.parseBandMasks("QMI bands nr=$nr\nVERDICT ok\n")
        assertEquals(listOf("bands-set", "0000000000000004", nr, "--mode", "lte"),
            QmiLockCommand.applyBands(LockRadio.Lte, listOf(3), masks))
        assertEquals(listOf("bands-set", "0000000000000004", "--mode", "lte"),
            QmiLockCommand.applyBands(LockRadio.Lte, listOf(3), QmiLockCommand.BandMasks(null, null)))
    }

    @Test
    fun restoreCarriesTheSelectedRadioAndReturnsToAutomaticMode() = runBlocking {
        assertEquals(listOf("bands-restore", "nr"), QmiLockCommand.restoreBands(LockRadio.Nr))
        assertEquals(listOf("bands-restore", "lte"), QmiLockCommand.restoreBands(LockRadio.Lte))
        val plan = requireNotNull(BandLockCommand.restore(LockRadio.Nr, LockTarget.Bands).qmi)
        assertEquals(LockNetworkMode.Auto, plan.mode)
        assertTrue(plan.switchModeAfterWrite)
        assertFalse(plan.allowsDiagFallback(emptyMap()))
        val commands = mutableListOf<List<String>>()
        plan.execute(beforeWrite = {}) { commands.add(it); ok }
        assertEquals(listOf(listOf("bands-restore", "nr"), listOf("mode", "auto")), commands)
    }

    @Test
    fun acknowledgedCellLockSwitchesRatAndAFailedModeChangeCannotFallBack() = runBlocking {
        val plan = requireNotNull(BandLockCommand.apply(LockRadio.Lte, LockTarget.Cell, "", "100", "3", 30).qmi)
        val commands = mutableListOf<List<String>>()
        val execution = plan.execute(beforeWrite = {}) {
            commands.add(it)
            if (it.first() == "mode") QmiLockOutcome.read(3, "QMI mode fail=lookup\n", "mode") else ok
        }
        assertEquals(listOf(listOf("celllte-set", "3:100"), listOf("mode", "lte")), commands)
        assertTrue(execution.outcome.accepted)
        assertFalse(execution.modeConfirmed)
        assertFalse(execution.mayFallbackToDiag)
    }

    @Test
    fun unconfirmedCellWriteCannotFallBackOrChangeMode() = runBlocking {
        val plan = requireNotNull(BandLockCommand.apply(LockRadio.Nr, LockTarget.Cell, "1", "422000", "3", 15).qmi)
        val commands = mutableListOf<List<String>>()
        val execution = plan.execute(beforeWrite = {}) {
            commands.add(it)
            QmiLockOutcome.read(6, "QMI cellnr-set transport_error=Connection timed out\n", "cellnr-set")
        }
        assertEquals(listOf(listOf("cellnr-set", "3", "422000", "15", nr)), commands)
        assertNull(execution.modeOutcome)
        assertFalse(execution.mayFallbackToDiag)
        assertFalse(execution.modeConfirmed)
    }

    @Test
    fun fallbackRequiresNativeProofThatTheRequestWasNotSent() {
        assertTrue(QmiLockOutcome.read(3, "QMI cellnr-set fail=lookup\n", "cellnr-set").unavailableBeforeWrite)
        assertFalse(QmiLockOutcome.read(6, "QMI cellnr-set fail=lookup\n", "cellnr-set").unavailableBeforeWrite)
        assertFalse(QmiLockOutcome.read(3, "QMI mode fail=lookup\n", "cellnr-set").unavailableBeforeWrite)
        assertFalse(QmiLockOutcome.read(7, "VERDICT rejected\nQMI cellnr-set qmi_error=3\n", "cellnr-set").unavailableBeforeWrite)
    }

    @Test
    fun onlyExplicitQmiRejectionOffersDiagRetry() {
        val rejected = QmiLockOutcome.read(7, "VERDICT rejected\nQMI cellnr-set qmi_error=3\n", "cellnr-set")
        val timeout = QmiLockOutcome.read(6, "QMI cellnr-set transport_error=timeout\n", "cellnr-set")
        val failed = QmiLockOutcome.read(1, "VERDICT fail\n", "cellnr-set")
        assertTrue(QmiLockExecution(rejected, writeAttempted = true).requiresDiagFallbackConfirmation)
        assertFalse(QmiLockExecution(timeout, writeAttempted = true).requiresDiagFallbackConfirmation)
        assertFalse(QmiLockExecution(failed, writeAttempted = true).requiresDiagFallbackConfirmation)
    }

    @Test
    fun defaultsToQmiWhereSupportedAndClearsThroughTheRecordedBackend() {
        val nrClear = requireNotNull(BandLockCommand.restore(LockRadio.Nr, LockTarget.Cell).qmi)
        val nrFrequency = requireNotNull(BandLockCommand.apply(LockRadio.Nr, LockTarget.Frequency, "", "422000", "", 15).qmi)
        assertEquals(listOf("cellnr-set", "arfcn", "422000", "15"), nrFrequency.arguments)
        assertEquals(listOf("cellnr-clear"), nrClear.arguments)
        val frequencySlot = requireNotNull(nrFrequency.slot)
        val cellSlot = requireNotNull(nrClear.slot)
        assertEquals(LockBackend.Qmi, preferredLockBackend(nrClear, emptyMap(), preferDiag = false))
        assertEquals(LockBackend.Diag, preferredLockBackend(nrClear, emptyMap(), preferDiag = true))
        assertEquals(LockBackend.Qmi, preferredLockBackend(nrClear, mapOf(frequencySlot to LockBackend.Qmi), preferDiag = true))
        assertEquals(LockBackend.Diag, preferredLockBackend(nrClear, mapOf(cellSlot to LockBackend.Diag), preferDiag = false))
        assertTrue(nrFrequency.allowsDiagFallback(emptyMap()))
        assertFalse(nrFrequency.allowsDiagFallback(mapOf(cellSlot to LockBackend.Qmi)))
        assertNull(BandLockCommand.restore(LockRadio.Lte, LockTarget.Frequency).qmi)
        assertEquals(LockBackend.Diag, preferredLockBackend(null, emptyMap(), preferDiag = false))
    }
}
