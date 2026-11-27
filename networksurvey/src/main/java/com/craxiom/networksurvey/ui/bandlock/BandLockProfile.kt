package com.craxiom.networksurvey.ui.bandlock

/**
 * Multi-device band-lock support classification.
 * Locked commands are only offered when a live DIAG channel probe succeeds.
 * Device names never override a failed probe, and a successful probe on an
 * untested model is not treated as a verified layout.
 */
internal enum class LockSupportLevel { Verified, ProbeOnly, Unsupported }

internal data class BandLockProfile(
    val level: LockSupportLevel,
    val adapter: String,
    val channel: String = "",
    val reason: String = "",
) {
    val canLock get() = level == LockSupportLevel.Verified || level == LockSupportLevel.ProbeOnly
}

internal object BandLockProfiles {
    private val verifiedModels = setOf("SM-S9370")
    private val verifiedSocs = setOf("SM8750", "SUN")

    fun classify(
        manufacturer: String,
        model: String,
        soc: String,
        hardware: String,
        channel: String?,
        capsPositive: Int,
    ): BandLockProfile {
        val brand = manufacturer.lowercase()
        val socKey = soc.uppercase()
        val blob = "$manufacturer $model $soc $hardware".lowercase()
        val mediatek = containsAny(blob, "mediatek", " dimensity", " helio", " mt67", " mt68", " mt69", "mtk")
        val exynos = containsAny(blob, "exynos")
        val kirin = containsAny(blob, "kirin", "hisi")
        val channelName = channel?.trim().orEmpty()

        if (channelName.isEmpty()) {
            val reason = when {
                mediatek -> "mediatek_unsupported"
                exynos -> "exynos_unsupported"
                kirin -> "kirin_unsupported"
                else -> "no_diag_channel"
            }
            return BandLockProfile(LockSupportLevel.Unsupported, adapterOf(blob, brand), reason = reason)
        }
        if (mediatek) return BandLockProfile(LockSupportLevel.Unsupported, "mediatek", channelName, "mediatek_unsupported")
        if (kirin) return BandLockProfile(LockSupportLevel.Unsupported, "hisilicon", channelName, "kirin_unsupported")

        val verified = model.uppercase() in verifiedModels || socKey in verifiedSocs
        val adapter = "qualcomm-diag"
        if (capsPositive <= 0) {
            return BandLockProfile(LockSupportLevel.Unsupported, adapter, channelName, "no_lock_capability")
        }
        return if (verified) {
            BandLockProfile(LockSupportLevel.Verified, adapter, channelName)
        } else {
            BandLockProfile(LockSupportLevel.ProbeOnly, adapter, channelName, "unverified_model")
        }
    }

    private fun adapterOf(blob: String, brand: String) = when {
        containsAny(blob, "mediatek", "mtk", "dimensity") -> "mediatek"
        containsAny(blob, "exynos") -> "exynos"
        containsAny(blob, "kirin", "hisi") -> "hisilicon"
        containsAny(blob, "qualcomm", "snapdragon", "sm8", "sm7", "qcom") -> "qualcomm"
        brand.contains("samsung") -> "unknown"
        else -> "unknown"
    }

    private fun containsAny(blob: String, vararg keys: String) = keys.any { blob.contains(it) }
}
