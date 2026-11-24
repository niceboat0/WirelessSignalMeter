package com.craxiom.networksurvey.services;

import static com.craxiom.networksurvey.constants.NetworkSurveyConstants.LOCATION_PROVIDER_ALL;
import static com.craxiom.networksurvey.constants.NetworkSurveyConstants.LOCATION_PROVIDER_FUSED;
import static com.craxiom.networksurvey.constants.NetworkSurveyConstants.LOCATION_PROVIDER_GNSS;
import static com.craxiom.networksurvey.constants.NetworkSurveyConstants.LOCATION_PROVIDER_NETWORK;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.craxiom.messaging.DeviceStatus;
import com.craxiom.messaging.DeviceStatusData;
import com.craxiom.networksurvey.Application;
import com.craxiom.networksurvey.BuildConfig;
import com.craxiom.networksurvey.GpsListener;
import com.craxiom.networksurvey.NetworkSurveyActivity;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.DeviceStatusMessageConstants;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.data.SsidExclusionManager;
import com.craxiom.networksurvey.gpstest.util.FormatUtils;
import com.craxiom.networksurvey.gpstest.util.MathUtils;
import com.craxiom.networksurvey.listeners.ExtraLocationListener;
import com.craxiom.networksurvey.listeners.IBluetoothSurveyRecordListener;
import com.craxiom.networksurvey.listeners.ICdrEventListener;
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener;
import com.craxiom.networksurvey.listeners.IDeviceStatusListener;
import com.craxiom.networksurvey.listeners.IGnssFailureListener;
import com.craxiom.networksurvey.listeners.IGnssSurveyRecordListener;
import com.craxiom.networksurvey.listeners.ILoggingChangeListener;
import com.craxiom.networksurvey.listeners.IMissionIdListener;
import com.craxiom.networksurvey.listeners.IPhoneStateListener;
import com.craxiom.networksurvey.listeners.IWifiSurveyRecordListener;
import com.craxiom.networksurvey.logging.DeviceStatusCsvLogger;
import com.craxiom.networksurvey.model.BatteryPauseState;
import com.craxiom.networksurvey.model.LogTypeState;
import com.craxiom.networksurvey.services.controller.BluetoothController;
import com.craxiom.networksurvey.services.controller.CellularController;
import com.craxiom.networksurvey.services.controller.GnssController;
import com.craxiom.networksurvey.services.controller.WifiController;
import com.craxiom.networksurvey.services.watchlist.WatchlistDetectionManager;
import com.craxiom.networksurvey.util.LocationUtils;
import com.craxiom.networksurvey.util.NsUtils;
import com.craxiom.networksurvey.util.PreferenceUtils;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Int32Value;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

/**
 * This service is the main service for Network Survey. It is responsible for initializing all of
 * the controllers and any other resources that is needed to conduct a survey. When the user
 * performs any actions such as toggling on logging or creating an MQTT connection this is the
 * service that handles dispatching those requests to the appropriate controller.
 * <p>
 * This service also handles performing any actions that need to be done when the application is
 * started at boot. For example, if the user has enabled auto start logging, then this service will
 * start the logging when the application is started at boot.
 *
 * @since 0.0.9
 */
public class NetworkSurveyService extends Service implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        BatteryMonitor.IBatteryLevelListener
{
    public static final String ACTION_START_SURVEY = "com.craxiom.networksurvey.START_SURVEY";
    public static final String ACTION_STOP_SURVEY = "com.craxiom.networksurvey.STOP_SURVEY";

    private static final String PROPERTY_BATTERY_PAUSE_STATE = "battery_pause_state";

    private final AtomicBoolean deviceStatusActive = new AtomicBoolean(false);
    private final ScheduledThreadPoolExecutor deviceStatusExecutor = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> deviceStatusFuture;

    private SurveyServiceBinder surveyServiceBinder;
    private final Handler uiThreadHandler;
    private final ExecutorService executorService;

    private volatile int deviceStatusScanRateMs;

    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean wakeLockActive = new AtomicBoolean(false);

    private CellularController cellularController;
    private WifiController wifiController;
    private WatchlistDetectionManager watchlistDetectionManager;
    private boolean watchlistRegistered = false;
    private BluetoothController bluetoothController;
    private GnssController gnssController;
    private String deviceId;
    private SurveyRecordProcessor surveyRecordProcessor;
    private GpsListener primaryLocationListener;
    private ExtraLocationListener gnssLocationListener;
    private ExtraLocationListener networkLocationListener;

    private DeviceStatusCsvLogger deviceStatusCsvLogger;
    private Looper serviceLooper;
    private Handler serviceHandler;
    private BroadcastReceiver managedConfigurationListener;
    private boolean mdmOverride = false;

    private BatteryMonitor batteryMonitor;
    private BatteryPauseState batteryPauseState;

    private final Set<ILoggingChangeListener> loggingChangeListeners = new CopyOnWriteArraySet<>();

    private int locationProviderPreference = NetworkSurveyConstants.DEFAULT_LOCATION_PROVIDER;

    // Survey session tracking
    private Long surveySessionStartTime = null;
    private final AtomicInteger surveySessionRecordCount = new AtomicInteger(0);

    // Mission ID tracking. The Mission ID identifies one survey session and rolls when the first
    // mission relevant survey of a session starts (OpenCelliD/BeaconDB uploads do not count).
    // missionId is the rolled value shown to the user; it is null until the first real mission and
    // is retained as the most recent value after a session ends. Before the first roll,
    // getMissionIdForRecords() returns an empty string; the only records stamped in that window are
    // OpenCelliD/BeaconDB upload scans, which discard the Mission ID.
    private volatile String missionId = null;
    private boolean missionSessionActive = false;
    private final Set<IMissionIdListener> missionIdListeners = new CopyOnWriteArraySet<>();

    public NetworkSurveyService()
    {
        surveyServiceBinder = new SurveyServiceBinder(this);
        uiThreadHandler = new Handler(Looper.getMainLooper());

        // Making it single thread to ensure records are processed in the order they are received.
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onCreate()
    {
        Timber.i("Creating the Network Survey Service");

        final Context context = getApplicationContext();

        // Initialize wake lock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null)
        {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    NetworkSurveyConstants.WAKE_LOCK_TAG);
            wakeLock.setReferenceCounted(false);
            Timber.d("Wake lock initialized");
        } else
        {
            Timber.e("PowerManager is null, wake lock not initialized");
        }

        final HandlerThread handlerThread = new HandlerThread("NetworkSurveyService");
        handlerThread.start();

        serviceLooper = handlerThread.getLooper();
        serviceHandler = new Handler(serviceLooper);

        deviceId = createDeviceId();
        deviceStatusCsvLogger = new DeviceStatusCsvLogger(this);

        primaryLocationListener = new GpsListener();
        gnssLocationListener = new ExtraLocationListener(LocationManager.GPS_PROVIDER);
        networkLocationListener = new ExtraLocationListener(LocationManager.NETWORK_PROVIDER);

        surveyRecordProcessor = new SurveyRecordProcessor(primaryLocationListener, deviceId, context, executorService);
        surveyRecordProcessor.setNetworkSurveyService(this);

        cellularController = new CellularController(this, executorService, serviceLooper, serviceHandler, surveyRecordProcessor);
        wifiController = new WifiController(this, executorService, serviceLooper, serviceHandler, surveyRecordProcessor, uiThreadHandler);
        bluetoothController = new BluetoothController(this, executorService, serviceLooper, serviceHandler, surveyRecordProcessor, uiThreadHandler);
        gnssController = new GnssController(this, executorService, serviceLooper, serviceHandler, surveyRecordProcessor);

        setScanRateValues();
        readMdmOverridePreference();
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this);

        // Register for MDM updates
        registerManagedConfigurationListener();

        // 蜂窝控制器立即初始化（首屏信号页需要，最高优先级）
        cellularController.initialize();

        // WiFi/蓝牙/GNSS 控制器延迟初始化：放到后台线程，避免阻塞 Service onCreate
        // 这些控制器只在对应 Tab 页首次打开时才需要
        executorService.execute(() -> {
            try { wifiController.initializeWifiScanningResources(); } catch (Throwable t) { Timber.e(t, "WiFi init failed"); }
            try { bluetoothController.initializeBtScanningResources(); } catch (Throwable t) { Timber.e(t, "BT init failed"); }
            try { gnssController.initializeGnssScanningResources(); } catch (Throwable t) { Timber.e(t, "GNSS init failed"); }
        });

        // Register the Watchlist detector if the feature is enabled (created lazily).
        updateWatchlistRegistration();

        // Initialize battery management
        batteryMonitor = new BatteryMonitor(this);
        batteryPauseState = loadBatteryPauseState();

        // Start battery monitoring
        batteryMonitor.startMonitoring();

        // Register battery monitor if battery management is enabled
        if (PreferenceUtils.isBatteryManagementEnabled(context))
        {
            batteryMonitor.register(this);

            // Force a threshold evaluation in case the initial battery reading hasn't completed yet
            batteryMonitor.reevaluateThreshold();

            // Check if we should start paused
            if (batteryMonitor.isPausedDueToBattery())
            {
                Timber.i("Starting in paused state due to low battery");
                pauseAllOperations();
            }
        }

        updateServiceNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (intent == null) return START_NOT_STICKY;

        // This component is private. Only the app's boot receiver/worker can request boot logging;
        // the exported control receiver forwards an allowlist of recording flags, never this extra.
        final boolean startedAtBoot = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, false);
        if (startedAtBoot)
        {
            Timber.i("Received the startedAtBoot flag in the NetworkSurveyService. Reading the auto start preferences");

            final Context applicationContext = getApplicationContext();

            final boolean autoStartCellularLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_CELLULAR_LOGGING, false, applicationContext);
            if (autoStartCellularLogging && !cellularController.isLoggingEnabled())
            {
                toggleCellularLogging(true);
            }

            final boolean autoStartWifiLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_WIFI_LOGGING, false, applicationContext);
            if (autoStartWifiLogging && !wifiController.isLoggingEnabled())
            {
                toggleWifiLogging(true);
            }

            final boolean autoStartBluetoothLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_BLUETOOTH_LOGGING, false, applicationContext);
            if (autoStartBluetoothLogging && !bluetoothController.isLoggingEnabled())
            {
                toggleBluetoothLogging(true);
            }

            final boolean autoStartGnssLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_GNSS_LOGGING, false, applicationContext);
            if (autoStartGnssLogging && !gnssController.isLoggingEnabled())
            {
                toggleGnssLogging(true);
            }

            final boolean autoStartPhoneStateLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_PHONE_STATE_LOGGING, false, applicationContext);
            if (autoStartPhoneStateLogging && !isPhoneStateLoggingEnabled())
            {
                togglePhoneStateLogging(true);
            }

            final boolean autoStartCdrLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_CDR_LOGGING, false, applicationContext);
            if (autoStartCdrLogging && !isCdrLoggingEnabled())
            {
                toggleCdrLogging(true);
            }
        } else if (ACTION_START_SURVEY.equals(intent.getAction()))
        {
            boolean allowIntentControl = PreferenceUtils.getAllowIntentControlPreference(this);
            if (!allowIntentControl)
            {
                Timber.w("Received a start survey control intent, but the user has disabled intent control");
                return START_REDELIVER_INTENT;
            }

            Timber.i("The Network Survey Service was started via an external intent");

            final boolean startCellular = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_CELLULAR_FILE_LOGGING, false);
            final boolean startPhoneState = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_PHONE_STATE_FILE_LOGGING, false);
            final boolean startWifi = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_WIFI_FILE_LOGGING, false);
            final boolean startBluetooth = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_BLUETOOTH_FILE_LOGGING, false);
            final boolean startGnss = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_GNSS_FILE_LOGGING, false);
            final boolean startCdr = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_CDR_FILE_LOGGING, false);

            Timber.i("Starting the Network Survey Service with the file logging flags: cellular=%b, phone_state=%b, wifi=%b, bluetooth=%b, gnss=%b, cdr=%b",
                    startCellular, startPhoneState, startWifi, startBluetooth, startGnss, startCdr);

            if (startCellular && !cellularController.isLoggingEnabled())
            {
                toggleCellularLogging(true);
            }
            if (startPhoneState && !isPhoneStateLoggingEnabled())
            {
                togglePhoneStateLogging(true);
            }
            if (startWifi && !wifiController.isLoggingEnabled())
            {
                toggleWifiLogging(true);
            }
            if (startBluetooth && !bluetoothController.isLoggingEnabled())
            {
                toggleBluetoothLogging(true);
            }
            if (startGnss && !gnssController.isLoggingEnabled())
            {
                toggleGnssLogging(true);
            }
            if (startCdr && !isCdrLoggingEnabled()) toggleCdrLogging(true);
        } else if (ACTION_STOP_SURVEY.equals(intent.getAction()))
        {
            boolean allowIntentControl = PreferenceUtils.getAllowIntentControlPreference(this);
            if (!allowIntentControl)
            {
                Timber.w("Received a stop survey control intent, but the user has disabled intent control");
                return START_REDELIVER_INTENT;
            }

            Timber.i("The Network Survey Service is being stopped via an external intent");

            stopAllLogging();

            stopSelf();
        }

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return surveyServiceBinder;
    }

    @Override
    public void onDestroy()
    {
        // Unregister and stop battery monitor
        if (batteryMonitor != null)
        {
            batteryMonitor.unregister(this);
            batteryMonitor.stopMonitoring();
        }

        // Release wake lock if held
        if (wakeLock != null && wakeLockActive.getAndSet(false))
        {
            Timber.d("Releasing wake lock in onDestroy");
            try
            {
                if (wakeLock.isHeld())
                {
                    wakeLock.release();
                }
            } catch (Exception e)
            {
                Timber.e(e, "Failed to release wake lock in onDestroy");
            }
        }

        unregisterManagedConfigurationListener();

        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).unregisterOnSharedPreferenceChangeListener(this);

        if (watchlistDetectionManager != null)
        {
            watchlistDetectionManager.stop();
            watchlistDetectionManager = null;
            watchlistRegistered = false;
        }

        cellularController.stopCellularRecordScanning();
        wifiController.stopWifiRecordScanning();
        bluetoothController.stopBluetoothRecordScanning();
        gnssController.stopGnssRecordScanning();
        cellularController.stopCdrEvents();
        removeLocationListener();
        stopDeviceStatusReport(true);
        stopAllLogging();

        serviceLooper.quitSafely();
        shutdownNotifications();
        executorService.shutdown();
        deviceStatusExecutor.shutdown();

        cellularController.onDestroy();
        wifiController.onDestroy();
        bluetoothController.onDestroy();
        gnssController.onDestroy();

        surveyServiceBinder.onDestroy();
        surveyServiceBinder = null;

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key)
    {
        if (key == null) return;

        switch (key)
        {
            case NetworkSurveyConstants.PROPERTY_LOG_ROLLOVER_SIZE_MB:
                cellularController.onRolloverPreferenceChanged();
                wifiController.onRolloverPreferenceChanged();
                bluetoothController.onRolloverPreferenceChanged();
                gnssController.onRolloverPreferenceChanged();

                deviceStatusCsvLogger.onSharedPreferenceChanged();
                break;
            case NetworkSurveyConstants.PROPERTY_CELLULAR_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_WIFI_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_BLUETOOTH_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_GNSS_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_DEVICE_STATUS_SCAN_INTERVAL_SECONDS:
                setScanRateValues();
                break;
            case NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY:
                readMdmOverridePreference();
                break;
            case NetworkSurveyConstants.PROPERTY_LOG_FILE_TYPE:
                cellularController.onLogFileTypePreferenceChanged();
                wifiController.onLogFileTypePreferenceChanged();
                bluetoothController.onLogFileTypePreferenceChanged();
                gnssController.onLogFileTypePreferenceChanged();
                stopDeviceStatusReportIfNotNeeded();
                break;
            case NetworkSurveyConstants.PROPERTY_LOCATION_PROVIDER:
                updateLocationListener();
                break;
            case NetworkSurveyConstants.PROPERTY_WATCHLIST_ENABLED:
                updateWatchlistRegistration();
                break;
            case NetworkSurveyConstants.PROPERTY_WATCHLIST_ABSENCE_WINDOW_SECONDS:
                if (watchlistDetectionManager != null) watchlistDetectionManager.refreshConfig();
                break;

            case NetworkSurveyConstants.PROPERTY_BATTERY_THRESHOLD_PERCENT:
                handleBatteryPreferenceChange();
                break;

            default:
                break;
        }
    }

    public GpsListener getPrimaryLocationListener()
    {
        return primaryLocationListener;
    }

    /**
     * @return The SsidExclusionManager instance for managing excluded WiFi SSIDs.
     */
    public SsidExclusionManager getSsidExclusionManager()
    {
        if (surveyRecordProcessor != null)
        {
            return surveyRecordProcessor.getSsidExclusionManager();
        }
        return null;
    }

    /**
     * Registers a new listener for changes to the location information.
     */
    public void registerLocationListener(LocationListener locationListener)
    {
        primaryLocationListener.registerListener(locationListener);
    }

    /**
     * Unregisters a listener for changes to the location information.
     *
     * @since 1.6.0
     */
    public void unregisterLocationListener(LocationListener locationListener)
    {
        primaryLocationListener.unregisterListener(locationListener);
    }

    public void registerLoggingChangeListener(ILoggingChangeListener listener)
    {
        loggingChangeListeners.add(listener);
    }

    public void unregisterLoggingChangeListener(ILoggingChangeListener listener)
    {
        loggingChangeListeners.remove(listener);
    }

    /**
     * Registers a listener for notifications when new cellular survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     */
    public void registerCellularSurveyRecordListener(ICellularSurveyRecordListener
                                                             surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerCellularSurveyRecordListener(surveyRecordListener);
        }

        cellularController.startCellularRecordScanning(); // Only starts scanning if it is not already active.
        startDeviceStatusReportIfLoggingEnabled(); // Only starts the device status report if it is not already active.
    }

    /**
     * Unregisters a cellular survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     *
     * @param surveyRecordListener The listener to unregister.
     */
    public void unregisterCellularSurveyRecordListener(ICellularSurveyRecordListener
                                                               surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterCellularSurveyRecordListener(surveyRecordListener);
            if (!surveyRecordProcessor.isCellularBeingUsed())
            {
                cellularController.stopCellularRecordScanning();
            }
        }

        stopDeviceStatusReportIfNotNeeded();

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new Wi-Fi survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 0.1.2
     */
    public void registerWifiSurveyRecordListener(IWifiSurveyRecordListener surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerWifiSurveyRecordListener(surveyRecordListener);
        }

        wifiController.startWifiRecordScanning(); // Only starts scanning if it is not already active.
        startDeviceStatusReportIfLoggingEnabled(); // Only starts the device status report if it is not already active.
    }

    /**
     * Unregisters a Wi-Fi survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for Wi-Fi
     * scanning, then just the Wi-Fi scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 0.1.2
     */
    public void unregisterWifiSurveyRecordListener(IWifiSurveyRecordListener
                                                           surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterWifiSurveyRecordListener(surveyRecordListener);
            if (!surveyRecordProcessor.isWifiBeingUsed())
            {
                wifiController.stopWifiRecordScanning();
            }
        }

        stopDeviceStatusReportIfNotNeeded();

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers or unregisters the Watchlist detector based on the watchlist-enabled preference.
     * <p>
     * The detector is registered as a PASSIVE Wi-Fi listener, so it observes scan results only while
     * a survey is already scanning Wi-Fi and never starts scanning or keeps the service alive on its
     * own. This is the "detection requires a survey running" model. The manager is recreated on each
     * enable so its coroutine scope is fresh after a previous disable.
     */
    public void updateWatchlistRegistration()
    {
        if (surveyRecordProcessor == null) return;

        final boolean enabled = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getBoolean(NetworkSurveyConstants.PROPERTY_WATCHLIST_ENABLED, NetworkSurveyConstants.DEFAULT_WATCHLIST_ENABLED);

        if (enabled && !watchlistRegistered)
        {
            if (watchlistDetectionManager == null)
            {
                watchlistDetectionManager = new WatchlistDetectionManager(getApplicationContext(), deviceId,
                        this::getMissionIdForRecords);
            }
            surveyRecordProcessor.registerPassiveWifiSurveyRecordListener(watchlistDetectionManager);
            watchlistDetectionManager.start();
            watchlistRegistered = true;
            Timber.i("Watchlist detection enabled");
        } else if (!enabled && watchlistRegistered)
        {
            surveyRecordProcessor.unregisterPassiveWifiSurveyRecordListener(watchlistDetectionManager);
            if (watchlistDetectionManager != null)
            {
                watchlistDetectionManager.stop();
                watchlistDetectionManager = null;
            }
            watchlistRegistered = false;
            Timber.i("Watchlist detection disabled");
        }
    }

    /**
     * @return true if the Watchlist feature is enabled in preferences.
     */
    public boolean isWatchlistEnabled()
    {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getBoolean(NetworkSurveyConstants.PROPERTY_WATCHLIST_ENABLED, NetworkSurveyConstants.DEFAULT_WATCHLIST_ENABLED);
    }

    /**
     * @return true if the Watchlist is enabled AND Wi-Fi scanning is currently active, meaning
     * alerts can actually fire. When false, the management screen shows an "alerts paused" status.
     */
    public boolean isWatchlistAlertsActive()
    {
        return isWatchlistEnabled() && wifiController != null && wifiController.isScanningActive();
    }

    /**
     * Registers a listener for notifications when new Bluetooth survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 1.0.0
     */
    public void registerBluetoothSurveyRecordListener(IBluetoothSurveyRecordListener
                                                              surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerBluetoothSurveyRecordListener(surveyRecordListener);
        }

        bluetoothController.startBluetoothRecordScanning(); // Only starts scanning if it is not already active.
        startDeviceStatusReportIfLoggingEnabled(); // Only starts the device status report if it is not already active.
    }

    /**
     * Unregisters a Bluetooth survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for Bluetooth
     * scanning, then just the Bluetooth scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 1.0.0
     */
    public void unregisterBluetoothSurveyRecordListener(IBluetoothSurveyRecordListener
                                                                surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterBluetoothSurveyRecordListener(surveyRecordListener);
            if (!surveyRecordProcessor.isBluetoothBeingUsed())
            {
                bluetoothController.stopBluetoothRecordScanning();
            }
        }

        stopDeviceStatusReportIfNotNeeded();

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new GNSS survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 0.3.0
     */
    public void registerGnssSurveyRecordListener(IGnssSurveyRecordListener surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerGnssSurveyRecordListener(surveyRecordListener);
        }

        gnssController.startGnssRecordScanning(); // Only starts scanning if it is not already active.
        startDeviceStatusReportIfLoggingEnabled(); // Only starts the device status report if it is not already active.
    }

    /**
     * Unregisters a GNSS survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for GNSS
     * scanning, then just the GNSS scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 0.3.0
     */
    public void unregisterGnssSurveyRecordListener(IGnssSurveyRecordListener
                                                           surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterGnssSurveyRecordListener(surveyRecordListener);
            if (!surveyRecordProcessor.isGnssBeingUsed())
            {
                gnssController.stopGnssRecordScanning();
            }
        }

        stopDeviceStatusReportIfNotNeeded();

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new CDR events are available.
     *
     * @param listener The listener to register.
     * @since 1.11
     */
    public void registerCdrEventListener(ICdrEventListener listener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerCdrEventListener(listener);
        }

        cellularController.startCdrEvents(); // Only starts if it is not already active

        // Don't call startDeviceStatusReportIfLoggingEnabled() here because CDR events are not part of the device status report.
    }

    /**
     * Unregisters a CDR event listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for CDR
     * events, then just the phone state lister portion of this service is stopped.
     *
     * @param listener The listener to unregister.
     * @since 1.11
     */
    public void unregisterCdrEventListener(ICdrEventListener listener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterCdrEventListener(listener);
            if (!surveyRecordProcessor.isCdrBeingUsed()) cellularController.stopCdrEvents();
        }

        // Don't call stopDeviceStatusReportIfNotNeeded() here because CDR events are not part of the device status report.

        // Check to see if this service is still needed. It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new device status messages are available.
     *
     * @param deviceStatusListener The survey record listener to register.
     * @since 1.1.0
     */
    public void registerDeviceStatusListener(IDeviceStatusListener deviceStatusListener)
    {
        synchronized (deviceStatusActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.registerDeviceStatusListener(deviceStatusListener);
            }

            startDeviceStatusReport(true); // Only starts scanning if it is not already active.
        }
    }

    /**
     * Unregisters a device status message listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     *
     * @param deviceStatusListener The listener to unregister.
     * @since 1.1.0
     */
    public void unregisterDeviceStatusListener(IDeviceStatusListener deviceStatusListener)
    {
        synchronized (deviceStatusActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.unregisterDeviceStatusListener(deviceStatusListener);
                if (!surveyRecordProcessor.isDeviceStatusBeingUsed()) stopDeviceStatusReport(true);
            }
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new phone state messages are available.
     * If this is the first phone state listener, the Android PhoneStateListener is started.
     *
     * @param phoneStateListener The phone state listener to register.
     */
    public void registerPhoneStateListener(IPhoneStateListener phoneStateListener)
    {
        boolean wasEmpty = surveyRecordProcessor != null && !surveyRecordProcessor.isPhoneStateBeingUsed();

        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerPhoneStateListener(phoneStateListener);
        }

        if (wasEmpty)
        {
            cellularController.startPhoneStateListener();
        }
    }

    /**
     * Unregisters a phone state message listener.
     * <p>
     * If no more phone state listeners remain, the Android PhoneStateListener is stopped.
     * If the listener being removed is the last listener and nothing else is using this
     * {@link NetworkSurveyService}, then this service is shutdown.
     *
     * @param phoneStateListener The listener to unregister.
     */
    public void unregisterPhoneStateListener(IPhoneStateListener phoneStateListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterPhoneStateListener(phoneStateListener);
            if (!surveyRecordProcessor.isPhoneStateBeingUsed())
            {
                cellularController.stopPhoneStateListener();
            }
        }

        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Used to check if this service is still needed.
     * <p>
     * This service is still needed if logging is enabled, if the UI is visible, or if a connection is active.  In other
     * words, if there is an active consumer of the survey records.
     *
     * @return True if there is an active consumer of the survey records produced by this service, false otherwise.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isBeingUsed()
    {
        return cellularController.isLoggingEnabled()
                || cellularController.isPhoneStateLoggingEnabled()
                || wifiController.isLoggingEnabled()
                || bluetoothController.isLoggingEnabled()
                || gnssController.isLoggingEnabled()
                || cellularController.isCdrLoggingEnabled()
                || surveyRecordProcessor.isBeingUsed();
    }

    /**
     * Whenever the UI is visible, we need to pass information to it so it can be displayed to the user.
     *
     * @param networkSurveyActivity The activity that is now visible to the user.
     */
    public void onUiVisible(NetworkSurveyActivity networkSurveyActivity)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.onUiVisible(networkSurveyActivity);
        }

        cellularController.startCellularRecordScanning();
    }

    /**
     * The UI is no longer visible, so don't send any updates to the UI.
     */
    public void onUiHidden()
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.onUiHidden();
            if (!surveyRecordProcessor.isCellularBeingUsed())
            {
                cellularController.stopCellularRecordScanning();
            }
        }
    }

    /**
     * Toggles the cellular logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleCellularLogging(boolean enable)
    {
        Boolean result = cellularController.toggleLogging(enable);
        if (result != null)
        {
            if (enable && result)
            {
                onSurveyStarted(true);

                // Auto-include phone state with cellular if the preference is enabled
                if (shouldAutoIncludePhoneState())
                {
                    cellularController.autoStartPhoneStateIfNotRunning();
                }
            } else if (!enable && !result)
            {
                // Auto-stop phone state if it was auto-started by cellular
                if (cellularController.isPhoneStateAutoStartedByCellular() && isPhoneStateLoggingEnabled())
                {
                    cellularController.togglePhoneStateLogging(false);
                    cellularController.setPhoneStateAutoStartedByCellular(false);
                }

                onSurveyStopped();
            }
            updateWakeLock();
        }
        return result;
    }

    /**
     * @return True if the auto-include phone state with cellular preference is enabled.
     */
    private boolean shouldAutoIncludePhoneState()
    {
        return PreferenceUtils.getAutoStartPreference(
                NetworkSurveyConstants.PROPERTY_AUTO_INCLUDE_PHONE_STATE_WITH_CELLULAR,
                true, getApplicationContext());
    }

    /**
     * Toggles the wifi logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleWifiLogging(boolean enable)
    {
        Boolean result = wifiController.toggleLogging(enable);
        if (result != null)
        {
            if (enable && result)
            {
                onSurveyStarted(true);
            } else if (!enable && !result) onSurveyStopped();
            updateWakeLock();
        }
        return result;
    }

    /**
     * Toggles the Bluetooth logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleBluetoothLogging(boolean enable)
    {
        Boolean result = bluetoothController.toggleLogging(enable);
        if (result != null)
        {
            if (enable && result)
            {
                onSurveyStarted(true);
            } else if (!enable && !result) onSurveyStopped();
            updateWakeLock();
        }
        return result;
    }

    /**
     * Toggles the GNSS logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleGnssLogging(boolean enable)
    {
        Boolean result = gnssController.toggleLogging(enable);
        if (result != null)
        {
            if (enable && result)
            {
                onSurveyStarted(true);
            } else if (!enable && !result) onSurveyStopped();
            updateWakeLock();
        }
        return result;
    }

    /**
     * Toggles the CDR logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging. In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled. Null is returned if the
     * toggling was unsuccessful.
     */

    /**
     * Toggles the phone state logging setting.
     * <p>
     * Clears the auto-started-by-cellular flag on ANY manual toggle (both enable and disable)
     * so the user "takes ownership" of phone state whenever they interact with it directly.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging. True if it is enabled, or false if it is disabled. Null is
     * returned if the toggling was unsuccessful.
     */
    public Boolean togglePhoneStateLogging(boolean enable)
    {
        cellularController.setPhoneStateAutoStartedByCellular(false);
        return toggleImmediateFireSurvey(enable, cellularController::togglePhoneStateLogging);
    }

    public boolean isPhoneStateLoggingEnabled()
    {
        return cellularController.isPhoneStateLoggingEnabled();
    }

    /**
     * @return True if phone state logging was auto-started alongside cellular logging,
     * false if it was started manually or is not running.
     */
    public boolean isPhoneStateAutoStartedByCellular()
    {
        return cellularController.isPhoneStateAutoStartedByCellular();
    }

    public Boolean toggleCdrLogging(boolean enable)
    {
        return toggleImmediateFireSurvey(enable, cellularController::toggleCdrLogging);
    }

    /**
     * A controller logging toggle that returns the new logging state (true enabled, false disabled)
     * or null when the toggle was unsuccessful.
     */
    @FunctionalInterface
    private interface LoggingToggle
    {
        Boolean toggle(boolean enable);
    }

    /**
     * Toggles a survey whose controller registers a listener that can emit a record immediately on
     * registration (phone state and CDR both register for {@code LISTEN_SERVICE_STATE}, which
     * delivers the current state right away). The Mission ID must be rolled before that registration
     * so the immediate record cannot capture a pre-roll value, but the controller toggle is what
     * performs the registration, so the roll is done optimistically first and undone if the toggle
     * reports the start failed.
     *
     * @param enable           True to start the survey, false to stop it.
     * @param controllerToggle The controller toggle to invoke.
     * @return The controller's result: the new logging state, or null if the toggle was unsuccessful.
     */
    private Boolean toggleImmediateFireSurvey(boolean enable, LoggingToggle controllerToggle)
    {
        final String missionIdBeforeStart;
        final boolean missionSessionActiveBeforeStart;
        synchronized (this)
        {
            missionIdBeforeStart = missionId;
            missionSessionActiveBeforeStart = missionSessionActive;
        }

        if (enable) onSurveyStarted(true);

        final Boolean result = controllerToggle.toggle(enable);
        if (result != null)
        {
            if (!enable && !result) onSurveyStopped();
            updateWakeLock();
        }

        // The controller returns null when the start failed. Undo the optimistic roll so a failed
        // start never leaves behind a rolled Mission ID that no record will ever carry.
        if (enable && result == null)
        {
            undoOptimisticStart(missionIdBeforeStart, missionSessionActiveBeforeStart);
        }

        return result;
    }

    public boolean isCellularLoggingEnabled()
    {
        return cellularController.isLoggingEnabled();
    }

    public boolean isWifiLoggingEnabled()
    {
        return wifiController.isLoggingEnabled();
    }

    public boolean isBluetoothLoggingEnabled()
    {
        return bluetoothController.isLoggingEnabled();
    }

    public boolean isGnssLoggingEnabled()
    {
        return gnssController.isLoggingEnabled();
    }

    public boolean isCdrLoggingEnabled()
    {
        return cellularController.isCdrLoggingEnabled();
    }

    public boolean isCellularScanningActive()
    {
        return cellularController.isScanningActive();
    }

    public boolean isWifiScanningActive()
    {
        return wifiController.isScanningActive();
    }

    public int getWifiScanRateMs()
    {
        return wifiController.getScanRateMs();
    }

    /**
     * Triggers the creation of a single device status message and notifies the listeners.
     */
    public void sendSingleDeviceStatus()
    {
        surveyRecordProcessor.onDeviceStatus(generateDeviceStatus());
    }

    /**
     * Returns the number of SIMs in the device, both physical and eSIMs. If 0 is returned, then
     * the device does not have any active SIMs. This does not mean that the device does not have a
     * cellular radio, nor does it mean that survey results won't be available. Because of emergency
     * service requirements the phone should still report limited survey results even if there is no
     * active SIM.
     */
    public int getSimCount()
    {
        return cellularController.getSimCount();
    }

    /**
     * Returns the information for the Active SIMs in the device, both physical and eSIMs. If the
     * returned list is empty, then the device does not have any active SIMs. This does not mean
     * that the device does not have a cellular radio, nor does it mean that survey results won't
     * be available. Because of emergency service requirements the phone should still report
     * limited survey results even if there is no active SIM.
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList()
    {
        return cellularController.getActiveSubscriptionInfoList();
    }

    /**
     * Check if any type of survey is currently active (file logging).
     *
     * @return true if any survey is active
     */
    public boolean isAnySurveyActive()
    {
        return isCellularLoggingEnabled() || isPhoneStateLoggingEnabled() || isWifiLoggingEnabled() ||
                isBluetoothLoggingEnabled() || isGnssLoggingEnabled() || isCdrLoggingEnabled();
    }

    /**
     * Get the start time of the current survey session.
     *
     * @return Start time in milliseconds since epoch, or null if no session is active
     */
    public Long getSurveySessionStartTime()
    {
        return surveySessionStartTime;
    }

    /**
     * Acquires the wake lock if any survey is active. This ensures the device
     * doesn't go to sleep while collecting survey data.
     */
    @SuppressLint("WakelockTimeout")
    private synchronized void updateWakeLock()
    {
        if (wakeLock == null)
        {
            Timber.w("Wake lock is null, cannot update wake lock state");
            return;
        }

        boolean shouldHoldWakeLock = isAnySurveyActive();

        if (shouldHoldWakeLock && !wakeLockActive.getAndSet(true))
        {
            Timber.d("Acquiring wake lock for survey operations");
            try
            {
                wakeLock.acquire();
            } catch (Exception e)
            {
                Timber.e(e, "Failed to acquire wake lock");
                wakeLockActive.set(false);
            }
        } else if (!shouldHoldWakeLock && wakeLockActive.getAndSet(false))
        {
            Timber.d("Releasing wake lock - no surveys active");
            try
            {
                if (wakeLock.isHeld())
                {
                    wakeLock.release();
                }
            } catch (Exception e)
            {
                Timber.e(e, "Failed to release wake lock");
            }
        }
    }

    /**
     * Get the count of records processed during the current survey session.
     *
     * @return Number of records processed
     */
    public int getSurveySessionRecordCount()
    {
        return surveySessionRecordCount.get();
    }

    /**
     * Called when any survey starts. Initializes session tracking if this is the first survey.
     * <p>
     * Callers pass whether the survey they are starting counts toward the Mission ID. This must be
     * driven by caller intent rather than reading back the survey enabled flags, because those flags
     * are set by the same controller call that registers the immediate-fire phone state / CDR service
     * state listener. Rolling here first (before that registration) is what guarantees the immediate
     * record cannot capture a pre-roll Mission ID.
     *
     * @param missionRelevant true if the starting survey counts toward the Mission ID (every survey
     *                        except OpenCelliD/BeaconDB upload scanning). When true and no mission
     *                        session is active yet, a fresh Mission ID is rolled.
     */
    private void onSurveyStarted(boolean missionRelevant)
    {
        final String missionIdSnapshot;
        final boolean missionSessionActiveSnapshot;
        final boolean rolled;
        synchronized (this)
        {
            if (surveySessionStartTime == null)
            {
                Timber.i("Starting new survey session");
                surveySessionStartTime = System.currentTimeMillis();
                surveySessionRecordCount.set(0);
            }

            // Roll the Mission ID only on the transition to the first mission relevant survey of a
            // session. Adding another survey to an already running session keeps the same value,
            // and OpenCelliD/BeaconDB only sessions never roll (they pass missionRelevant = false).
            if (missionRelevant && !missionSessionActive)
            {
                missionSessionActive = true;
                missionId = generateMissionId();
                Timber.i("Rolled to a new Mission ID: %s", missionId);
                rolled = true;
            } else
            {
                rolled = false;
            }
            missionIdSnapshot = missionId;
            missionSessionActiveSnapshot = missionSessionActive;
        }

        // Notify outside the lock so listener callbacks never run while holding the service monitor.
        if (rolled) notifyMissionIdListeners(missionIdSnapshot, missionSessionActiveSnapshot);
    }

    /**
     * Called when any survey stops. Clears session tracking if all surveys have stopped.
     */
    private void onSurveyStopped()
    {
        final String missionIdSnapshot;
        final boolean missionSessionActiveSnapshot;
        final boolean ended;
        synchronized (this)
        {
            if (!isAnySurveyActive())
            {
                Timber.i("All surveys stopped, ending survey session. Total records: %d",
                        surveySessionRecordCount.get());
                surveySessionStartTime = null;
                surveySessionRecordCount.set(0);
            }

            // End the mission session when the last survey stops. The missionId is
            // intentionally retained as the most recent value so the UI can still show and copy it
            // and any trailing records stay non-null. The next survey start rolls a fresh one.
            if (missionSessionActive && !isAnySurveyActive())
            {
                missionSessionActive = false;
                Timber.i("Mission session ended. Most recent Mission ID: %s", missionId);
                ended = true;
            } else
            {
                ended = false;
            }
            missionIdSnapshot = missionId;
            missionSessionActiveSnapshot = missionSessionActive;
        }

        // Notify outside the lock so listener callbacks never run while holding the service monitor.
        if (ended) notifyMissionIdListeners(missionIdSnapshot, missionSessionActiveSnapshot);
    }

    /**
     * Undoes the optimistic session start and Mission ID roll performed by
     * {@link #toggleImmediateFireSurvey(boolean, LoggingToggle)} when the controller reports the
     * start failed. Unlike {@link #onSurveyStopped()} (which retains the Mission ID as the most
     * recent value for a real session end), this restores the Mission ID to its pre-start value so a
     * failed start does not leave behind a rolled Mission ID that no record will ever carry. Session
     * tracking is cleared only when nothing else is running.
     *
     * @param missionIdBeforeStart            The Mission ID value captured before the optimistic roll.
     * @param missionSessionActiveBeforeStart Whether a mission session was already active before the
     *                                        optimistic roll (if so, this attempt did not roll).
     */
    private void undoOptimisticStart(String missionIdBeforeStart, boolean missionSessionActiveBeforeStart)
    {
        final String missionIdSnapshot;
        final boolean missionSessionActiveSnapshot;
        final boolean rolledBack;
        synchronized (this)
        {
            // onSurveyStarted(true) only rolls when no mission session was active yet, so only undo
            // the Mission ID when this attempt is the one that rolled it.
            if (!missionSessionActiveBeforeStart && missionSessionActive)
            {
                missionSessionActive = false;
                missionId = missionIdBeforeStart;
                rolledBack = true;
            } else
            {
                rolledBack = false;
            }

            // Clear session tracking if this attempt started it and nothing else is running.
            if (!isAnySurveyActive())
            {
                surveySessionStartTime = null;
                surveySessionRecordCount.set(0);
            }
            missionIdSnapshot = missionId;
            missionSessionActiveSnapshot = missionSessionActive;
        }

        // Notify outside the lock so the UI settles back to the pre-start Mission ID.
        if (rolledBack) notifyMissionIdListeners(missionIdSnapshot, missionSessionActiveSnapshot);
    }

    /**
     * Generates a new Mission ID of the form "NS &lt;deviceId&gt; &lt;yyyyMMdd-HHmmss&gt;" using the
     * current time. Reuses {@link SurveyRecordProcessor#DATE_TIME_FORMATTER} so the trailing
     * timestamp format stays consistent with the rest of the app.
     *
     * @return A new Mission ID.
     */
    private String generateMissionId()
    {
        return NetworkSurveyConstants.MISSION_ID_PREFIX + deviceId + " "
                + SurveyRecordProcessor.DATE_TIME_FORMATTER.format(LocalDateTime.now());
    }

    /**
     * @return The Mission ID to stamp on survey records: the current rolled Mission ID, or an empty
     * string if no mission relevant survey has started yet. The empty string case is only reachable
     * when nothing but OpenCelliD/BeaconDB scanning is running, where the value is discarded before
     * reaching any consumer that uses it. Every mission relevant survey rolls the Mission ID before
     * registering any record listener, so retained records always carry the rolled value.
     */
    public String getMissionIdForRecords()
    {
        final String currentMissionId = missionId;
        return currentMissionId != null ? currentMissionId : "";
    }

    /**
     * @return The current rolled Mission ID for display, or null if no mission relevant survey has
     * started during this app session. After a survey stops, this retains the most recent value.
     */
    public String getRolledMissionId()
    {
        return missionId;
    }

    /**
     * @return true while at least one mission relevant survey is running.
     */
    public synchronized boolean isMissionSessionActive()
    {
        return missionSessionActive;
    }

    /**
     * Registers a listener that is notified when the Mission ID rolls or the mission session ends.
     *
     * @param listener The listener to register.
     */
    public void registerMissionIdListener(IMissionIdListener listener)
    {
        missionIdListeners.add(listener);
    }

    /**
     * Unregisters a previously registered Mission ID listener.
     *
     * @param listener The listener to unregister.
     */
    public void unregisterMissionIdListener(IMissionIdListener listener)
    {
        missionIdListeners.remove(listener);
    }

    private void notifyMissionIdListeners(String currentMissionId, boolean sessionActive)
    {
        com.craxiom.networksurvey.db.SurveyTaskStore.onMissionState(this, currentMissionId, sessionActive);
        for (IMissionIdListener listener : missionIdListeners)
        {
            listener.onMissionIdChanged(currentMissionId, sessionActive);
        }
    }

    /**
     * Increment the survey session record count. This should be called by the SurveyRecordProcessor
     * for each record processed.
     */
    public void incrementSurveySessionRecordCount()
    {
        surveySessionRecordCount.incrementAndGet();
    }

    /**
     * @return The Android ID associated with this device and app.
     */
    @SuppressLint("HardwareIds")
    private String createDeviceId()
    {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * Triggers a read of the scan rate values and stores them in instance variables.
     * <p>
     * The approach for reading the scan rates is to first use the MDM provided values. If those are not
     * set then the user preference values are employed. Finally, the default values are used as a fallback.
     *
     * @since 0.3.0
     */
    private void setScanRateValues()
    {
        final Context applicationContext = getApplicationContext();

        cellularController.refreshScanRate();
        wifiController.refreshScanRate();
        bluetoothController.refreshScanRate();
        gnssController.refreshScanRate();

        final int oldDeviceStatusScanRateMs = deviceStatusScanRateMs;
        deviceStatusScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_DEVICE_STATUS_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_DEVICE_STATUS_SCAN_INTERVAL_SECONDS, applicationContext);

        // If device status reporting is active and the rate has changed, restart it with the new rate
        if (deviceStatusActive.get() && oldDeviceStatusScanRateMs != deviceStatusScanRateMs)
        {
            Timber.i("Device status scan rate changed from %d ms to %d ms, restarting to apply new rate",
                    oldDeviceStatusScanRateMs, deviceStatusScanRateMs);

            restartDeviceStatusReport();
        }

        surveyRecordProcessor.setGnssScanRateMs(gnssController.getScanRateMs());

        updateLocationListener();
    }

    /**
     * Triggers a read of the mdm override preference and stores it in an instance variable.
     *
     * @since 1.10.0
     */
    private void readMdmOverridePreference()
    {
        final Context applicationContext = getApplicationContext();
        mdmOverride = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getBoolean(NetworkSurveyConstants.PROPERTY_MDM_OVERRIDE_KEY, false);
    }

    /**
     * Creates a new {@link GpsListener} if necessary, and Registers with the Android {@link LocationManager} for
     * location updates.
     * <p>
     * It is necessary to call this method after any of the scanning services are updated. This is needed because the
     * location update rate depends on which scanners are active, and which of those has the shortest scan interval. So
     * if any of that changes we need to update the rate at which we request location updates.
     * <p>
     * If none of the scanning is active, then this method does nothing an returns immediately.
     */
    public void updateLocationListener()
    {
        if (!isBeingUsed()) return;

        // Don't register location listeners if paused for battery
        if (isPausedForBattery())
        {
            Timber.d("Skipping location listener registration - paused for battery");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            Timber.w("ACCESS_FINE_LOCATION Permission not granted for the NetworkSurveyService");
            return;
        }

        final LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager != null)
        {
            int smallestScanRate = Integer.MAX_VALUE;

            // Find the smallest scan rate for all the scanning types that are active as a starting point
            if (cellularController.isScanningActive() && cellularController.getScanRateMs() < smallestScanRate)
            {
                smallestScanRate = cellularController.getScanRateMs();
            }

            if (wifiController.isScanningActive() && wifiController.getScanRateMs() < smallestScanRate)
            {
                smallestScanRate = wifiController.getScanRateMs();
            }

            if (bluetoothController.isScanningActive() && bluetoothController.getScanRateMs() < smallestScanRate)
            {
                smallestScanRate = bluetoothController.getScanRateMs();
            }

            if (gnssController.isScanningActive() && gnssController.getScanRateMs() < smallestScanRate)
            {
                smallestScanRate = gnssController.getScanRateMs();
            }

            if (deviceStatusActive.get() && deviceStatusScanRateMs < smallestScanRate)
            {
                smallestScanRate = deviceStatusScanRateMs;
            }

            if (smallestScanRate == Integer.MAX_VALUE)
            {
                // This scenario indicates that only CDR logging is active, and since records are so infrequent we use
                // another approach to get the location.
                Timber.d("Not adding the location update request because only CDR logging is enabled.");
                return;
            }

            // Use the smallest scan rate set by the user for the active scanning types
            if (smallestScanRate > 20_000) smallestScanRate = smallestScanRate / 2;

            long scanRateCeiling = GpsListener.LOCATION_AGE_THRESHOLD_MS - 35_000;
            if (smallestScanRate > scanRateCeiling) smallestScanRate = (int) scanRateCeiling;

            if (smallestScanRate < 5_000) smallestScanRate = 5_000;

            Timber.d("Setting the location update rate to %d", smallestScanRate);

            try
            {
                final String provider;

                locationProviderPreference = PreferenceUtils.getLocationProviderPreference(getApplicationContext());

                String locationProviderMapping = getLocationProviderFromPreference(locationProviderPreference);
                if (locationManager.isProviderEnabled(locationProviderMapping))
                {
                    provider = locationProviderMapping;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                {
                    provider = LocationManager.FUSED_PROVIDER;
                } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                {
                    provider = LocationManager.GPS_PROVIDER;
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                {
                    provider = LocationManager.NETWORK_PROVIDER;
                } else
                {
                    provider = LocationManager.PASSIVE_PROVIDER;
                }
                // Register the extra GPS/NETWORK listeners before the primary FUSED listener.
                // On de-Googled OSs (e.g. GrapheneOS), FUSED_PROVIDER silently fails if GPS
                // hardware isn't already active. Warming up GPS first ensures FUSED can
                // piggyback on the active provider. This is a no-op on standard Android.
                updateOtherLocationListeners(locationProviderPreference, locationManager, smallestScanRate);

                locationManager.requestLocationUpdates(provider, smallestScanRate, 0f, primaryLocationListener, serviceLooper);
            } catch (Throwable t)
            {
                // An IllegalArgumentException was occurring on phones that don't have a GPS provider, so some defensive coding here
                Timber.e(t, "Could not request location updates because of an exception.");
            }
        } else
        {
            Timber.e("The location manager was null when trying to request location updates for the NetworkSurveyService");
        }
    }

    /**
     * @return The location provider that is mapped to the location provider preference. This is a one to one mapping
     * but in this method we also check the Android SDK version and also map "All" to the FUSED provider.
     */
    private String getLocationProviderFromPreference(int locationProvider)
    {
        switch (locationProvider)
        {
            // Use the FUSED provider for "All" as well, since we will be registering them all anyway.
            case LOCATION_PROVIDER_FUSED, LOCATION_PROVIDER_ALL ->
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                {
                    return LocationManager.FUSED_PROVIDER;
                } else
                {
                    return LocationManager.GPS_PROVIDER;
                }
            }
            case LOCATION_PROVIDER_GNSS ->
            {
                return LocationManager.GPS_PROVIDER;
            }
            case LOCATION_PROVIDER_NETWORK ->
            {
                return LocationManager.NETWORK_PROVIDER;
            }
            default ->
            {
                return LocationManager.GPS_PROVIDER;
            }
        }
    }

    @SuppressLint("MissingPermission")
    // Only called from updateLocationListener, which checks the permission
    private void updateOtherLocationListeners(int locationProviderPreference, LocationManager
            locationManager, int scanRate)
    {
        if (locationProviderPreference == LOCATION_PROVIDER_ALL)
        {

            locationManager.requestLocationUpdates(gnssLocationListener.getProvider(), scanRate, 0f, gnssLocationListener, serviceLooper);
            locationManager.requestLocationUpdates(networkLocationListener.getProvider(), scanRate, 0f, networkLocationListener, serviceLooper);
        } else
        {
            locationManager.removeUpdates(gnssLocationListener);
            locationManager.removeUpdates(networkLocationListener);
        }
    }

    /**
     * Removes the location listener(s) from the Android {@link LocationManager}.
     */
    private void removeLocationListener()
    {
        final LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (primaryLocationListener != null)
        {
            if (locationManager != null) locationManager.removeUpdates(primaryLocationListener);
        }
        if (gnssLocationListener != null)
        {
            if (locationManager != null) locationManager.removeUpdates(gnssLocationListener);
        }
        if (networkLocationListener != null)
        {
            if (locationManager != null) locationManager.removeUpdates(networkLocationListener);
        }
    }

    /**
     * Runs one cellular scan. This is used to prime the UI in the event that the scan interval is really long.
     */
    public void runSingleCellularScan()
    {
        cellularController.runSingleScan();
    }

    /**
     * Starts the device status report if any of the logging types are enabled (other than CDR logging), otherwise it
     * does nothing.
     */
    private void startDeviceStatusReportIfLoggingEnabled()
    {
        if (cellularController.isLoggingEnabled()
                || wifiController.isLoggingEnabled()
                || bluetoothController.isLoggingEnabled()
                || gnssController.isLoggingEnabled())
        {
            LogTypeState types = PreferenceUtils.getLogTypePreference(getApplicationContext());
            if (surveyRecordProcessor != null && types.csv)
            {
                deviceStatusCsvLogger.enableLogging(true);
                surveyRecordProcessor.registerDeviceStatusListener(deviceStatusCsvLogger);
            }
            startDeviceStatusReport(true);
        }
    }

    /**
     * Initialize and start the handler that generates a periodic Device Status Message.
     * <p>
     * This method only starts scanning if the scan is not already active.
     *
     * @param fullStart If true, also updates wake lock.
     *                  If false, only starts the device status task itself.
     */
    private void startDeviceStatusReport(boolean fullStart)
    {
        if (deviceStatusActive.getAndSet(true)) return;

        if (fullStart)
        {
            updateWakeLock();
        }

        // Cancel any existing device status task
        if (deviceStatusFuture != null)
        {
            deviceStatusFuture.cancel(true);
            deviceStatusFuture = null;
        }

        // Ensure scan rate is valid before scheduling
        if (deviceStatusScanRateMs <= 0)
        {
            Timber.w("Invalid device status scan rate: %d ms. Using default value.", deviceStatusScanRateMs);
            deviceStatusScanRateMs = NetworkSurveyConstants.DEFAULT_DEVICE_STATUS_SCAN_INTERVAL_SECONDS * 1000;
        }

        // Schedule the device status task at a fixed rate
        try
        {
            deviceStatusFuture = deviceStatusExecutor.scheduleWithFixedDelay(() -> {
                try
                {
                    if (!deviceStatusActive.get())
                    {
                        Timber.i("Device status reporting is no longer active, skipping status generation");
                        return;
                    }

                    // Skip device status generation if paused for battery management
                    if (isPausedForBattery())
                    {
                        Timber.d("Device status generation is paused for battery management");
                        return;
                    }

                    surveyRecordProcessor.onDeviceStatus(generateDeviceStatus());
                } catch (SecurityException e)
                {
                    Timber.e(e, "Could not get the required permissions to generate a device status message");
                } catch (Exception e)
                {
                    Timber.e(e, "Unexpected error generating device status message");
                }
            }, 1000L, deviceStatusScanRateMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ree)
        {
            Timber.w(ree, "Could not schedule device status reporting task - executor may be shut down");
        }

        Timber.d("Started device status reporting with interval %d ms", deviceStatusScanRateMs);
    }

    /**
     * Generate a device status message that can be sent to any remote servers.
     *
     * @return A Device Status message that can be sent to a remote server.
     * @since 1.1.0
     */
    private DeviceStatus generateDeviceStatus()
    {
        final DeviceStatusData.Builder dataBuilder = DeviceStatusData.newBuilder();
        dataBuilder.setDeviceSerialNumber(deviceId)
                .setDeviceTime(NsUtils.getRfc3339String(ZonedDateTime.now()));
        dataBuilder.setMdmOverride(BoolValue.newBuilder().setValue(mdmOverride).build());

        // Read the clock once so that the primary, GNSS, and network ages in this message are all
        // measured against the same instant and stay comparable to each other.
        final long elapsedTimeMillis = SystemClock.elapsedRealtime();

        if (primaryLocationListener != null)
        {
            final Location lastKnownLocation = primaryLocationListener.getLatestLocation();
            if (lastKnownLocation != null)
            {
                dataBuilder.setLatitude(lastKnownLocation.getLatitude());
                dataBuilder.setLongitude(lastKnownLocation.getLongitude());
                dataBuilder.setAltitude((float) lastKnownLocation.getAltitude());
                dataBuilder.setAccuracy(MathUtils.roundAccuracy(lastKnownLocation.getAccuracy()));
                dataBuilder.setLocationAge(SurveyRecordProcessor.getLocationAgeMs(lastKnownLocation, elapsedTimeMillis));

                // Only ever set when the location really was mocked. Mocking is the exceptional
                // case, so stamping every ordinary record with an explicit false would spend wire
                // bytes on every message, and a row of the CSV, to say nothing.
                if (LocationUtils.isMock(lastKnownLocation))
                {
                    dataBuilder.setMockLocation(BoolValue.of(true));
                }
                if (lastKnownLocation.hasSpeed())
                {
                    float speed = FormatUtils.formatSpeed(lastKnownLocation.getSpeed());
                    if (speed != 0f)
                    {
                        dataBuilder.setSpeed(speed);
                    }
                }
            }
        }

        if (locationProviderPreference == LOCATION_PROVIDER_ALL)
        {
            // Add the extra locations to the message. The listeners hold the last fix from each
            // provider indefinitely, so the age is what tells a consumer whether the provider is
            // still producing fixes or has gone quiet while the app keeps reporting the old one.
            if (gnssLocationListener != null)
            {
                Location gnssLocation = gnssLocationListener.getLatestLocation();
                if (gnssLocation != null)
                {
                    dataBuilder.setGnssLatitude(gnssLocation.getLatitude());
                    dataBuilder.setGnssLongitude(gnssLocation.getLongitude());
                    dataBuilder.setGnssAltitude((float) gnssLocation.getAltitude());
                    dataBuilder.setGnssAccuracy(MathUtils.roundAccuracy(gnssLocation.getAccuracy()));
                    dataBuilder.setGnssAge(SurveyRecordProcessor.getLocationAgeMs(gnssLocation, elapsedTimeMillis));
                }
            }

            if (networkLocationListener != null)
            {
                Location networkLocation = networkLocationListener.getLatestLocation();
                if (networkLocation != null)
                {
                    dataBuilder.setNetworkLatitude(networkLocation.getLatitude());
                    dataBuilder.setNetworkLongitude(networkLocation.getLongitude());
                    dataBuilder.setNetworkAltitude((float) networkLocation.getAltitude());
                    dataBuilder.setNetworkAccuracy(MathUtils.roundAccuracy(networkLocation.getAccuracy()));
                    dataBuilder.setNetworkAge(SurveyRecordProcessor.getLocationAgeMs(networkLocation, elapsedTimeMillis));
                }
            }
        }

        final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        final Intent batteryStatus = registerReceiver(null, intentFilter);
        if (batteryStatus != null)
        {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            final float batteryPercent = (level / (float) scale) * 100;
            dataBuilder.setBatteryLevelPercent(Int32Value.of((int) batteryPercent));
        }

        dataBuilder.setDeviceModel(Build.MODEL);
        dataBuilder.setAppVersion(NsUtils.getAppVersionName(this));

        final DeviceStatus.Builder statusBuilder = DeviceStatus.newBuilder();
        statusBuilder.setMessageType(DeviceStatusMessageConstants.DEVICE_STATUS_MESSAGE_TYPE);
        statusBuilder.setVersion(BuildConfig.MESSAGING_API_VERSION);
        statusBuilder.setData(dataBuilder);

        return statusBuilder.build();
    }

    /**
     * Stops generating device status reports if no more loggers are enabled.
     */
    private void stopDeviceStatusReportIfNotNeeded()
    {
        if (!deviceStatusActive.get()) return;

        LogTypeState types = PreferenceUtils.getLogTypePreference(getApplicationContext());

        // We only want to stop the device status report if all of the logging types are inactive or CSV logging is off
        if (!types.csv ||
                // Not checking for CDR logging since that is a different beast (it logs at a much lower rate)
                (!cellularController.isLoggingEnabled() && !wifiController.isLoggingEnabled() && !bluetoothController.isLoggingEnabled() && !gnssController.isLoggingEnabled())
        )
        {
            surveyRecordProcessor.unregisterDeviceStatusListener(deviceStatusCsvLogger);
            deviceStatusCsvLogger.enableLogging(false);

            // Need to check the survey record processor because MQTT could be using the device status message
            if (!surveyRecordProcessor.isDeviceStatusBeingUsed()) stopDeviceStatusReport(true);
        }
    }

    /**
     * Restarts the device status report with a new scan rate.
     * This method stops and restarts the device status task without affecting
     * the phone state listener, wake lock, or location listener.
     */
    private void restartDeviceStatusReport()
    {
        stopDeviceStatusReport(false);
        startDeviceStatusReport(false);
    }

    /**
     * Stop generating device status messages.
     *
     * @param fullStop If true, also updates wake lock/location listener.
     *                 If false, only stops the device status task itself.
     */
    private void stopDeviceStatusReport(boolean fullStop)
    {
        deviceStatusActive.set(false);

        // Cancel the scheduled device status task
        if (deviceStatusFuture != null)
        {
            deviceStatusFuture.cancel(true);
            deviceStatusFuture = null;
            Timber.d("Cancelled device status reporting task");
        }

        if (fullStop)
        {
            updateLocationListener();
            updateWakeLock();
        }
    }

    /**
     * A notification for this service that is started in the foreground so that we can continue to get GPS location
     * updates while the phone is locked or the app is not in the foreground.
     */
    public void updateServiceNotification()
    {
        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            {
                startForeground(NetworkSurveyConstants.LOGGING_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else
            {
                startForeground(NetworkSurveyConstants.LOGGING_NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e)
        {
            Timber.e(e, "Could not start the foreground service for Network Survey");
            // TODO This is one possible option for the crash on Samsung S22 devices running Android 13
            /*AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            {
                if (alarmManager.canScheduleExactAlarms())
                {
                    Intent i = new Intent(this, NetworkSurveyService.class);
                    PendingIntent pi = PendingIntent.getForegroundService(this, 50, i, PendingIntent.FLAG_UPDATE_CURRENT);
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + GO_OFF_OFFSET, pi);
                } else
                {
                    Timber.e("Can't schedule an exact alarm in place of startForeground");
                }
            }*/
        }
    }

    /**
     * Creates a new {@link Notification} based on the current state of this service.  The returned notification can
     * then be passed on to the Android system.
     *
     * @return A {@link Notification} that represents the current state of this service (e.g. if logging is enabled).
     */
    private Notification buildNotification()
    {
        Application.createNotificationChannel(this);

        final boolean logging = cellularController.isLoggingEnabled() || cellularController.isPhoneStateLoggingEnabled() || wifiController.isLoggingEnabled() || bluetoothController.isLoggingEnabled() || gnssController.isLoggingEnabled() || cellularController.isCdrLoggingEnabled();
        final CharSequence notificationTitle = getText(R.string.network_survey_notification_title);
        final String notificationText = getNotificationText(logging);

        final Intent notificationIntent = new Intent(this, NetworkSurveyActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NetworkSurveyConstants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(logging ? R.drawable.logging_thick_icon : R.drawable.gps_map_icon)
                .setContentIntent(pendingIntent)
                .setTicker(notificationTitle)
                .setContentText(notificationText)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText));

        return builder.build();
    }

    /**
     * Gets the text to use for the Network Survey Service Notification.
     *
     * @param logging True if logging is active, false if disabled.
     * @return The text that can be added to the service notification.
     */
    private String getNotificationText(boolean logging)
    {
        // Check if operations are paused due to battery (highest priority - requires external action)
        if (batteryMonitor != null && batteryMonitor.isPausedDueToBattery())
        {
            final int batteryLevel = batteryMonitor.getCurrentBatteryLevel();
            return getString(R.string.battery_paused_notification_text, batteryLevel);
        }

        String notificationText = "";

        if (logging)
        {
            notificationText = String.valueOf(getText(R.string.logging_notification_text));
        }

        return notificationText;
    }

    /**
     * If any of the loggers are still active, this stops them all just to be safe. If they are not active then nothing
     * changes.
     *
     * @since 0.3.0
     */
    private void stopAllLogging()
    {
        cellularController.stopAllLogging();
        wifiController.stopAllLogging();
        bluetoothController.stopAllLogging();
        gnssController.stopAllLogging();
        if (deviceStatusCsvLogger != null) deviceStatusCsvLogger.enableLogging(false);
    }

    /**
     * Close out the notification since we no longer need this service.
     */
    private void shutdownNotifications()
    {
        stopForeground(true);
    }

    /**
     * Register a listener so that if the Managed Config changes we will be notified of the new config and can restart
     * the MQTT broker connection with the new parameters.
     *
     * @since 0.1.1
     */
    private void registerManagedConfigurationListener()
    {
        final IntentFilter restrictionsFilter = new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);

        managedConfigurationListener = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                setScanRateValues();

                cellularController.onMdmPreferenceChanged();
                wifiController.onMdmPreferenceChanged();
                bluetoothController.onMdmPreferenceChanged();
                gnssController.onMdmPreferenceChanged();

                deviceStatusCsvLogger.onMdmPreferenceChanged();
            }
        };

        registerReceiver(managedConfigurationListener, restrictionsFilter);
    }

    /**
     * Remove the managed configuration listener.
     *
     * @since 0.1.1
     */
    private void unregisterManagedConfigurationListener()
    {
        if (managedConfigurationListener != null)
        {
            try
            {
                unregisterReceiver(managedConfigurationListener);
            } catch (Exception e)
            {
                Timber.e(e, "Unable to unregister the Managed Configuration Listener when pausing the app");
            }
            managedConfigurationListener = null;
        }
    }

/**
     * Notify {@link #loggingChangeListeners} that one or more of the logging states have changed.
     *
     * @since 1.10.0
     */
    public void notifyLoggingChangedListeners()
    {
        loggingChangeListeners.forEach(l -> {
            try
            {
                l.onLoggingChanged();
            } catch (Exception e)
            {
                Timber.e(e, "Unable to notify a Logging Changed Listener because of an exception");
            }
        });
    }

    /**
     * Registers a listener any GNSS failures. This can include timing out before we received any
     * GNSS measurements.
     *
     * @param gnssFailureListener The listener.
     * @since 0.4.0
     */
    public void registerGnssFailureListener(IGnssFailureListener gnssFailureListener)
    {
        gnssController.registerGnssFailureListener(gnssFailureListener);
    }

    /**
     * Clears the GNSS failure listener.
     *
     * @since 0.4.0
     */
    public void clearGnssFailureListener()
    {
        gnssController.clearGnssFailureListener();
    }

    /**
     * Class used for the client Binder.  Because we know this service always runs in the same process as its clients,
     * we don't need to deal with IPC.
     */
    public static class SurveyServiceBinder extends Binder
    {
        public NetworkSurveyService service;

        public SurveyServiceBinder(NetworkSurveyService service)
        {
            this.service = service;
        }

        public NetworkSurveyService getService()
        {
            return service;
        }

        public void onDestroy()
        {
            service = null;
        }
    }

    // ========================================
    // Battery Management Methods
    // ========================================

    /**
     * Handles changes to battery management preferences.
     */
    private void handleBatteryPreferenceChange()
    {
        final boolean batteryManagementEnabled = PreferenceUtils.isBatteryManagementEnabled(this);

        if (batteryManagementEnabled)
        {
            // Register battery monitor if not already registered
            if (batteryMonitor != null)
            {
                batteryMonitor.unregister(this); // Unregister first to avoid duplicate registration
                batteryMonitor.register(this);
                batteryMonitor.reevaluateThreshold(); // Force re-check with new threshold
            }
        } else
        {
            // Unregister battery monitor and resume if paused
            if (batteryMonitor != null)
            {
                batteryMonitor.unregister(this);

                // If we were paused, resume operations
                if (batteryPauseState != null && batteryPauseState.hasActiveOperations())
                {
                    Timber.i("Battery management disabled, resuming operations");
                    resumePreviousOperations();
                }
            }
        }
    }

    @Override
    public void onBatteryLevelChanged(int newLevel)
    {
        // Dashboard will handle updating the battery level display
    }

    @Override
    public void onBatteryLevelBelowThreshold(int currentLevel, int threshold)
    {
        Timber.i("Battery level %d%% is below threshold %d%%, pausing operations", currentLevel, threshold);
        pauseAllOperations();
        updateServiceNotification();
    }

    @Override
    public void onBatteryLevelAboveThreshold(int currentLevel, int threshold)
    {
        Timber.i("Battery level %d%% is above threshold %d%%, resuming operations", currentLevel, threshold);
        resumePreviousOperations();
        updateServiceNotification();
    }

    /**
     * Pauses all active survey operations and saves their state.
     */
    private void pauseAllOperations()
    {
        // Create new pause state to track what was active
        batteryPauseState = new BatteryPauseState();

        // Save current scanning states
        batteryPauseState.setWasCellularScanningActive(cellularController.isScanningActive());
        batteryPauseState.setWasWifiScanningActive(wifiController.isScanningActive());
        batteryPauseState.setWasBluetoothScanningActive(bluetoothController.isScanningActive());
        batteryPauseState.setWasGnssScanningActive(gnssController.isScanningActive());
        batteryPauseState.setWasCdrScanningActive(cellularController.isCdrLoggingEnabled());

        // Save current logging states
        batteryPauseState.setWasCellularLoggingEnabled(cellularController.isLoggingEnabled());
        batteryPauseState.setWasWifiLoggingEnabled(wifiController.isLoggingEnabled());
        batteryPauseState.setWasBluetoothLoggingEnabled(bluetoothController.isLoggingEnabled());
        batteryPauseState.setWasGnssLoggingEnabled(gnssController.isLoggingEnabled());
        batteryPauseState.setWasCdrLoggingEnabled(cellularController.isCdrLoggingEnabled());

        // Save device status state
        batteryPauseState.setWasDeviceStatusActive(deviceStatusActive.get());

        // Persist the state
        saveBatteryPauseState();

        // Pause all controllers
        cellularController.pauseScanning();
        wifiController.pauseScanning();
        bluetoothController.pauseScanning();
        gnssController.pauseScanning();

        // Remove location listeners to save battery
        removeLocationListener();

        // Note: We don't disconnect MQTT/gRPC or stop logging completely
        // The controllers will skip their work when isPaused() returns true

        // Future optimization: Implement IPauseAware for connections to reduce battery usage
        // Currently, connections stay active but receive no data when controllers are paused
        // This is acceptable as it avoids reconnection overhead, but gRPC queue polling
        // could be optimized to reduce battery drain during pause
    }

    /**
     * Resumes operations that were active before the battery pause.
     */
    private void resumePreviousOperations()
    {
        if (batteryPauseState == null)
        {
            Timber.w("No battery pause state found, nothing to resume");
            return;
        }

        // Resume all controllers (they will check their internal state)
        cellularController.resumeScanning();
        wifiController.resumeScanning();
        bluetoothController.resumeScanning();
        gnssController.resumeScanning();

        // Restore location listeners
        updateLocationListener();

        // Clear the saved state
        batteryPauseState = null;
        clearBatteryPauseState();
    }

    /**
     * Saves the battery pause state to preferences.
     */
    private void saveBatteryPauseState()
    {
        if (batteryPauseState == null) return;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final SharedPreferences.Editor editor = prefs.edit();

        final String json = batteryPauseState.toJson();
        if (json != null)
        {
            editor.putString(PROPERTY_BATTERY_PAUSE_STATE, json);
            editor.apply();
        }
    }

    /**
     * Loads the battery pause state from preferences.
     */
    private BatteryPauseState loadBatteryPauseState()
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String json = prefs.getString(PROPERTY_BATTERY_PAUSE_STATE, null);

        if (json != null)
        {
            return BatteryPauseState.fromJson(json);
        }

        return null;
    }

    /**
     * Clears the saved battery pause state.
     */
    private void clearBatteryPauseState()
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final SharedPreferences.Editor editor = prefs.edit();
        editor.remove(PROPERTY_BATTERY_PAUSE_STATE);
        editor.apply();
    }

    /**
     * Checks if operations are currently paused due to low battery.
     *
     * @return true if paused due to battery, false otherwise
     */
    public boolean isPausedForBattery()
    {
        return batteryMonitor != null && batteryMonitor.isPausedDueToBattery();
    }

    /**
     * Gets the current battery level percentage.
     *
     * @return The current battery level (0-100), or -1 if unknown
     */
    public int getCurrentBatteryLevel()
    {
        return batteryMonitor != null ? batteryMonitor.getCurrentBatteryLevel() : -1;
    }

    /**
     * Gets the battery monitor instance.
     *
     * @return The BatteryMonitor instance, or null if not initialized
     */
    public BatteryMonitor getBatteryMonitor()
    {
        return batteryMonitor;
    }

    }
