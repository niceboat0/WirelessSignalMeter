package com.craxiom.networksurvey.ui.rootdiag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import java.util.concurrent.Executor
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Maps the public ImsMmTelManager registration states into a source-labeled
 * snapshot. This is the low-privilege fallback: it does not parse vendor
 * logcat or DIAG SIP, and it never stores identifiers.
 */
internal data class AndroidImsSnapshot(
    val subscriptionId: Int,
    val state: String,
    val reason: String,
    val source: String = "android_telephony",
)

internal object AndroidImsRegistration {
    const val STATE_NOT_REGISTERED = 0
    const val STATE_REGISTERING = 1
    const val STATE_REGISTERED = 2

    fun snapshot(subscriptionId: Int, registrationState: Int?, error: String?): AndroidImsSnapshot {
        val (state, reason) = when {
            error != null -> "unavailable" to error
            registrationState == null -> "unknown" to "unknown"
            registrationState == STATE_REGISTERED -> "registered" to "registered"
            registrationState == STATE_REGISTERING -> "registering" to "registering"
            registrationState == STATE_NOT_REGISTERED -> "not_registered" to "not_registered"
            else -> "unknown" to "unrecognized_state"
        }
        return AndroidImsSnapshot(subscriptionId, state, reason)
    }

    fun providerStatus(snapshots: List<AndroidImsSnapshot>, sdk: Int): ImsProviderStatus {
        if (sdk < 30) return ImsProviderStatus("android_telephony", "unavailable", false, "api_too_low")
        val error = snapshots.firstOrNull { it.state == "unavailable" }
        if (snapshots.isEmpty()) return ImsProviderStatus("android_telephony", "unavailable", false, "no_subscription")
        if (error != null && snapshots.all { it.state == "unavailable" }) {
            return ImsProviderStatus("android_telephony", "unavailable", false, error.reason)
        }
        val registered = snapshots.any { it.state == "registered" }
        val registering = snapshots.any { it.state == "registering" }
        return when {
            registered -> ImsProviderStatus("android_telephony", "available", true, "registered")
            registering -> ImsProviderStatus("android_telephony", "starting", null, "registering")
            else -> ImsProviderStatus("android_telephony", "available", true, "not_registered")
        }
    }
}

internal fun readAndroidIms(context: Context): ImsProviderStatus {
    val sdk = Build.VERSION.SDK_INT
    if (sdk < 30) return AndroidImsRegistration.providerStatus(emptyList(), sdk)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED) {
        return ImsProviderStatus("android_telephony", "unavailable", false, "permission_denied")
    }
    return try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val infos = manager?.activeSubscriptionInfoList.orEmpty()
        val imsManager = context.getSystemService(Context.TELEPHONY_IMS_SERVICE) as? ImsManager
            ?: return ImsProviderStatus("android_telephony", "unavailable", false, "ims_unavailable")
        val direct = Executor { runnable -> runnable.run() }
        val snapshots = infos.map { info ->
            try {
                val ims = imsManager.getImsMmTelManager(info.subscriptionId)
                val latch = CountDownLatch(1)
                var state: Int? = null
                ims.getRegistrationState(direct) { value ->
                    state = value
                    latch.countDown()
                }
                if (!latch.await(1_500, TimeUnit.MILLISECONDS)) {
                    AndroidImsRegistration.snapshot(info.subscriptionId, null, "timeout")
                } else {
                    AndroidImsRegistration.snapshot(info.subscriptionId, state, null)
                }
            } catch (_: ImsException) {
                AndroidImsRegistration.snapshot(info.subscriptionId, null, "ims_unavailable")
            } catch (_: SecurityException) {
                AndroidImsRegistration.snapshot(info.subscriptionId, null, "permission_denied")
            } catch (_: RuntimeException) {
                AndroidImsRegistration.snapshot(info.subscriptionId, null, "ims_unavailable")
            }
        }
        AndroidImsRegistration.providerStatus(snapshots, sdk)
    } catch (_: SecurityException) {
        ImsProviderStatus("android_telephony", "unavailable", false, "permission_denied")
    } catch (_: RuntimeException) {
        ImsProviderStatus("android_telephony", "unavailable", false, "ims_unavailable")
    }
}
