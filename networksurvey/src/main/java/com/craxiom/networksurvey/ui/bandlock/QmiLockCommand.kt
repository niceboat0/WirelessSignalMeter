package com.craxiom.networksurvey.ui.bandlock

/**
 * Argument builders for the QMI NAS instant-lock path (qmi-probe over QRTR).
 *
 * This backend is independent of the NV/EFS route: it takes effect
 * immediately without a modem restart, and its persistence is not promised
 * (unverified after SSR). The plan uses the same validated selection as DIAG.
 */
internal object QmiLockCommand {
    fun probe(): List<String> = listOf("bands-get")

    data class BandMasks(val lte: String?, val nr: String?)

    fun parseBandMasks(output: String): BandMasks = BandMasks(
        lte = Regex("(?m)^QMI bands lte=0x([0-9a-fA-F]{16})\\s*$").find(output)?.groupValues?.get(1),
        nr = Regex("(?m)^QMI bands nr=([0-9a-fA-F]{128})\\s*$").find(output)?.groupValues?.get(1),
    )

    fun bandPlan(radio: LockRadio, bands: List<Int>) =
        QmiLockPlan(slot = QmiLockSlot(radio, LockTarget.Bands), bands = bands, mode = radio.mode)

    fun frequencyPlan(radio: LockRadio, channel: Int, scs: Int): QmiLockPlan? =
        if (radio == LockRadio.Nr) QmiLockPlan(
            slot = QmiLockSlot(radio, LockTarget.Frequency),
            arguments = listOf("cellnr-set", "arfcn", channel.toString(), scs.toString()),
            mode = radio.mode, switchModeAfterWrite = true,
        ) else null

    fun cellPlan(radio: LockRadio, channel: Int, pci: Int, scs: Int, nrBand: Int?) =
        QmiLockPlan(
            slot = QmiLockSlot(radio, LockTarget.Cell),
            arguments = if (radio == LockRadio.Lte) {
                listOf("celllte-set", "$pci:$channel")
            } else {
                listOf("cellnr-set", pci.toString(), channel.toString(), scs.toString(), nrMask(listOf(requireNotNull(nrBand))))
            },
            mode = radio.mode, switchModeAfterWrite = true,
        )

    fun modePlan(mode: LockNetworkMode) = QmiLockPlan(arguments = mode(mode), mode = mode)

    fun restorePlan(radio: LockRadio, target: LockTarget): QmiLockPlan? =
        when {
            target == LockTarget.Bands -> QmiLockPlan(
                slot = QmiLockSlot(radio, LockTarget.Bands),
                arguments = restoreBands(radio),
                mode = LockNetworkMode.Auto,
                switchModeAfterWrite = true,
                restoring = true,
            )
            target == LockTarget.Cell || radio == LockRadio.Nr -> QmiLockPlan(
                slot = QmiLockSlot(radio, target),
                arguments = clearCell(radio),
                mode = LockNetworkMode.Auto,
                switchModeAfterWrite = true,
                restoring = true,
            )
            else -> null
        }

    fun mode(mode: LockNetworkMode) = listOf("mode", mode.argument)

    fun applyBands(radio: LockRadio, numbers: List<Int>, masks: BandMasks): List<String> {
        val masksToWrite = if (radio == LockRadio.Lte) {
            val mask = numbers.fold(0uL) { acc, band -> acc or (1uL shl (band - 1)) }
            listOfNotNull(mask.toString(16).padStart(16, '0'), masks.nr)
        } else {
            listOf(requireNotNull(masks.lte), nrMask(numbers))
        }
        return listOf("bands-set") + masksToWrite + listOf("--mode", radio.mode.argument)
    }

    /** Hex CLI argument, packed little-endian by qmi-probe. */
    private fun nrMask(numbers: List<Int>): String {
        val bytes = ByteArray(64)
        for (band in numbers) {
            val bit = band - 1
            bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return buildString(128) {
            for (i in bytes.indices.reversed()) append((bytes[i].toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    /**
     * Restore every band the modem itself reports as supported. The native
     * `bands-restore` path reads DMS GET_BANDS and writes only that mask with
     * no mode TLV, so the network mode preference is left untouched. Sending
     * an all-ones mask instead was rejected on a real SM-S9370 (qmi_error=3),
     * because it names bands that modem does not support.
     *
     * Requires qmi-probe built with the bands-restore command.
     */
    fun restoreBands(radio: LockRadio): List<String> = listOf("bands-restore", radio.argument)

    private fun clearCell(radio: LockRadio): List<String> =
        if (radio == LockRadio.Lte) listOf("celllte-clear") else listOf("cellnr-clear")
}

internal enum class LockBackend { Qmi, Diag }

internal data class QmiLockSlot(val radio: LockRadio, val target: LockTarget) {
    val preferenceKey: String get() = "${radio.argument}_${target.name}"

    // The NR NAS clear command clears either the ARFCN or PCI lock.
    fun relatedQmiSlot(): QmiLockSlot? =
        if (radio != LockRadio.Nr || target == LockTarget.Bands) null else
            copy(target = if (target == LockTarget.Cell) LockTarget.Frequency else LockTarget.Cell)
}

internal data class QmiLockPlan(
    val slot: QmiLockSlot? = null,
    val arguments: List<String> = emptyList(),
    val bands: List<Int>? = null,
    val mode: LockNetworkMode? = null,
    val switchModeAfterWrite: Boolean = false,
    val restoring: Boolean = false,
) {
    fun allowsDiagFallback(): Boolean = !restoring

    suspend fun execute(
        beforeWrite: suspend () -> Unit,
        run: suspend (List<String>) -> QmiLockOutcome,
    ): QmiLockExecution {
        val args = if (bands != null) {
            val radio = requireNotNull(slot).radio
            val readback = run(QmiLockCommand.probe())
            if (!readback.accepted) return QmiLockExecution(readback)
            val masks = QmiLockCommand.parseBandMasks(readback.output)
            if (radio == LockRadio.Nr && masks.lte == null) {
                return QmiLockExecution(readback.copy(exit = 1, verdict = "fail"))
            }
            QmiLockCommand.applyBands(radio, bands, masks)
        } else arguments
        beforeWrite()
        val outcome = run(args)
        val modeOutcome = mode?.takeIf { switchModeAfterWrite && outcome.accepted }
            ?.let { run(QmiLockCommand.mode(it)) }
        return QmiLockExecution(outcome, modeOutcome, writeAttempted = true)
    }
}

internal fun preferredLockBackend(
    plan: QmiLockPlan?,
    preferDiag: Boolean,
): LockBackend = if (plan == null || preferDiag) LockBackend.Diag else LockBackend.Qmi

internal fun QmiLockPlan.savedBackend(backends: Map<QmiLockSlot, LockBackend>): LockBackend? {
    val key = slot ?: return null
    return backends[key] ?: key.relatedQmiSlot()?.let(backends::get)?.takeIf { it == LockBackend.Qmi }
}

internal data class QmiLockExecution(
    val outcome: QmiLockOutcome,
    val modeOutcome: QmiLockOutcome? = null,
    val writeAttempted: Boolean = false,
) {
    val mayFallbackToDiag: Boolean get() = outcome.unavailableBeforeWrite
    val requiresDiagFallbackConfirmation: Boolean get() = outcome.verdict == "rejected"
    val modeConfirmed: Boolean get() = outcome.accepted && (modeOutcome?.accepted != false)
}

/** Outcome of one qmi-probe run. */
internal data class QmiLockOutcome(
    val exit: Int,
    val output: String,
    val verdict: String,        // ok / rejected / fail / unavailable / timeout
    val reachable: Boolean,     // the QRTR NAS channel answered at all
    val errorCode: Int? = null, // qmi_error=N from a rejected command, if printed
    val unavailableBeforeWrite: Boolean = false,
) {
    val accepted: Boolean get() = exit == 0 && verdict == "ok"

    companion object {
        private val errorPattern = Regex("qmi_error=(\\d+)")

        fun unavailable() = QmiLockOutcome(1, "", "unavailable", false, unavailableBeforeWrite = true)

        fun read(exit: Int, output: String, command: String): QmiLockOutcome {
            val lines = output.lineSequence().map(String::trim).toList()
            val verdict = lines.lastOrNull { it.startsWith("VERDICT ") }
                ?.removePrefix("VERDICT ")?.substringBefore(' ')
            // Native exit 3 with this exact message occurs before nas_exchange().
            val missingService = verdict == null && exit == 3 &&
                lines.any { it == "QMI $command fail=lookup" || it == "QMI $command fail=dms_lookup" }
            val errorCode = lines.mapNotNull { errorPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                .lastOrNull()
            return QmiLockOutcome(
                exit, output, verdict ?: if (missingService) "unavailable" else "fail",
                verdict == "ok" || verdict == "rejected", errorCode, missingService,
            )
        }
    }
}
