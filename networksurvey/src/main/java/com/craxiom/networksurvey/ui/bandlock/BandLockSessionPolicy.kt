package com.craxiom.networksurvey.ui.bandlock

/**
 * How a band-lock command reaches the modem.
 *
 * The live DIAG collector already proved root (`id -u`) and a working DIAG
 * session. Band lock must not launch another `su` or scan diag-router
 * transports just to prove that again. Capability and backup reads still
 * happen, on one long-lived helper: the first check may open a raw
 * diag-router client, and every later command reuses it.
 *
 * That helper is not the collector process. The collector speaks libdiag DCI
 * and its stdin is only the QMDL start/stop channel. Mixing EFS and NV writes
 * into that process would stall the live log stream, so lock frames stay on
 * their own socket. The screen-level handoff pauses the collector while this
 * raw socket is in use on routers that do not support both clients together.
 */
internal enum class BandLockTransport {
    /** Start the long-lived helper and register with a supported router. */
    OpenSession,
    /** Reuse the helper; it re-registers if the modem closed the old connection. */
    ReuseSession,
}

internal data class BandLockPlan(
    val transport: BandLockTransport,
    /** Show 「正在检测 Root 与 DIAG」. False once the collector or a helper is up. */
    val announceRootDiagCheck: Boolean,
    /** Begin with discovery instead of trying a cached channel or a live helper first. */
    val discoverChannel: Boolean,
    /** Launch `su`. False when the helper process is already connected. */
    val spawnRootShell: Boolean,
    /**
     * A probe still has to read lock items and backups. The collector being
     * up does not by itself authorize a write.
     */
    val checksCapabilityAndBackup: Boolean,
    val knownChannel: String?,
)

internal object BandLockSessionPolicy {
    private val channelName = Regex("[a-z0-9-]{1,64}")

    fun normalizedChannel(channel: String?): String? =
        channel?.takeIf { channelName.matches(it) }

    /** Keep vendor transport names internal; the lock UI shows the generic DIAG label. */
    fun displayChannel(channel: String?): String =
        channel?.trim()?.let {
            if (it.startsWith("samsung-", ignoreCase = true)) it.substring(8) else it
        }.orEmpty()

    fun plan(
        collectorRunning: Boolean,
        helperConnected: Boolean,
        knownChannel: String?,
        kind: LockWriteKind,
    ): BandLockPlan {
        val channel = normalizedChannel(knownChannel)
        val transport = if (helperConnected) BandLockTransport.ReuseSession else BandLockTransport.OpenSession
        val provingDiscovery = !collectorRunning && !helperConnected
        return BandLockPlan(
            transport = transport,
            announceRootDiagCheck = provingDiscovery && kind == LockWriteKind.Probe,
            discoverChannel = transport == BandLockTransport.OpenSession && channel == null,
            spawnRootShell = transport == BandLockTransport.OpenSession,
            checksCapabilityAndBackup = kind == LockWriteKind.Probe,
            knownChannel = channel.takeIf { transport == BandLockTransport.OpenSession },
        )
    }

    /** One command per stdin frame. Arguments stay off the shell. */
    fun encodeSessionCommand(arguments: List<String>): String {
        require(arguments.isNotEmpty() && arguments.size <= 12) { "Invalid lock command." }
        require(arguments.all { argument ->
            argument.isNotEmpty() && argument.length <= 240 && '\n' !in argument && '\r' !in argument
        }) { "Invalid lock command." }
        return buildString {
            append("CMD ").append(arguments.size).append('\n')
            arguments.forEach { append(it).append('\n') }
        }
    }

    fun sessionExitCode(line: String): Int? {
        if (!line.startsWith("SESSION exit ")) return null
        return line.removePrefix("SESSION exit ").trim().toIntOrNull()
    }

    const val SESSION_CONNECTED = "SESSION connected"
    const val SESSION_CLOSE = "session-close"
}

internal interface BandLockCommandRunner {
    val helperConnected: Boolean
    fun close()
    suspend fun run(
        command: BandLockCommand,
        plan: BandLockPlan,
        onLine: (String) -> Unit,
        diagnostic: (String) -> Unit,
    ): BandLockOutcome
}
