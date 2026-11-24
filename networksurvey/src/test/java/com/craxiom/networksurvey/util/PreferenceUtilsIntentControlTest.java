package com.craxiom.networksurvey.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.PreferenceManager;

import com.craxiom.networksurvey.constants.NetworkSurveyConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PreferenceUtilsIntentControlTest
{
    private Context context;
    private SharedPreferences preferences;
    private RestrictionsManager restrictionsManager;

    @Before
    public void setUp()
    {
        context = RuntimeEnvironment.getApplication();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        shadowOf(restrictionsManager).setApplicationRestrictions(new Bundle());
    }

    @Test
    public void defaultIsDisabledBeforeAndAfterMigration()
    {
        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
        assertTrue(PreferenceUtils.migrateIntentControlPreference(context));
        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
    }

    @Test
    public void historicalEnabledDefaultIsRejectedAndReset()
    {
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();

        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
        assertTrue(PreferenceUtils.migrateIntentControlPreference(context));
        assertFalse(preferences.getBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true));
        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
    }

    @Test
    public void freshUserConsentSurvivesRepeatedMigration()
    {
        PreferenceUtils.migrateIntentControlPreference(context);
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();

        assertTrue(PreferenceUtils.migrateIntentControlPreference(context));
        assertTrue(PreferenceUtils.getAllowIntentControlPreference(context));
    }

    @Test
    public void explicitMdmConsentSurvivesLocalMigration()
    {
        Bundle restrictions = new Bundle();
        restrictions.putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true);
        shadowOf(restrictionsManager).setApplicationRestrictions(restrictions);

        assertTrue(PreferenceUtils.getAllowIntentControlPreference(context));
        assertTrue(PreferenceUtils.migrateIntentControlPreference(context));
        assertTrue(PreferenceUtils.getAllowIntentControlPreference(context));
    }

    @Test
    public void explicitMdmDenialOverridesLocalConsent()
    {
        PreferenceUtils.migrateIntentControlPreference(context);
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();
        Bundle restrictions = new Bundle();
        restrictions.putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, false);
        shadowOf(restrictionsManager).setApplicationRestrictions(restrictions);

        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
    }

    @Test
    public void mdmOverrideStillRequiresFreshLocalConsent()
    {
        Bundle restrictions = new Bundle();
        restrictions.putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true);
        shadowOf(restrictionsManager).setApplicationRestrictions(restrictions);
        preferences.edit()
                .putBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, true)
                .putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true)
                .commit();

        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));
        PreferenceUtils.migrateIntentControlPreference(context);
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();
        assertTrue(PreferenceUtils.getAllowIntentControlPreference(context));
    }
}
