/*
 * Copyright (C) 2012-2018 Paul Watts (paulcwatts@gmail.com), Sean J. Barbeau (sjbarbeau@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.craxiom.networksurvey.util;

import static com.craxiom.networksurvey.constants.NetworkSurveyConstants.DEFAULT_LOCATION_PROVIDER;
import static java.util.Collections.emptySet;

import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import com.craxiom.networksurvey.Application;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.gpstest.model.GnssType;
import com.craxiom.networksurvey.model.LogTypeState;


import java.util.LinkedHashSet;
import java.util.Set;

import timber.log.Timber;

/**
 * A class containing utility methods related to preferences.
 * <p>
 * Originally from the GPS Test open source Android app.  https://github.com/barbeau/gpstest
 */
public class PreferenceUtils
{
    public static final int CAPABILITY_UNKNOWN = -1;
    public static final int CAPABILITY_NOT_SUPPORTED = 0;
    public static final int CAPABILITY_SUPPORTED = 1;
    public static final int CAPABILITY_LOCATION_DISABLED = 2;

    /**
     * Maximum scan rate in seconds to prevent integer overflow when converting to milliseconds.
     * Set to 12 hours (43,200 seconds) which is generous for survey intervals.
     */
    private static final int MAX_SCAN_RATE_SECONDS = 43200;

    /**
     * Maximum log rollover size in MB to prevent excessively large files.
     * Set to 200MB which is generous for log files.
     */
    private static final int MAX_ROLLOVER_SIZE_MB = 200;

    private static final String INTENT_CONTROL_CONSENT_VERSION = "intent_control_consent_version";
    private static final int CURRENT_INTENT_CONTROL_CONSENT_VERSION = 1;

    /**
     * Applies minimum and maximum scan rate validation.
     * - Maximum: 12 hours to prevent integer overflow when converting to milliseconds
     * - Minimum: Bluetooth has a 23-second minimum due to scan duration requirements
     *
     * @param scanRateSeconds       The scan rate in seconds to validate
     * @param scanRatePreferenceKey The preference key to determine scan type
     * @return The validated scan rate in seconds (clamped to valid range)
     */
    private static int applyMinimumScanRate(int scanRateSeconds, String scanRatePreferenceKey)
    {
        // Apply maximum to prevent overflow (scanRate * 1000 must fit in int)
        if (scanRateSeconds > MAX_SCAN_RATE_SECONDS)
        {
            Timber.w("Scan rate %d seconds exceeds maximum %d seconds, clamping to max",
                    scanRateSeconds, MAX_SCAN_RATE_SECONDS);
            scanRateSeconds = MAX_SCAN_RATE_SECONDS;
        }

        // Apply minimum for Bluetooth scan rate
        if (NetworkSurveyConstants.PROPERTY_BLUETOOTH_SCAN_INTERVAL_SECONDS.equals(scanRatePreferenceKey))
        {
            return Math.max(scanRateSeconds, NetworkSurveyConstants.MINIMUM_BLUETOOTH_SCAN_INTERVAL_SECONDS);
        }

        return scanRateSeconds;
    }

    /**
     * Gets the scan rate preference associated with the provide preference key.
     * <p>
     * First, this method tries to pull the MDM provided scan rate. If it is not set (either because the device is not
     * under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled from
     * the Android Shared Preferences (aka from the user settings). If it is not set there then the provided default
     * value is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     * <p>
     * For Bluetooth scanning, a minimum interval is enforced to ensure proper operation.
     *
     * @param scanRatePreferenceKey  The preference key to use when pulling the scan rate from MDM and Shared Preferences.
     * @param defaultScanRateSeconds The default scan rate to fall back on if the scan rate could not be found.
     * @param context                The context to use when getting the Shared Preferences and Restriction Manager.
     * @return The scan rate to use.
     * @since 0.3.0
     */
    public static int getScanRatePreferenceMs(String scanRatePreferenceKey, int defaultScanRateSeconds, Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        int scanRateSeconds = 0;

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(scanRatePreferenceKey))
            {
                scanRateSeconds = mdmProperties.getInt(scanRatePreferenceKey);
                if (scanRateSeconds > 0)
                {
                    return applyMinimumScanRate(scanRateSeconds, scanRatePreferenceKey) * 1_000;
                } else
                {
                    Timber.w("MDM set invalid scan rate %d seconds for %s, falling back to user preference",
                            scanRateSeconds, scanRatePreferenceKey);
                }
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences.
        final String scanInterval = preferences.getString(scanRatePreferenceKey,
                String.valueOf(defaultScanRateSeconds));
        try
        {
            scanRateSeconds = Integer.parseInt(scanInterval);
            scanRateSeconds = scanRateSeconds > 0 ? scanRateSeconds : defaultScanRateSeconds;

            return applyMinimumScanRate(scanRateSeconds, scanRatePreferenceKey) * 1_000;
        } catch (Exception e)
        {
            Timber.e(e, "Could not convert the scan interval user preference (%s) to an int", scanInterval);
            scanRateSeconds = defaultScanRateSeconds;

            return applyMinimumScanRate(scanRateSeconds, scanRatePreferenceKey) * 1_000;
        }
    }

    /**
     * Gets the auto start preference associated with the provide preference key.
     * <p>
     * First, this method tries to pull the MDM provided auto start value. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the provided default
     * value is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param autoStartPreferenceKey The preference key to use when pulling the value from MDM and Shared Preferences.
     * @param defaultAutoStart       The default auto start value to fall back on if it could not be found.
     * @param context                The context to use when getting the Shared Preferences and Restriction Manager.
     * @return The auto start preference to use.
     * @since 0.4.0
     */
    public static boolean getAutoStartPreference(String autoStartPreferenceKey, boolean defaultAutoStart, Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(autoStartPreferenceKey))
            {
                return mdmProperties.getBoolean(autoStartPreferenceKey);
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences, with a default fallback
        return preferences.getBoolean(autoStartPreferenceKey, defaultAutoStart);
    }

    /**
     * Gets the maximum file size preference. Once this file size is reached, the log file should be closed and a new
     * one started.
     * <p>
     * First, this method tries to pull the MDM provided rollover size. If it is not set (either because the device is
     * not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the default value
     * is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return The maximum file size to use.
     * @since 0.4.0
     */
    public static int getRolloverSizePreference(Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(NetworkSurveyConstants.PROPERTY_LOG_ROLLOVER_SIZE_MB))
            {
                final int logRolloverSizeMb = mdmProperties.getInt(NetworkSurveyConstants.PROPERTY_LOG_ROLLOVER_SIZE_MB);
                // 0 means unlimited, values > 0 must be validated
                if (logRolloverSizeMb == 0)
                {
                    return 0; // Unlimited
                } else if (logRolloverSizeMb > 0)
                {
                    if (logRolloverSizeMb > MAX_ROLLOVER_SIZE_MB)
                    {
                        Timber.w("MDM rollover size %d MB exceeds maximum %d MB, clamping to max",
                                logRolloverSizeMb, MAX_ROLLOVER_SIZE_MB);
                        return MAX_ROLLOVER_SIZE_MB;
                    }
                    return logRolloverSizeMb;
                } else
                {
                    Timber.w("MDM rollover size %d MB is negative, using default", logRolloverSizeMb);
                }
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences.
        final String rolloverPreferenceString = preferences.getString(NetworkSurveyConstants.PROPERTY_LOG_ROLLOVER_SIZE_MB, NetworkSurveyConstants.DEFAULT_ROLLOVER_SIZE_MB);
        try
        {
            final int logRolloverSizeMb = Integer.parseInt(rolloverPreferenceString);
            // 0 means unlimited, values > 0 must be validated
            if (logRolloverSizeMb == 0)
            {
                return 0; // Unlimited
            } else if (logRolloverSizeMb > 0)
            {
                if (logRolloverSizeMb > MAX_ROLLOVER_SIZE_MB)
                {
                    Timber.w("User rollover size %d MB exceeds maximum %d MB, clamping to max",
                            logRolloverSizeMb, MAX_ROLLOVER_SIZE_MB);
                    return MAX_ROLLOVER_SIZE_MB;
                }
                return logRolloverSizeMb;
            } else
            {
                Timber.w("User rollover size %d MB is negative, using default", logRolloverSizeMb);
            }
        } catch (Exception e)
        {
            Timber.e(e, "Could not convert the log rollover size user preference (%s) to an int", rolloverPreferenceString);
        }

        return Integer.parseInt(NetworkSurveyConstants.DEFAULT_ROLLOVER_SIZE_MB);
    }

    /**
     * Gets the log file type preference.
     * <p>
     * First, this method tries to pull the MDM provided log file type. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the default
     * value is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return A wrapper object that contains flags indicating which file types are enabled.
     */
    public static LogTypeState getLogTypePreference(Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(NetworkSurveyConstants.PROPERTY_LOG_FILE_TYPE))
            {
                return convertIndexToLogTypeState(String.valueOf(mdmProperties.getInt(NetworkSurveyConstants.PROPERTY_LOG_FILE_TYPE)));
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences, with a default fallback
        return convertIndexToLogTypeState(preferences.getString(NetworkSurveyConstants.PROPERTY_LOG_FILE_TYPE, "0"));
    }

    /**
     * Gets the location provider preference
     * <p>
     * First, this method tries to pull the MDM provided location provider. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the default
     * value is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return An int representing the location provider. See {@link values/arrays.xml:location_provider_option_labels}
     * for a mapping of the values.
     */
    public static int getLocationProviderPreference(Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(NetworkSurveyConstants.PROPERTY_LOCATION_PROVIDER))
            {
                return mdmProperties.getInt(NetworkSurveyConstants.PROPERTY_LOCATION_PROVIDER, DEFAULT_LOCATION_PROVIDER);
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences, with a default fallback
        String locationProviderString = preferences.getString(NetworkSurveyConstants.PROPERTY_LOCATION_PROVIDER, String.valueOf(DEFAULT_LOCATION_PROVIDER));
        try
        {
            return Integer.parseInt(locationProviderString);
        } catch (NumberFormatException e)
        {
            Timber.e(e, "Could not parse the location provider string: %s", locationProviderString);
            return DEFAULT_LOCATION_PROVIDER;
        }
    }

    /**
     * Requires fresh local consent once, because older releases persisted an enabled default.
     * MDM restrictions are not modified. Call before initializing the settings screen.
     */
    public static synchronized boolean migrateIntentControlPreference(Context context)
    {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getInt(INTENT_CONTROL_CONSENT_VERSION, 0) >= CURRENT_INTENT_CONTROL_CONSENT_VERSION)
        {
            return true;
        }

        final boolean saved = preferences.edit()
                .putBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, false)
                .putInt(INTENT_CONTROL_CONSENT_VERSION, CURRENT_INTENT_CONTROL_CONSENT_VERSION)
                .commit();
        if (!saved) Timber.w("Could not persist the external intent control consent migration");
        return saved;
    }

    /**
     * Gets the preference for allowing intent control.
     * <p>
     * First, this method tries to pull the MDM provided allow intent control value. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the default
     * value is used.
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return True if the app should allow intent control, false otherwise.
     */
    public static boolean getAllowIntentControlPreference(Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL))
            {
                return mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL);
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Historical local defaults are not evidence of consent. Explicit MDM values above still apply.
        if (preferences.getInt(INTENT_CONTROL_CONSENT_VERSION, 0) < CURRENT_INTENT_CONTROL_CONSENT_VERSION)
        {
            return false;
        }
        return preferences.getBoolean(NetworkSurveyConstants.PROPERTY_ALLOW_INTENT_CONTROL, false);
    }

    /**
     * Gets the preference for ignoring the WiFi scan throttling warning.
     * <p>
     * First, this method tries to pull the MDM provided auto start value. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the provided default
     * value is used.
     * <p>
     * Note: The MDM Override does not apply to this preference because it should be up to the MDM admin to decide
     * if developer options have been disabled. If they have, then the user toggling the MDM override switch does not
     * change anything.
     *
     * @param defaultValue The default value to fall back on if it could not be found.
     * @param context      The context to use when getting the Shared Preferences and Restriction Manager.
     * @return True if the Wi-Fi scan throttling warning should not be displayed, false if it should be displayed if
     * throttling is enabled.
     */
    public static boolean getIgnoreWifiThrottlingWarningPreference(boolean defaultValue, Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        String propertyKey = NetworkSurveyConstants.PROPERTY_IGNORE_WIFI_SCAN_THROTTLING_WARNING;

        // First try to use the MDM provided value.
        if (restrictionsManager != null)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(propertyKey))
            {
                return mdmProperties.getBoolean(propertyKey);
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences, with a default fallback
        return preferences.getBoolean(propertyKey, defaultValue);
    }

    /**
     * Converts the user preference index to a wrapper object that contains the flags indicating
     * which file types are enabled.
     */
    private static LogTypeState convertIndexToLogTypeState(String index)
    {
        if (index == null || index.isEmpty())
        {
            Timber.w("Log type index is null or empty, defaulting to CSV only");
            return new LogTypeState(true, false);
        }

        boolean csv = false;
        boolean geoPackage = false;
        switch (index)
        {
            case "0" -> // CSV Only
                    csv = true;
            case "1" -> // GeoPackage Only
                    geoPackage = true;
            case "2" ->
            { // Both
                csv = true;
                geoPackage = true;
            }
            default ->
            {
                Timber.w("Unhandled log type setting=%s, defaulting to CSV only", index);
                csv = true;
            }
        }

        return new LogTypeState(csv, geoPackage);
    }

    public static void saveString(SharedPreferences prefs, String key, String value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(key, value);

        edit.apply();
    }

    public static void saveString(String key, String value)
    {
        saveString(Application.getPrefs(), key, value);
    }

    public static void saveInt(SharedPreferences prefs, String key, int value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(key, value);

        edit.apply();
    }

    public static void saveInt(String key, int value)
    {
        saveInt(Application.getPrefs(), key, value);
    }

    public static void saveLong(SharedPreferences prefs, String key, long value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putLong(key, value);

        edit.apply();
    }

    public static void saveLong(String key, long value)
    {
        saveLong(Application.getPrefs(), key, value);
    }

    public static void saveBoolean(SharedPreferences prefs, String key, boolean value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean(key, value);

        edit.apply();
    }

    public static void saveBoolean(String key, boolean value)
    {
        saveBoolean(Application.getPrefs(), key, value);
    }

    public static void saveFloat(SharedPreferences prefs, String key, float value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putFloat(key, value);

        edit.apply();
    }

    public static void saveFloat(String key, float value)
    {
        saveFloat(Application.getPrefs(), key, value);
    }

    public static void saveDouble(SharedPreferences prefs, String key, double value)
    {
        SharedPreferences.Editor edit = prefs.edit();
        edit.putLong(key, Double.doubleToRawLongBits(value));

        edit.apply();
    }

    public static void saveDouble(String key, double value)
    {
        saveDouble(Application.getPrefs(), key, value);
    }

    /**
     * Gets a double for the provided key from preferences, or the default value if the preference
     * doesn't currently have a value
     *
     * @param key          key for the preference
     * @param defaultValue the default value to return if the key doesn't have a value
     * @return a double from preferences, or the default value if it doesn't exist
     */
    public static Double getDouble(String key, double defaultValue)
    {
        if (!Application.getPrefs().contains(key))
        {
            return defaultValue;
        }
        return Double.longBitsToDouble(Application.getPrefs().getLong(key, 0));
    }

    /**
     * Gets a boolean for the provided key from preferences, or the default value if the preference
     * doesn't currently have a value
     *
     * @param key          key for the preference
     * @param defaultValue the default value to return if the key doesn't have a value
     * @return a boolean from preferences, or the default value if it doesn't exist
     */
    public static boolean getBoolean(String key, boolean defaultValue)
    {
        return Application.getPrefs().getBoolean(key, defaultValue);
    }

    public static String getString(String key, SharedPreferences prefs)
    {
        return prefs.getString(key, null);
    }

    public static long getLong(SharedPreferences prefs, String key, long defaultValue)
    {
        return prefs.getLong(key, defaultValue);
    }

    public static float getFloat(SharedPreferences prefs, String key, float defaultValue)
    {
        return prefs.getFloat(key, defaultValue);
    }

    /**
     * Returns the currently selected satellite sort order as the index in R.array.sort_sats
     *
     * @return the currently selected satellite sort order as the index in R.array.sort_sats
     */
    public static int getSatSortOrderFromPreferences(Context context, SharedPreferences prefs)
    {
        Resources r = context.getResources();
        String[] sortOptions = r.getStringArray(R.array.sort_sats);
        String sortPref = prefs.getString(r.getString(
                R.string.pref_key_default_sat_sort), sortOptions[0]);
        for (int i = 0; i < sortOptions.length; i++)
        {
            if (sortPref.equalsIgnoreCase(sortOptions[i]))
            {
                return i;
            }
        }
        return 0;  // Default to the first option
    }

    /**
     * Gets a set of GnssTypes that should have their satellites displayed that has been saved to preferences. (All are shown if empty or null)
     *
     * @return a set of GnssTypes that should have their satellites displayed that has been saved to preferences. (All are shown if empty or null)
     */
    public static Set<GnssType> gnssFilter(Context context, SharedPreferences prefs)
    {
        Set<GnssType> filter = new LinkedHashSet<>();
        Resources r = context.getResources();
        String filterString = getString(r.getString(R.string.pref_key_default_sat_filter), prefs);
        if (filterString == null)
        {
            return filter;
        }
        String[] parsedFilter = filterString.split(",");
        for (String s : parsedFilter)
        {
            GnssType gnssType = GnssType.fromString(s);
            if (gnssType != null)
            {
                filter.add(gnssType);
            }
        }
        return filter;
    }

    /**
     * Saves a set of GnssTypes that should have their satellites displayed to preferences. (All are shown if empty or null)
     * Values are persisted as string of comma-separated values, with each of the enum values .toString() called
     *
     * @param filter a set of GnssTypes that should have their satellites displayed. (All are shown if empty or null)
     */
    public static void saveGnssFilter(Context context, Set<GnssType> filter, SharedPreferences prefs)
    {
        Resources r = context.getResources();
        StringBuilder filterString = new StringBuilder();
        for (GnssType gnssType : filter)
        {
            filterString.append(gnssType.toString()).append(",");
        }
        // Remove the last comma (if there was at least one entry)
        if (!filter.isEmpty())
        {
            filterString.deleteCharAt(filterString.length() - 1);
        }
        saveString(prefs, r.getString(R.string.pref_key_default_sat_filter), filterString.toString());
    }

    /**
     * Clears any active GNSS filter so all satellites are displayed
     */
    public static void clearGnssFilter(Context context, SharedPreferences prefs)
    {
        saveGnssFilter(context, emptySet(), prefs);
    }

    /**
     * Removes the specified preference by deleting it
     */
    public static void remove(String key)
    {
        SharedPreferences.Editor edit = Application.getPrefs().edit();
        edit.remove(key).apply();
    }

    public static boolean hasDeniedBackgroundLocationPermission(Context context)
    {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_KEY_DENIED_BACKGROUND_LOCATION_PERMISSION, false);
    }

    public static void denyBackgroundLocationPermission(Context context)
    {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(NetworkSurveyConstants.PROPERTY_KEY_DENIED_BACKGROUND_LOCATION_PERMISSION, true).apply();
    }

    public static boolean displayServingCellCoverageOnMap(Context context)
    {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MAP_DISPLAY_SERVING_CELL_COVERAGE, true);
    }

    /**
     * Gets the battery management enabled preference.
     * <p>
     * First, this method tries to pull the MDM provided battery management enabled value. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then false is returned
     * (feature disabled by default).
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return True if battery management should be enabled, false otherwise.
     */
    public static boolean isBatteryManagementEnabled(Context context)
    {
        return getBatteryThresholdPercent(context) > 0;
    }

    /**
     * Gets the battery threshold percentage preference.
     * <p>
     * First, this method tries to pull the MDM provided battery threshold value. If it is not set (either because the device
     * is not under MDM control, or if that specific value is not set by the MDM administrator) then the value is pulled
     * from the Android Shared Preferences (aka from the user settings). If it is not set there then the default value
     * of 0 is returned (which disables battery management).
     * <p>
     * The only exception to this sequence is that if the user has toggled the MDM override switch in user settings,
     * then the user preference value will be used instead of the MDM value.
     *
     * @param context The context to use when getting the Shared Preferences and Restriction Manager.
     * @return The battery threshold percentage (0-95). Operations pause when battery drops to or below this value. 0 disables battery management.
     */
    public static int getBatteryThresholdPercent(Context context)
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);

        final boolean mdmOverride = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);

        // First try to use the MDM provided value.
        if (restrictionsManager != null && !mdmOverride)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            if (mdmProperties.containsKey(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT))
            {
                final int threshold = mdmProperties.getInt(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT);
                // Clamp the threshold to valid range (0-95%)
                return Math.max(0, Math.min(threshold, 95));
            }
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Next, try to use the value from user preferences.
        try
        {
            // Try to get as int first (new format)
            final int threshold = preferences.getInt(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT,
                    NetworkSurveyConstants.DEFAULT_BATTERY_THRESHOLD_PERCENT);
            // Clamp the threshold to valid range (0-95%)
            return Math.max(0, Math.min(threshold, 95));
        } catch (ClassCastException e)
        {
            // Fall back to string format (old format) for migration
            try
            {
                final String thresholdString = preferences.getString(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT,
                        String.valueOf(NetworkSurveyConstants.DEFAULT_BATTERY_THRESHOLD_PERCENT));
                final int threshold = Integer.parseInt(thresholdString);

                // Clamp before migration to ensure we store a valid value
                final int clampedThreshold = Math.max(0, Math.min(threshold, 95));

                // Migrate to int for future use
                preferences.edit().remove(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT)
                        .putInt(NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT, clampedThreshold).apply();

                return clampedThreshold;
            } catch (Exception ex)
            {
                Timber.e(ex, "Could not convert the battery threshold preference to an int");
                return NetworkSurveyConstants.DEFAULT_BATTERY_THRESHOLD_PERCENT;
            }
        }
    }

    // ============================================================================
    // Display Settings Helper Methods
    // ============================================================================

    /**
     * Gets the measurement units preference (metric or imperial).
     *
     * @param context The context to use when getting the Shared Preferences.
     * @return The measurement units preference value ("metric" or "imperial").
     */
    public static String getMeasurementUnits(Context context)
    {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(NetworkSurveyConstants.PROPERTY_MEASUREMENT_UNITS,
                        NetworkSurveyConstants.DEFAULT_MEASUREMENT_UNITS);
    }

    /**
     * Checks if imperial units should be used for display.
     *
     * @param context The context to use when getting the Shared Preferences.
     * @return True if imperial units should be used, false for metric.
     */
    public static boolean useImperialUnits(Context context)
    {
        return NetworkSurveyConstants.MEASUREMENT_UNITS_IMPERIAL
                .equals(getMeasurementUnits(context));
    }
}
