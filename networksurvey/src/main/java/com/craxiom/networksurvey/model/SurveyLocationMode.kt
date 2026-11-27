package com.craxiom.networksurvey.model

/** Chosen when a task is created. Parsing uses the location sidecar header, not the task row. */
internal enum class SurveyLocationMode {
    DT,
    CQT,
    ;

    val label: String
        get() = when (this) {
            DT -> "DT 路测"
            CQT -> "CQT 定点"
        }

    companion object {
        fun fromPreference(value: String?): SurveyLocationMode? =
            entries.firstOrNull { it.name == value }
    }
}
