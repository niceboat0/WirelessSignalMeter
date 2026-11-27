package com.craxiom.networksurvey.ui.rootdiag

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.craxiom.networksurvey.R

internal fun capabilityReasonId(reason: String): Int? = when (reason) {
    "ok" -> R.string.diag_reason_ok
    "checking" -> R.string.diag_reason_checking
    "no_su" -> R.string.diag_reason_no_su
    "root_denied" -> R.string.diag_reason_root_denied
    "no_libdiag" -> R.string.diag_reason_no_libdiag
    "missing_libdiag_symbols" -> R.string.diag_reason_missing_libdiag_symbols
    "diag_lsm_init_failed" -> R.string.diag_reason_diag_lsm_init_failed
    "dci_register_failed" -> R.string.diag_reason_dci_register_failed
    "dci_stream_or_mask_failed" -> R.string.diag_reason_dci_stream_or_mask_failed
    "collector_stopped" -> R.string.diag_reason_collector_stopped
    "abi_unsupported" -> R.string.diag_reason_abi_unsupported
    "permission_denied" -> R.string.diag_reason_permission_denied
    "api_too_low" -> R.string.diag_reason_api_too_low
    "no_subscription" -> R.string.diag_reason_no_subscription
    "ims_unavailable" -> R.string.diag_reason_ims_unavailable
    "no_samsung_ims_tags" -> R.string.diag_reason_no_samsung_ims_tags
    "no_backend" -> R.string.diag_reason_no_backend
    "ims_pipeline_unsupported" -> R.string.diag_reason_no_backend
    "ims_pipeline_ready" -> R.string.diag_ims_pipeline_ready
    "ims_pipeline_subscribed" -> R.string.diag_ims_pipeline_subscribed
    "ims_pipeline_starting" -> R.string.diag_ims_pipeline_starting
    "ims_pipeline_reconnecting" -> R.string.diag_ims_pipeline_reconnecting
    "ims_pipeline_privileged" -> R.string.diag_ims_pipeline_privileged
    "ims_pipeline_unavailable" -> R.string.diag_ims_pipeline_unavailable
    "logcat_exited" -> R.string.diag_reason_logcat_exited
    "mediatek_unsupported" -> R.string.lock_reason_mediatek
    "exynos_unsupported" -> R.string.lock_reason_exynos
    "kirin_unsupported" -> R.string.lock_reason_kirin
    "unverified_model" -> R.string.lock_reason_unverified
    "no_diag_channel" -> R.string.lock_reason_no_channel
    "no_lock_capability" -> R.string.lock_reason_no_capability
    "verified" -> R.string.diag_reason_ok
    else -> null
}

internal fun capabilityReasonText(context: Context, reason: String): String =
    capabilityReasonId(reason)?.let(context::getString) ?: reason

@Composable
internal fun capabilityStateText(state: CapabilityState): String = stringResource(
    when (state) {
        CapabilityState.Available -> R.string.diag_cap_available
        CapabilityState.Degraded -> R.string.diag_cap_degraded
        CapabilityState.Unavailable -> R.string.diag_cap_unavailable
        CapabilityState.Unsupported -> R.string.diag_cap_unsupported
    }
)

@Composable
internal fun capabilityReasonText(reason: String): String =
    capabilityReasonText(LocalContext.current, reason)

@Composable
internal fun capabilityLine(item: CapabilityItem): String {
    val label = when (item.id) {
        "telephony" -> stringResource(R.string.diag_capability_telephony)
        "root" -> stringResource(R.string.diag_capability_root)
        "qualcomm_diag" -> stringResource(R.string.diag_capability_diag)
        "band_lock" -> stringResource(R.string.diag_capability_lock)
        else -> item.id
    }
    return "$label · ${capabilityStateText(item.state)} · ${capabilityReasonText(item.reason)}"
}


internal fun imsHeadlineId(key: String): Int = when (key) {
    "ims_unknown_samsung_ready" -> R.string.diag_ims_headline_unknown_samsung_ready
    "ims_unknown_waiting" -> R.string.diag_ims_headline_unknown_waiting
    "ims_unknown_reconnecting" -> R.string.diag_ims_headline_unknown_reconnecting
    "ims_registered" -> R.string.diag_ims_headline_registered
    "ims_registered_inferred" -> R.string.diag_ims_headline_registered_inferred
    "ims_deregistered" -> R.string.diag_ims_headline_deregistered
    "ims_failed" -> R.string.diag_ims_headline_failed
    "ims_unavailable_privileged" -> R.string.diag_ims_headline_unavailable_privileged
    "ims_unavailable_no_source" -> R.string.diag_ims_headline_unavailable_no_source
    else -> R.string.diag_ims_headline_unknown_waiting
}

internal fun imsStatusLine(context: Context, summary: ImsStatusSummary): String {
    val headline = context.getString(imsHeadlineId(summary.headlineKey))
    val call = when (summary.call) {
        "calling" -> context.getString(R.string.diag_ims_call_calling)
        "alerting" -> context.getString(R.string.diag_ims_call_alerting)
        "connected" -> context.getString(R.string.diag_ims_call_connected)
        else -> ""
    }
    val line = context.getString(R.string.diag_ims_line, headline)
    return if (call.isBlank()) line else "$line · $call"
}

@Composable
internal fun imsStatusLine(summary: ImsStatusSummary): String =
    imsStatusLine(LocalContext.current, summary)
