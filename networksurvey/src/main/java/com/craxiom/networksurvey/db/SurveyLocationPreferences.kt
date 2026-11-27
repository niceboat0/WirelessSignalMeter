package com.craxiom.networksurvey.db

import android.content.Context
import com.craxiom.networksurvey.model.SurveyLocationMode

internal const val SURVEY_LOCATION_MODE_PREFS = "survey_location_mode"

/** Process restarts keep the DT/CQT choice by task id. The survey_task table stays unchanged. */
internal fun rememberSurveyLocationMode(context: Context, taskId: String, mode: SurveyLocationMode): Boolean =
    context.applicationContext
        .getSharedPreferences(SURVEY_LOCATION_MODE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(taskId, mode.name)
        .commit()

internal fun recallSurveyLocationMode(context: Context, taskId: String): SurveyLocationMode? =
    SurveyLocationMode.fromPreference(
        context.applicationContext
            .getSharedPreferences(SURVEY_LOCATION_MODE_PREFS, Context.MODE_PRIVATE)
            .getString(taskId, null),
    )
