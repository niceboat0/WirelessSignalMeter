package com.craxiom.networksurvey.ui.rootdiag

import android.content.Context

internal object RootDiagPrefs {
    const val NAME = "root_diag_prefs"
    const val OVERLAY = "overlay_wanted"
    const val OVERLAY_SHOW_RF = "overlay_show_rf"
    const val OVERLAY_SHOW_IMS = "overlay_show_ims"
    const val OVERLAY_SHOW_QUEUE = "overlay_show_queue"
    const val SHOW_PRIVATE_FIELDS = "show_private_fields"

    fun store(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun overlayWanted(context: Context) = store(context).getBoolean(OVERLAY, false)
    fun setOverlayWanted(context: Context, value: Boolean) {
        store(context).edit().putBoolean(OVERLAY, value).apply()
    }

    fun showRf(context: Context) = store(context).getBoolean(OVERLAY_SHOW_RF, true)
    fun showIms(context: Context) = store(context).getBoolean(OVERLAY_SHOW_IMS, true)
    fun showQueue(context: Context) = store(context).getBoolean(OVERLAY_SHOW_QUEUE, false)

    fun showPrivateFields(context: Context) = store(context).getBoolean(SHOW_PRIVATE_FIELDS, false)
    fun setShowPrivateFields(context: Context, value: Boolean) {
        store(context).edit().putBoolean(SHOW_PRIVATE_FIELDS, value).apply()
    }
}
