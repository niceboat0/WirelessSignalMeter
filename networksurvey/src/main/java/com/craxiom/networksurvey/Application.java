/*
 * Copyright (C) 2013 Sean J. Barbeau
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

package com.craxiom.networksurvey;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.preference.PreferenceManager;

import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.lang.LocaleManager;
import com.craxiom.networksurvey.services.watchlist.WatchlistNotificationHelper;
import com.craxiom.networksurvey.util.AppLogger;
import com.craxiom.networksurvey.util.PreferenceUtils;
import com.craxiom.networksurvey.util.ThemePref;

import android.os.Build;

import dagger.hilt.android.HiltAndroidApp;
import timber.log.Timber;

/**
 * Holds application-wide state.
 * <p>
 * This class was pulled from the GPS Test app
 *
 * @author Sean J. Barbeau
 */
@HiltAndroidApp
public class Application extends android.app.Application
{
    private static Application mApp;

    private SharedPreferences mPrefs;

    public static Application get()
    {
        return mApp;
    }

    public static SharedPreferences getPrefs()
    {
        return get().mPrefs;
    }

    private static LocaleManager mLocaleManager;

    public static LocaleManager getLocaleManager()
    {
        return mLocaleManager;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        // If this is a debug apk, then we enable logging. If it is a release apk we don't want to output any logs.
        if (BuildConfig.DEBUG) Timber.plant(new Timber.DebugTree());

        mApp = this;
        // Capture failures in preference migration and other startup work as well.
        AppLogger.init(this);
        AppLogger.i("App", "启动 v" + BuildConfig.VERSION_NAME + " device=" + Build.MANUFACTURER + " " + Build.MODEL + " API=" + Build.VERSION.SDK_INT);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        PreferenceUtils.migrateIntentControlPreference(this);

        // Material You（Monet）动态取色：在 Activity 创建前统一应用（XML 层：preference/dialog/fragment）
        // - Android 12（API 31）+：从系统壁纸提取主色，覆盖到当前 Activity 主题；
        // - 更低版本：DynamicColors 内部自动跳过，沿用 styles.xml 的静态配色；
        // - 用户在设置中关闭开关时不应用，回退静态主题。
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState)
            {
                try
                {
                    if (ThemePref.INSTANCE.isMonet(activity))
                    {
                        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity);
                    }
                }
                catch (Throwable t)
                {
                    Timber.w(t, "Monet 动态取色应用失败");
                }
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
            @Override public void onActivityStarted(@NonNull Activity activity) {}
            @Override public void onActivityResumed(@NonNull Activity activity) {}
            @Override public void onActivityPaused(@NonNull Activity activity) {}
            @Override public void onActivityStopped(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });

        // Map SDK initialization is owned by BaiduMapView, before constructing a map.
        new Thread(() -> {
            try
            {
                com.craxiom.networksurvey.util.TencentTileProxy.INSTANCE.start();
            }
            catch (Throwable t)
            {
                Timber.w(t, "TencentTileProxy 启动失败");
            }
        }, "TileProxyInit").start();
    }

    @Override
    public void onTerminate()
    {
        super.onTerminate();
        mApp = null;
    }

    @Override
    protected void attachBaseContext(Context base)
    {
        mLocaleManager = new LocaleManager(base);
        super.attachBaseContext(mLocaleManager.setLocale(base));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        mLocaleManager.setLocale(this);
    }

    /**
     * Creates the notification Channel that is used by this Android app.
     *
     * @param context The context that is used to create the notification channel.
     * @since 1.5.0
     */
    public static void createNotificationChannel(Context context)
    {
        final NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null)
        {
            final NotificationChannel channel = new NotificationChannel(NetworkSurveyConstants.NOTIFICATION_CHANNEL_ID,
                    context.getText(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);

            // Register the Watchlist alerts channel up front so it appears in system notification
            // settings before the first alert fires.
            WatchlistNotificationHelper.INSTANCE.createNotificationChannel(context);
        } else
        {
            Timber.wtf("The Notification Manager could not be retrieved to add the Network Survey notification channel");
        }
    }
}
