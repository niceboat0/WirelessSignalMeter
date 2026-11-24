package com.craxiom.networksurvey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.services.NetworkSurveyService;
import com.craxiom.networksurvey.services.SurveyControlCommand;
import com.craxiom.networksurvey.util.PreferenceUtils;

import timber.log.Timber;

/**
 * Optional external control gateway. The recording service itself is not exported.
 */
public class SurveyControlReceiver extends BroadcastReceiver
{
    private static final String[] LOGGING_FLAGS = {
            NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING,
            NetworkSurveyConstants.EXTRA_PHONE_STATE_FILE_LOGGING,
            NetworkSurveyConstants.EXTRA_WIFI_FILE_LOGGING,
            NetworkSurveyConstants.EXTRA_BLUETOOTH_FILE_LOGGING,
            NetworkSurveyConstants.EXTRA_GNSS_FILE_LOGGING,
            NetworkSurveyConstants.EXTRA_CDR_FILE_LOGGING
    };

    @Override
    public void onReceive(Context context, Intent intent)
    {
        if (intent == null) return;

        final SurveyControlCommand command = SurveyControlCommand.fromExternal(intent.getAction(),
                PreferenceUtils.getAllowIntentControlPreference(context));
        if (command == SurveyControlCommand.IGNORE) return;

        try
        {
            // Build a new explicit intent; copying the caller's extras could grant boot-only behavior.
            final Intent serviceIntent = new Intent(context, NetworkSurveyService.class)
                    .setAction(command.getAction());
            if (command == SurveyControlCommand.START)
            {
                for (String flag : LOGGING_FLAGS)
                {
                    serviceIntent.putExtra(flag, intent.getBooleanExtra(flag, false));
                }
            }
            context.startForegroundService(serviceIntent);
        } catch (RuntimeException e)
        {
            Timber.w(e, "Could not dispatch the external survey control command");
        }
    }
}
