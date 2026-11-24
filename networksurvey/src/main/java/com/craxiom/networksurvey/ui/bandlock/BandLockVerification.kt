package com.craxiom.networksurvey.ui.bandlock

import com.google.gson.JsonParser
import java.io.File

internal enum class LockWriteKind {
    Probe,
    Apply,
    RestoreLteBands,
    UnlockNrBands,
    ClearLock,
    ChangeMode,
    Console,
}

internal enum class WriteDecision {
    Allowed,
    Busy,
    NotLiveConfirmed,
    BackupMissing,
    BackupUnreadable,
    BackupCorrupt,
    BackupMismatch,
}

internal enum class BackupFileState { Missing, NotRegular, Unreadable, UnexpectedSize, Empty, Usable }

internal data class DeviceIdentity(val model: String, val soc: String, val fingerprint: String)

internal data class CachedProbe(
    val channel: String,
    val caps: List<Pair<String, Boolean>>,
    val readyHint: Boolean,
    val lteBackupHint: Boolean,
    val nrBackupHint: Boolean,
)

internal data class FileView(
    val exists: Boolean,
    val isRegular: Boolean,
    val readable: Boolean,
    val bytes: ByteArray?,
    val size: Long? = bytes?.size?.toLong(),
)

internal object BandLockVerification {
    const val LTE_BACKUP = "lte-original.bin"
    const val NR_BACKUP = "nr-original.bin"
    const val NR_NSA_BACKUP = "nr-nsa-original.bin"
    const val ORIGIN_NAME = "backup-origin"
    const val LTE_BACKUP_BYTES = 8
    const val NR_BACKUP_BYTES = 64

    fun cacheKey(versionCode: Int, fingerprint: String, soc: String) =
        "probe:$versionCode:$fingerprint:$soc"

    fun cacheApplies(storedKey: String?, currentKey: String): Boolean =
        !storedKey.isNullOrEmpty() && storedKey == currentKey

    fun parseCachedProbe(text: String): CachedProbe? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) parseLegacyJsonCache(trimmed) else parsePropertyCache(trimmed)
    }

    fun encodeCache(probe: CachedProbe, liveReady: Boolean, lteBackup: Boolean, nrBackup: Boolean): String? {
        if (!safeToken(probe.channel) || probe.caps.any { !safeToken(it.first) }) return null
        return buildString {
            appendLine("ready=$liveReady")
            appendLine("channel=${probe.channel}")
            appendLine("lte_backup=$lteBackup")
            appendLine("nr_backup=$nrBackup")
            probe.caps.forEach { (key, value) -> appendLine("cap.$key=$value") }
        }
    }

    private fun parsePropertyCache(text: String): CachedProbe? {
        val values = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }
            .toMap()
        val channel = values["channel"]?.takeIf(::safeToken) ?: return null
        val ready = values["ready"]?.toBooleanStrictOrNull() ?: return null
        val caps = values.mapNotNull { (key, value) ->
            key.removePrefix("cap.").takeIf { key.startsWith("cap.") && safeToken(it) }?.let { name ->
                name to (value.toBooleanStrictOrNull() ?: return@mapNotNull null)
            }
        }
        return CachedProbe(
            channel = channel,
            caps = caps,
            readyHint = ready,
            lteBackupHint = values["lte_backup"]?.toBooleanStrictOrNull() ?: false,
            nrBackupHint = values["nr_backup"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun parseLegacyJsonCache(text: String): CachedProbe? = runCatching {
        val parsed = JsonParser.parseString(text)
        if (!parsed.isJsonObject) return@runCatching null
        val cache = parsed.asJsonObject
        fun boolean(name: String): Boolean? {
            val value = cache.get(name) ?: return null
            return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        }
        val ready = boolean("ready") ?: return@runCatching null
        val rawChannel = cache.get("channel") ?: return@runCatching null
        if (!rawChannel.isJsonPrimitive || !rawChannel.asJsonPrimitive.isString) return@runCatching null
        val channel = rawChannel.asString.takeIf(::safeToken) ?: return@runCatching null
        val rawCaps = cache.get("caps") ?: return@runCatching null
        if (!rawCaps.isJsonObject) return@runCatching null
        val caps = rawCaps.asJsonObject.entrySet().map { (name, value) ->
            if (!safeToken(name) || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
                return@runCatching null
            }
            name to value.asBoolean
        }
        CachedProbe(
            channel = channel,
            caps = caps,
            readyHint = ready,
            lteBackupHint = if (cache.has("lte_backup")) boolean("lte_backup") ?: return@runCatching null else false,
            nrBackupHint = if (cache.has("nr_backup")) boolean("nr_backup") ?: return@runCatching null else false,
        )
    }.getOrNull()

    fun writeKind(command: BandLockCommand): LockWriteKind =
        when (command.arguments.firstOrNull()) {
            "probe" -> LockWriteKind.Probe
            "lte-band-restore" -> LockWriteKind.RestoreLteBands
            "nr-band-restore" -> LockWriteKind.UnlockNrBands
            "lte-freq-clear", "nr-freq-clear", "lte-cell-clear", "nr-cell-clear" -> LockWriteKind.ClearLock
            "mode" -> LockWriteKind.ChangeMode
            "lte-band", "nr-band", "lte-freq", "nr-freq", "lte-cell", "nr-cell" -> LockWriteKind.Apply
            else -> LockWriteKind.Console
        }

    suspend fun <T> executeWhenConfirmed(
        availability: LockAvailability,
        kind: LockWriteKind,
        confirm: suspend () -> Boolean,
        execute: suspend () -> T,
    ): T? {
        if (kind != LockWriteKind.Probe && availability != LockAvailability.Ready) {
            if (availability != LockAvailability.Cached || !confirm()) return null
        }
        return execute()
    }

    fun inspectBackup(
        view: FileView,
        expectedSize: Int,
        nativeConfirmed: Boolean = false,
    ): BackupFileState {
        if (!view.exists) return BackupFileState.Missing
        if (!view.isRegular) return BackupFileState.NotRegular
        if (view.size != null && view.size != expectedSize.toLong()) return BackupFileState.UnexpectedSize
        if (view.bytes != null && view.bytes.size != expectedSize) return BackupFileState.UnexpectedSize
        if (!view.readable || view.bytes == null) {
            // Root creates private backups with mode 0600; the helper validates them again before restore.
            return if (nativeConfirmed && view.size == expectedSize.toLong()) BackupFileState.Usable
            else BackupFileState.Unreadable
        }
        if (view.bytes.all { it == 0.toByte() }) return BackupFileState.Empty
        return BackupFileState.Usable
    }

    fun inspectBackup(file: File, expectedSize: Int, nativeConfirmed: Boolean = false): BackupFileState {
        val exists = file.exists()
        val regular = exists && file.isFile
        val readable = regular && file.canRead()
        val size = file.length().takeIf { regular }
        val bytes = if (readable && size == expectedSize.toLong()) runCatching { file.readBytes() }.getOrNull() else null
        return inspectBackup(
            FileView(exists, regular, readable && bytes != null, bytes, size),
            expectedSize, nativeConfirmed,
        )
    }

    fun encodeOrigin(identity: DeviceIdentity): String? {
        if (!safeToken(identity.model) || !safeToken(identity.soc) || !safeToken(identity.fingerprint)) return null
        return "model=${identity.model}\nsoc=${identity.soc}\nfingerprint=${identity.fingerprint}\n"
    }

    fun parseOrigin(text: String): DeviceIdentity? {
        val values = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }
            .toMap()
        val model = values["model"]?.takeIf(::safeToken) ?: return null
        val soc = values["soc"]?.takeIf(::safeToken) ?: return null
        val fingerprint = values["fingerprint"]?.takeIf(::safeToken) ?: return null
        return DeviceIdentity(model, soc, fingerprint)
    }

    fun originMatches(saved: DeviceIdentity, current: DeviceIdentity): Boolean =
        saved.model == current.model && saved.soc == current.soc && saved.fingerprint == current.fingerprint

    fun readOrigin(directory: File): DeviceIdentity? {
        val file = File(directory, ORIGIN_NAME)
        if (!file.isFile) return null
        return runCatching { parseOrigin(file.readText()) }.getOrNull()
    }

    fun decideWrite(
        availability: LockAvailability,
        busy: Boolean,
        kind: LockWriteKind,
        lteBackup: BackupFileState = BackupFileState.Missing,
        origin: DeviceIdentity? = null,
        current: DeviceIdentity? = null,
    ): WriteDecision {
        if (kind == LockWriteKind.Probe) return if (busy) WriteDecision.Busy else WriteDecision.Allowed
        if (busy) return WriteDecision.Busy
        if (availability != LockAvailability.Ready) return WriteDecision.NotLiveConfirmed
        if (kind in setOf(LockWriteKind.Apply, LockWriteKind.RestoreLteBands, LockWriteKind.UnlockNrBands) &&
            origin != null && current != null && !originMatches(origin, current)) return WriteDecision.BackupMismatch
        if (kind != LockWriteKind.RestoreLteBands) return WriteDecision.Allowed
        return when (lteBackup) {
            BackupFileState.Usable -> WriteDecision.Allowed
            BackupFileState.Missing -> WriteDecision.BackupMissing
            BackupFileState.Unreadable, BackupFileState.NotRegular -> WriteDecision.BackupUnreadable
            BackupFileState.UnexpectedSize, BackupFileState.Empty -> WriteDecision.BackupCorrupt
        }
    }

    fun resultMessage(decision: WriteDecision): Int? = when (decision) {
        WriteDecision.Allowed, WriteDecision.Busy -> null
        WriteDecision.NotLiveConfirmed -> com.craxiom.networksurvey.R.string.lock_write_blocked_unconfirmed
        WriteDecision.BackupMissing -> com.craxiom.networksurvey.R.string.lock_restore_blocked_missing
        WriteDecision.BackupUnreadable -> com.craxiom.networksurvey.R.string.lock_restore_blocked_unreadable
        WriteDecision.BackupCorrupt -> com.craxiom.networksurvey.R.string.lock_restore_blocked_corrupt
        WriteDecision.BackupMismatch -> com.craxiom.networksurvey.R.string.lock_restore_blocked_mismatch
    }

    fun rootRevoked(output: String): Boolean = output.lineSequence().any { line ->
        val text = line.trim()
        if (text == "Root authorization is required.") return@any true
        if (!text.startsWith("su:", ignoreCase = true)) return@any false
        text.substringAfter(':').trim().lowercase() in setOf(
            "permission denied", "not allowed", "authorization denied", "access denied",
            "root access denied", "user denied", "request rejected",
        )
    }

    fun backupCaption(
        availability: LockAvailability,
        radio: LockRadio,
        confirmed: Boolean,
        cachedHint: Boolean,
    ): BackupCaption = when {
        availability == LockAvailability.Cached || availability == LockAvailability.Unchecked ||
            availability == LockAvailability.Checking ->
            if (cachedHint) BackupCaption.CachedHint else BackupCaption.NeedsRecheck
        confirmed -> BackupCaption.Confirmed
        else -> BackupCaption.Needed
    }

    private fun safeToken(value: String): Boolean =
        value.isNotEmpty() && value.length < 256 && '\n' !in value && '\r' !in value
}

internal enum class BackupCaption { CachedHint, NeedsRecheck, Confirmed, Needed }
