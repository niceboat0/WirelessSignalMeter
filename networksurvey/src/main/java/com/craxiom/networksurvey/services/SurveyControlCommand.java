package com.craxiom.networksurvey.services;

/**
 * The commands exposed by the optional external broadcast API.
 */
public enum SurveyControlCommand
{
    START(NetworkSurveyService.ACTION_START_SURVEY),
    STOP(NetworkSurveyService.ACTION_STOP_SURVEY),
    IGNORE(null);

    private final String action;

    SurveyControlCommand(String action)
    {
        this.action = action;
    }

    public String getAction()
    {
        return action;
    }

    public static SurveyControlCommand fromExternal(String action, boolean enabled)
    {
        if (!enabled) return IGNORE;
        if (START.action.equals(action)) return START;
        if (STOP.action.equals(action)) return STOP;
        return IGNORE;
    }
}
