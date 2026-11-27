package com.craxiom.networksurvey.ui.bandlock

/**
 * Argument builders for the QMI NAS instant-lock path (qmi-probe over QRTR).
 *
 * This backend is independent of the NV/EFS route: it takes effect
 * immediately without a modem restart, and its persistence is not promised
 * (unverified after SSR). Bands are the same selections the NV route uses;
 * only the wire format differs (bit band-1, little endian).
 */
internal object QmiLockCommand {
    fun probe(): List<String> = listOf("bands-get")

    /** LTE mask as a 64-bit value, NR mask as 64 little-endian bytes. */
    fun applyBands(radio: LockRadio, bandsText: String): List<String> {
        val numbers = bandNumbers(bandsText, radio.maxBand)
        require(numbers.isNotEmpty()) { "no bands selected" }
        return if (radio == LockRadio.Lte) {
            val mask = numbers.fold(0L) { acc, band -> acc or (1L shl (band - 1)) }
            listOf("bands-set", mask.toULong().toString(16).padStart(16, '0'))
        } else {
            val bytes = ByteArray(64)
            numbers.forEach { band ->
                val bit = band - 1
                bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            val hex = buildString {
                for (i in bytes.indices.reversed()) append(String.format("%02x", bytes[i]))
            }
            /* Pass "-" so NR-only locking leaves the LTE mask untouched instead
               of sending an all-ones LTE mask that some modems reject (QMI 3). */
            listOf("bands-set", "-", hex)
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
    fun restoreBands(): List<String> = listOf("bands-restore")

    fun lockCell(radio: LockRadio, channel: String, pci: String, scs: Int): List<String> {
        val frequency = channel.trim().toIntOrNull() ?: error("invalid ARFCN")
        val cell = pci.trim().toIntOrNull() ?: error("invalid PCI")
        return if (radio == LockRadio.Lte) {
            listOf("celllte-set", "$cell:$frequency")
        } else {
            listOf("cellnr-set", cell.toString(), frequency.toString(), scs.toString())
        }
    }

    fun clearCell(radio: LockRadio): List<String> =
        if (radio == LockRadio.Lte) listOf("celllte-clear") else listOf("cellnr-clear")

    fun readCell(radio: LockRadio): List<String> =
        if (radio == LockRadio.Lte) listOf("celllte-get") else listOf("cellnr-get")

    private fun bandNumbers(text: String, maxBand: Int): List<Int> {
        val tokens = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        require(tokens.isNotEmpty() && tokens.size <= maxBand) { "no bands selected" }
        return tokens.map { token ->
            val raw = token.trim().removePrefix("b").removePrefix("B").removePrefix("n").removePrefix("N")
            val band = raw.toIntOrNull() ?: error("invalid band $token")
            require(band in 1..maxBand) { "invalid band $token" }
            band
        }.distinct().sorted()
    }
}

/** Outcome of one qmi-probe run. */
internal data class QmiLockOutcome(
    val exit: Int,
    val output: String,
    val verdict: String,        // ok / rejected / fail / unavailable / timeout
    val reachable: Boolean,     // the QRTR NAS channel answered at all
    val errorCode: Int? = null, // qmi_error=N from a rejected command, if printed
) {
    val accepted: Boolean get() = verdict == "ok"
}