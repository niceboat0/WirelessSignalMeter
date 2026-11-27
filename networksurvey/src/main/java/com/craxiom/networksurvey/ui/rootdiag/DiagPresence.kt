package com.craxiom.networksurvey.ui.rootdiag

import com.craxiom.networksurvey.R

/** Short status shared by the overlay and the foreground notification. */
internal fun diagPresenceHeadline(presence: String): Int = when (presence) {
    "recording" -> R.string.diag_overlay_recording
    "task" -> R.string.diag_overlay_task_open
    "listening" -> R.string.diag_overlay_listening
    else -> R.string.diag_overlay_standby
}

/** Longer line for the notification. It describes the same presence as [diagPresenceHeadline]. */
internal fun diagPresenceDetail(presence: String): Int = when (presence) {
    "recording" -> R.string.diag_bg_notification_text_recording
    "task" -> R.string.diag_bg_notification_text_task
    "listening" -> R.string.diag_bg_notification_text_listen
    else -> R.string.diag_bg_notification_text_standby
}
