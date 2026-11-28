package com.craxiom.networksurvey.util

import android.os.Build

/**
 * Normalizes SoC identity across Android releases.
 *
 * Build.SOC_MODEL and Build.SOC_MANUFACTURER were added in API 31. Keep
 * callers free of direct references so the API 30 compatibility branch can
 * load diagnostics and band-lock screens safely.
 */
object DeviceSoc {
    val model: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeUnless { it.isBlank() } ?: fallbackModel()
        } else {
            fallbackModel()
        }

    val manufacturer: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.takeUnless { it.isBlank() } ?: Build.MANUFACTURER
        } else {
            Build.MANUFACTURER
        }

    private fun fallbackModel(): String =
        listOf(Build.HARDWARE, Build.BOARD, Build.PRODUCT)
            .firstOrNull { it.isNotBlank() }
            ?: "unknown"
}
