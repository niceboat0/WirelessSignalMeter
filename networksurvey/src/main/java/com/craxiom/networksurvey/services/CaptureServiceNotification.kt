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
import org.json.JSONArray
import org.json.JSONObject

/** Two foreground services can publish the same notification without sharing their lifecycles. */
internal object CaptureServiceNotification {
    private const val ID = NetworkSurveyConstants.LOGGING_NOTIFICATION_ID

    private var surveyText: String? = null
    private var diagTask: Boolean? = null

    @JvmStatic
    @Synchronized
    fun startSurvey(service: Service, type: Int, text: String) {
        start(service, type, text, diagTask)
        surveyText = text
    }

    @Synchronized
    fun startDiag(service: Service, type: Int, task: Boolean) {
        start(service, type, surveyText, task)
        diagTask = task
    }

    private fun start(service: Service, type: Int, survey: String?, diag: Boolean?) {
        Application.createNotificationChannel(service)
        ServiceCompat.startForeground(service, ID, build(service, survey, diag), type)
    }

    @Synchronized
    fun updateDiag(context: Context, task: Boolean) {
        if (diagTask == null || diagTask == task) return
        diagTask = task
        refresh(context)
    }

    @JvmStatic
    @Synchronized
    fun stopSurvey(service: Service) {
        if (surveyText == null) return
        surveyText = null
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        refresh(service)
    }

    @Synchronized
    fun stopDiag(service: Service) {
        if (diagTask == null) return
        diagTask = null
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        refresh(service)
    }

    private fun refresh(context: Context) {
        if (diagTask == null && surveyText == null) return
        context.getSystemService(NotificationManager::class.java)
            ?.notify(ID, build(context, surveyText, diagTask))
    }

    private fun build(context: Context, survey: String?, diag: Boolean?) =
        NotificationCompat.Builder(context, NetworkSurveyConstants.NOTIFICATION_CHANNEL_ID).apply {
            setSmallIcon(R.drawable.logging_thick_icon)
            setContentTitle(context.getString(when (diag) {
                true -> R.string.diag_bg_notification_title_task
                false -> R.string.diag_bg_notification_title_listen
                null -> R.string.network_survey_notification_title
            }))
            val diagText = diag?.let {
                context.getString(if (it) R.string.diag_bg_notification_text_task
                    else R.string.diag_bg_notification_text_listen)
            }
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
            .put("survey_foreground", surveyText != null).put("diag_foreground", diagTask != null)
            .put("allowed", manager?.areNotificationsEnabled() ?: false)
            .put("active", JSONArray(active.map {
                JSONObject().put("id", it.id).put("channel", it.notification.channelId)
                    .put("category", it.notification.category).put("flags", it.notification.flags)
            }))
    }
}
