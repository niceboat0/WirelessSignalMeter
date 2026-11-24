package com.craxiom.networksurvey.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SurveyControlCommandTest
{
    @Test
    public void disabledControlRejectsBothCommands()
    {
        assertEquals(SurveyControlCommand.IGNORE,
                SurveyControlCommand.fromExternal(NetworkSurveyService.ACTION_START_SURVEY, false));
        assertEquals(SurveyControlCommand.IGNORE,
                SurveyControlCommand.fromExternal(NetworkSurveyService.ACTION_STOP_SURVEY, false));
    }

    @Test
    public void explicitConsentAllowsStartAndStop()
    {
        assertEquals(SurveyControlCommand.START,
                SurveyControlCommand.fromExternal(NetworkSurveyService.ACTION_START_SURVEY, true));
        assertEquals(SurveyControlCommand.STOP,
                SurveyControlCommand.fromExternal(NetworkSurveyService.ACTION_STOP_SURVEY, true));
    }

    @Test
    public void enabledControlStillRejectsBootAndUnknownActions()
    {
        assertEquals(SurveyControlCommand.IGNORE,
                SurveyControlCommand.fromExternal("android.intent.action.BOOT_COMPLETED", true));
        assertEquals(SurveyControlCommand.IGNORE,
                SurveyControlCommand.fromExternal("other.action", true));
        assertEquals(SurveyControlCommand.IGNORE, SurveyControlCommand.fromExternal(null, true));
    }
}
