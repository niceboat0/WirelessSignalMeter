package com.craxiom.networksurvey.db

import android.content.Context

internal const val SURVEY_RECORDING_FORMAT_PREFS = "survey_recording_format"

/** The air-interface format chosen for one task. Missing means do not start a file automatically. */
internal fun rememberSurveyRecordingFormat(context: Context, taskId: String, formatName: String): Boolean {
    val id = taskId.trim()
    val name = formatName.trim()
    if (id.isEmpty() || name.isEmpty()) return false
    return context.applicationContext
        .getSharedPreferences(SURVEY_RECORDING_FORMAT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(id, name)
        .commit()
}

internal fun recallSurveyRecordingFormat(context: Context, taskId: String): String? =
    context.applicationContext
        .getSharedPreferences(SURVEY_RECORDING_FORMAT_PREFS, Context.MODE_PRIVATE)
        .getString(taskId, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
