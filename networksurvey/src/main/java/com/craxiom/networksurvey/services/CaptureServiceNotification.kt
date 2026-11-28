package com.craxiom.networksurvey.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.craxiom.networksurvey.Application
import com.craxiom.networksurvey.NetworkSurveyActivity
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.constants.NetworkSurveyConstants
import com.craxiom.networksurvey.ui.rootdiag.diagPresenceDetail
import com.craxiom.networksurvey.ui.rootdiag.diagPresenceHeadline
import org.json.JSONArray
import org.json.JSONObject

/** Two foreground services can publish the same notification without sharing their lifecycles. */
internal object CaptureServiceNotification {
    private const val ID = NetworkSurveyConstants.LOGGING_NOTIFICATION_ID

    private var surveyText: String? = null
    private var diagPresence: String? = null

    @JvmStatic
    @Synchronized
    fun startSurvey(service: Service, type: Int, text: String) {
        start(service, type, text, diagPresence)
        surveyText = text
    }

    @Synchronized
    fun startDiag(service: Service, type: Int, presence: String) {
        start(service, type, surveyText, presence)
        diagPresence = presence
    }

    private fun start(service: Service, type: Int, survey: String?, diag: String?) {
        Application.createNotificationChannel(service)
        ServiceCompat.startForeground(service, ID, build(service, survey, diag), type)
    }

    @Synchronized
    fun updateDiag(context: Context, presence: String) {
        if (diagPresence == null || diagPresence == presence) return
        diagPresence = presence
        refresh(context)
    }

    @JvmStatic
    @Synchronized
    fun stopSurvey(service: Service) {
        if (surveyText == null) return
        surveyText = null
        ServiceCompat.stopForeground(
            service,
            if (diagPresence != null) ServiceCompat.STOP_FOREGROUND_DETACH
            else ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        refresh(service)
    }

    @Synchronized
    fun stopDiag(service: Service) {
        if (diagPresence == null) return
        diagPresence = null
        ServiceCompat.stopForeground(
            service,
            if (surveyText != null) ServiceCompat.STOP_FOREGROUND_DETACH
            else ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        refresh(service)
    }

    private fun refresh(context: Context) {
        if (diagPresence == null && surveyText == null) return
        context.getSystemService(NotificationManager::class.java)
            ?.notify(ID, build(context, surveyText, diagPresence))
    }

    private fun build(context: Context, survey: String?, diag: String?) =
        NotificationCompat.Builder(context, NetworkSurveyConstants.NOTIFICATION_CHANNEL_ID).apply {
            setSmallIcon(R.drawable.logging_thick_icon)
            setContentTitle(context.getString(
                if (diag == null) R.string.network_survey_notification_title else diagPresenceHeadline(diag),
            ))
            val diagText = diag?.let { context.getString(diagPresenceDetail(it)) }
            val text = listOfNotNull(diagText, survey?.takeIf { it.isNotBlank() }).joinToString("\n")
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setContentIntent(PendingIntent.getActivity(
                context, 0, Intent(context, NetworkSurveyActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ))
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setOngoing(true)
            setSilent(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            if (diag != null) {
                val stop = PendingIntent.getService(context, 1,
                    Intent(context, RootDiagCollectorService::class.java).setAction(RootDiagCollectorService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                addAction(0, context.getString(R.string.diag_bg_stop), stop)
            }
        }.build()

    @Synchronized
    fun diagnostics(context: Context): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { manager?.activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        return JSONObject().put("type", "capture_notifications")
            .put("survey_foreground", surveyText != null).put("diag_foreground", diagPresence != null)
            .put("allowed", manager?.areNotificationsEnabled() ?: false)
            .put("active", JSONArray(active.map {
                JSONObject().put("id", it.id).put("channel", it.notification.channelId)
                    .put("category", it.notification.category).put("flags", it.notification.flags)
            }))
    }
}
