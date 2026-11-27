package com.craxiom.networksurvey.ui.rootdiag

/**
 * Limited reconnect policy for the root DIAG collector.
 * Denied root or a missing libdiag is not retried automatically.
 * Transient drops after a successful start, or diag-router init/register
 * failures, back off instead of spinning.
 */
internal object CollectorRestartPolicy {
    const val MAX_ATTEMPTS = 6
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 60_000L
    const val MODEM_RECOVERY_INTERVAL_MS = 12_000L
    const val MODEM_RECOVERY_CHECKS = 4
    const val MAX_MODEM_RECOVERY_RESTARTS = 2
    const val MODEM_PACKET_STALE_MS = 10_000L
    const val STATUS_STALE_MS = 5_000L

    fun needsModemRecovery(
        statusRunning: Boolean, statusAgeMs: Long, packetAgeMs: Long?, sinceRequestMs: Long,
    ): Boolean = !statusRunning || statusAgeMs !in 0..STATUS_STALE_MS ||
        packetAgeMs == null || packetAgeMs !in 0 until minOf(MODEM_PACKET_STALE_MS, sinceRequestMs)

    /** An open recording does not postpone a stale collector. The segment is archived first. */
    fun modemRecoveryPhase(stale: Boolean, check: Int, restarts: Int): String = when {
        !stale -> "packets_resumed"
        check == MODEM_RECOVERY_CHECKS -> "still_stalled"
        restarts >= MAX_MODEM_RECOVERY_RESTARTS -> "waiting_after_restarts"
        else -> "restarting_collector"
    }

    fun isTransient(status: RootDiagStatus, errorCode: Int?, hadBeenRunning: Boolean): Boolean {
        if (status == RootDiagStatus.Denied || status == RootDiagStatus.Unavailable) return false
        if (errorCode == 2 || errorCode == 3) return false
        if (hadBeenRunning) return true
        return status == RootDiagStatus.Unsupported && errorCode in setOf(4, 5, 6)
    }

    fun shouldRetry(
        status: RootDiagStatus,
        errorCode: Int?,
        hadBeenRunning: Boolean,
        attempt: Int,
    ): Boolean = attempt < MAX_ATTEMPTS && isTransient(status, errorCode, hadBeenRunning)

    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 5)
        val delay = BASE_DELAY_MS shl shift
        return if (delay > MAX_DELAY_MS) MAX_DELAY_MS else delay
    }

    fun collectorReason(status: RootDiagStatus, errorCode: Int?): String = when (status) {
        RootDiagStatus.Unavailable -> "no_su"
        RootDiagStatus.Denied -> "root_denied"
        RootDiagStatus.Unsupported -> when (errorCode) {
            2 -> "no_libdiag"
            3 -> "missing_libdiag_symbols"
            4 -> "diag_lsm_init_failed"
            5 -> "dci_register_failed"
            6 -> "dci_stream_or_mask_failed"
            else -> "collector_stopped"
        }
        RootDiagStatus.Stopped -> "stopped"
        RootDiagStatus.Running, RootDiagStatus.Starting, RootDiagStatus.Checking -> "ok"
    }
}
