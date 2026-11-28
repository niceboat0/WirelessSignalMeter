package com.craxiom.networksurvey.services

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import com.craxiom.networksurvey.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureServiceNotificationTest {
    class RecordingService : Service() {
        override fun onBind(intent: Intent?) = null
    }

    class DiagService : Service() {
        override fun onBind(intent: Intent?) = null
    }

    @Test
    fun stoppingEitherServiceKeepsTheOtherServiceAndItsNotification() {
        val survey = Robolectric.buildService(RecordingService::class.java).create().get()
        val diag = Robolectric.buildService(DiagService::class.java).create().get()
        val manager = survey.getSystemService(NotificationManager::class.java)!!
        fun title() = manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE)
        try {
            CaptureServiceNotification.startSurvey(survey, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "Recording")
            assertEquals(survey.getString(R.string.network_survey_notification_title), title())

            CaptureServiceNotification.startDiag(diag, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "listening")
            assertEquals(shadowOf(survey).lastForegroundNotificationId, shadowOf(diag).lastForegroundNotificationId)
            assertEquals(diag.getString(R.string.diag_overlay_listening), title())

            CaptureServiceNotification.stopSurvey(survey)
            assertTrue(shadowOf(survey).isForegroundStopped)
            assertFalse(shadowOf(diag).isForegroundStopped)
            assertEquals(diag.getString(R.string.diag_overlay_listening), title())

            CaptureServiceNotification.startSurvey(survey, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "Recording")
            CaptureServiceNotification.stopDiag(diag)
            assertTrue(shadowOf(diag).isForegroundStopped)
            assertFalse(shadowOf(survey).isForegroundStopped)
            assertEquals(survey.getString(R.string.network_survey_notification_title), title())
        } finally {
            CaptureServiceNotification.stopSurvey(survey)
            CaptureServiceNotification.stopDiag(diag)
        }
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun notificationUsesTheSamePresenceWordsAsTheOverlay() {
        val diag = Robolectric.buildService(DiagService::class.java).create().get()
        val manager = diag.getSystemService(NotificationManager::class.java)!!
        fun title() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        fun text() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        try {
            CaptureServiceNotification.startDiag(diag, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "recording")
            assertEquals(diag.getString(R.string.diag_overlay_recording), title())
            assertEquals(diag.getString(R.string.diag_bg_notification_text_recording), text())

            CaptureServiceNotification.updateDiag(diag, "task")
            assertEquals(diag.getString(R.string.diag_overlay_task_open), title())
            assertEquals(diag.getString(R.string.diag_bg_notification_text_task), text())

            CaptureServiceNotification.updateDiag(diag, "listening")
            assertEquals(diag.getString(R.string.diag_overlay_listening), title())
            assertEquals(diag.getString(R.string.diag_bg_notification_text_listen), text())

            CaptureServiceNotification.updateDiag(diag, "standby")
            assertEquals(diag.getString(R.string.diag_overlay_standby), title())
            assertEquals(diag.getString(R.string.diag_bg_notification_text_standby), text())

            CaptureServiceNotification.updateDiag(diag, "task")
            assertEquals(diag.getString(R.string.diag_overlay_task_open), title())
            assertEquals(diag.getString(R.string.diag_bg_notification_title_task), diag.getString(R.string.diag_overlay_task_open))
            assertEquals(diag.getString(R.string.diag_bg_notification_title_listen), diag.getString(R.string.diag_overlay_listening))
        } finally {
            CaptureServiceNotification.stopDiag(diag)
        }
    }
}
