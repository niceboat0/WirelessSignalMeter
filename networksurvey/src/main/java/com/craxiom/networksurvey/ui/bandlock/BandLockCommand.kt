package com.craxiom.networksurvey.ui.bandlock

import kotlinx.coroutines.delay

internal enum class LockRadio(
    val argument: String, val maxBand: Int, val maxChannel: Int, val maxPci: Int, val mode: LockNetworkMode,
) {
    Lte("lte", 64, 262_143, 503, LockNetworkMode.Lte),
    Nr("nr", 512, 3_279_165, 1007, LockNetworkMode.Nr),
}

internal enum class LockTarget { Bands, Frequency, Cell }
internal enum class LockNetworkMode(val argument: String) { Auto("auto"), Lte("lte"), Nr("nr") }

internal data class BandLockCommand(
    val arguments: List<String>,
    val restartsModem: Boolean,
    val modeAfterLock: LockNetworkMode? = null,
) {
    companion object {
        private fun number(text: String, maximum: Int, minimum: Int = 0): Int {
            require(text.trim().matches(Regex("[0-9]+")))
            return requireNotNull(text.trim().toIntOrNull()).also { require(it in minimum..maximum) }
        }

        fun apply(
            radio: LockRadio, target: LockTarget, bands: String, channel: String, pci: String, scs: Int,
        ): BandLockCommand {
            val prefix = radio.argument
            val args: List<String>
            var instantLteBands = false
            when (target) {
                LockTarget.Bands -> {
                    val tokens = bands.trim().split(Regex("[,;\\s]+"))
                    require(tokens.isNotEmpty() && tokens.size <= radio.maxBand)
                    val numbers = tokens.map { token ->
                        number(token.removePrefix("b").removePrefix("B").removePrefix("n").removePrefix("N"), radio.maxBand, 1)
                    }.distinct().sorted()
                    args = listOf("$prefix-band", numbers.joinToString(","))
                    /* Native sends bands 1..32 as an instant CM frame with no modem
                     * restart; only 33..64 still ride the EFS+restart route. */
                    instantLteBands = radio == LockRadio.Lte && numbers.all { it <= 32 }
                }
                LockTarget.Frequency, LockTarget.Cell -> {
                    val frequency = number(channel, radio.maxChannel, if (radio == LockRadio.Nr) 1 else 0)
                    if (radio == LockRadio.Nr) require(scs in setOf(15, 30, 60, 120))
                    args = if (target == LockTarget.Frequency) {
                        listOfNotNull("$prefix-freq", frequency.toString(), scs.toString().takeIf { radio == LockRadio.Nr })
                    } else {
                        val cell = number(pci, radio.maxPci)
                        if (radio == LockRadio.Lte) listOf("lte-cell", "$frequency:$cell")
                        else listOf("nr-cell", number(bands, radio.maxBand, 1).toString(), frequency.toString(), cell.toString(), scs.toString())
                    }
                }
            }
            return BandLockCommand(args, !instantLteBands, radio.mode)
        }

        fun restore(radio: LockRadio, target: LockTarget) = BandLockCommand(
            listOf("${radio.argument}-${when (target) {
                LockTarget.Bands -> "band-restore"
                LockTarget.Frequency -> "freq-clear"
                LockTarget.Cell -> "cell-clear"
            }}"), true,
        )

        fun mode(mode: LockNetworkMode) = BandLockCommand(listOf("mode", mode.argument), false)

        /** probe carries the device identity so the helper logs it and future
         * per-model profiles can key off the same arguments. */
        fun probe(model: String, soc: String) =
            BandLockCommand(listOf("probe", model, soc), false)

        /** Command console: pass an arbitrary native helper command line
         * verbatim (e.g. "nr-band 1,78,79" or "at /dev/smd0 AT+CGMR"). */
        fun console(text: String): BandLockCommand {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            require(tokens.isNotEmpty())
            /* Conservative: any console command may end in a modem restart;
             * only the read-only probe is known not to. */
            return BandLockCommand(tokens, restartsModem = tokens.first() != "probe")
        }
    }
}

internal data class BandLockOutcome(val exit: Int, val output: String) {
    val baselineUnverified get() = exit != 0 && output.lineSequence().any { it.trimEnd() == "RESULT baseline_unverified" }
    val restartRequested get() = exit == 0 && output.lineSequence().any { it.trimEnd() == "RESULT restart_sent" }
    val accepted get() = restartRequested || exit == 0 && output.lineSequence().any { it.trimEnd() == "RESULT accepted" }
    // band_lock.c returns 2 only when the operation could not connect to DIAG.
    val channelUnavailable get() = exit == 2
}

internal data class BandLockExecution(val outcome: BandLockOutcome, val modeConfirmed: Boolean? = null)

internal fun BandLockCommand.standaloneMode(): LockNetworkMode? =
    if (arguments.firstOrNull() == "mode" && arguments.size == 2) {
        LockNetworkMode.entries.firstOrNull { it.argument == arguments[1] }
    } else null

internal suspend fun BandLockCommand.executeWithMode(
    pause: suspend (Long) -> Unit = { delay(it) },
    onModemRestartRequested: suspend () -> Unit = {},
    executeQmiMode: (suspend (LockNetworkMode) -> QmiLockOutcome)? = null,
    execute: suspend (BandLockCommand) -> BandLockOutcome,
): BandLockExecution {
    val standalone = standaloneMode()
    if (standalone != null && executeQmiMode != null) {
        if (executeQmiMode(standalone).accepted) {
            return BandLockExecution(BandLockOutcome(0, "RESULT accepted\n"))
        }
        return BandLockExecution(execute(this))
    }
    val outcome = execute(this)
    val restart = outcome.restartRequested
    if (restart) onModemRestartRequested()
    val mode = modeAfterLock?.takeIf { outcome.accepted } ?: return BandLockExecution(outcome)
    val maxAttempts = if (restart) 6 else 1
    if (restart) pause(5_000)
    if (executeQmiMode != null && executeQmiMode(mode).accepted) {
        return BandLockExecution(outcome, true)
    }
    repeat(maxAttempts) { attempt ->
        val change = execute(BandLockCommand.mode(mode))
        // Retry only if no command was sent; a missing ACK is not safe to retry.
        if (!change.channelUnavailable || attempt == maxAttempts - 1) {
            return BandLockExecution(outcome, change.accepted)
        }
        pause(2_000)
    }
    return BandLockExecution(outcome, false)
}
