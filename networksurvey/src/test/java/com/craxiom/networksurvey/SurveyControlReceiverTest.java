package com.craxiom.networksurvey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.services.NetworkSurveyService;
import com.craxiom.networksurvey.util.PreferenceUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SurveyControlReceiverTest
{
    private RecordingContext context;
    private SharedPreferences preferences;
    private SurveyControlReceiver receiver;

    @Before
    public void setUp()
    {
        context = new RecordingContext(RuntimeEnvironment.getApplication());
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        RestrictionsManager restrictionsManager =
                (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        shadowOf(restrictionsManager).setApplicationRestrictions(new Bundle());
        receiver = new SurveyControlReceiver();
    }

    @Test
    public void defaultAndHistoricalConsentDoNotStartService()
    {
        Intent request = new Intent(NetworkSurveyService.ACTION_START_SURVEY)
                .putExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, true);
        receiver.onReceive(context, request);
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();
        receiver.onReceive(context, request);

        assertTrue(context.starts.isEmpty());
    }

    @Test
    public void forgedBootExtraDoesNotBypassDisabledControl()
    {
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_AUTO_START_CELLULAR_LOGGING, true).commit();
        receiver.onReceive(context, new Intent(NetworkSurveyService.ACTION_START_SURVEY)
                .putExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, true)
                .putExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, true));

        assertTrue(context.starts.isEmpty());
    }

    @Test
    public void enabledStartCopiesOnlySupportedBooleanFlags()
    {
        enableControl();
        receiver.onReceive(context, new Intent(NetworkSurveyService.ACTION_START_SURVEY)
                .putExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, true)
                .putExtra(NetworkSurveyConstants.EXTRA_GNSS_FILE_LOGGING, true)
                .putExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, true)
                .putExtra("untrusted_extra", "ignored"));

        assertEquals(1, context.starts.size());
        Intent forwarded = context.starts.get(0);
        assertEquals(new ComponentName(context, NetworkSurveyService.class), forwarded.getComponent());
        assertEquals(NetworkSurveyService.ACTION_START_SURVEY, forwarded.getAction());
        assertTrue(forwarded.getBooleanExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, false));
        assertTrue(forwarded.getBooleanExtra(NetworkSurveyConstants.EXTRA_GNSS_FILE_LOGGING, false));
        assertFalse(forwarded.getBooleanExtra(NetworkSurveyConstants.EXTRA_WIFI_FILE_LOGGING, true));
        assertNotNull(forwarded.getExtras());
        assertEquals(new HashSet<>(Arrays.asList(
                NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING,
                NetworkSurveyConstants.EXTRA_PHONE_STATE_FILE_LOGGING,
                NetworkSurveyConstants.EXTRA_WIFI_FILE_LOGGING,
                NetworkSurveyConstants.EXTRA_BLUETOOTH_FILE_LOGGING,
                NetworkSurveyConstants.EXTRA_GNSS_FILE_LOGGING,
                NetworkSurveyConstants.EXTRA_CDR_FILE_LOGGING)), forwarded.getExtras().keySet());
    }

    @Test
    public void enabledStopForwardsNoExtras()
    {
        enableControl();
        receiver.onReceive(context, new Intent(NetworkSurveyService.ACTION_STOP_SURVEY)
                .putExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, true)
                .putExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, true));

        assertEquals(1, context.starts.size());
        assertEquals(NetworkSurveyService.ACTION_STOP_SURVEY, context.starts.get(0).getAction());
        assertNull(context.starts.get(0).getExtras());
    }

    @Test
    public void enabledControlRejectsBootAndUnknownActions()
    {
        enableControl();
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED)
                .putExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, true));
        receiver.onReceive(context, new Intent("unknown"));
        receiver.onReceive(context, null);

        assertTrue(context.starts.isEmpty());
    }

    @Test
    public void coreServiceIsPrivateAndExternalGatewayIsExported() throws Exception
    {
        PackageManager packageManager = context.getPackageManager();
        assertFalse(packageManager.getServiceInfo(
                new ComponentName(context, NetworkSurveyService.class), 0).exported);
        assertTrue(packageManager.getReceiverInfo(
                new ComponentName(context, SurveyControlReceiver.class), 0).exported);
    }

    @Test
    public void genuineBootReceiverStillStartsPrivateServiceWithoutExternalConsent()
    {
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_AUTO_START_CELLULAR_LOGGING, true).commit();
        assertFalse(PreferenceUtils.getAllowIntentControlPreference(context));

        new StartAtBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertEquals(1, context.starts.size());
        Intent started = context.starts.get(0);
        assertEquals(new ComponentName(context, NetworkSurveyService.class), started.getComponent());
        assertTrue(started.getBooleanExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, false));
    }

    @Test
    public void bootReceiverRejectsOtherActions()
    {
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_AUTO_START_CELLULAR_LOGGING, true).commit();
        new StartAtBootReceiver().onReceive(context, new Intent(NetworkSurveyService.ACTION_START_SURVEY)
                .putExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, true));

        assertTrue(context.starts.isEmpty());
    }

    @Test
    public void simReceiverKeepsSystemActionHandlingWithoutExternalConsent()
    {
        AtomicInteger notifications = new AtomicInteger();
        BroadcastReceiver observer = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context ignored, Intent intent)
            {
                notifications.incrementAndGet();
            }
        };
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(context);
        manager.registerReceiver(observer, new IntentFilter(SimChangeReceiver.SIM_CHANGED_INTENT));
        try
        {
            SimChangeReceiver simReceiver = new SimChangeReceiver();
            simReceiver.onReceive(context, new Intent(NetworkSurveyService.ACTION_START_SURVEY));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, notifications.get());

            simReceiver.onReceive(context, new Intent("android.intent.action.SIM_STATE_CHANGED")
                    .putExtra("ss", "LOADED"));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, notifications.get());
            assertTrue(context.starts.isEmpty());
        } finally
        {
            manager.unregisterReceiver(observer);
        }
    }

    private void enableControl()
    {
        PreferenceUtils.migrateIntentControlPreference(context);
        preferences.edit().putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, true).commit();
    }

    private static final class RecordingContext extends ContextWrapper
    {
        private final List<Intent> starts = new ArrayList<>();

        RecordingContext(Context base)
        {
            super(base);
        }

        @Override
        public Context getApplicationContext()
        {
            return this;
        }

        @Override
        public ComponentName startForegroundService(Intent service)
        {
            starts.add(new Intent(service));
            return service.getComponent();
        }

        @Override
        public int checkPermission(String permission, int pid, int uid)
        {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                    || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission))
            {
                return PackageManager.PERMISSION_GRANTED;
            }
            return super.checkPermission(permission, pid, uid);
        }
    }
}
