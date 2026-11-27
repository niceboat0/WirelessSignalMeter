package com.craxiom.networksurvey.ui.rootdiag

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.craxiom.networksurvey.model.SurveyLocationMode
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * One location request owned by the recording session.
 * It keeps running after the map screen pauses. Updates stop only when the session does.
 * CQT uses the latest fix only as optional metadata on a floor-plan tap.
 */
internal class RecordingLocationFeed(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private val callbacks by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recording-location").apply { isDaemon = true }
        }
    }
    private var listener: LocationListener? = null
    private var taskId: String = ""
    private var clockId: String = ""
    @Volatile private var latest: RecordedFix? = null

    fun start(taskId: String, clockId: String, onFix: (RecordedFix) -> Unit) {
        stop()
        this.taskId = taskId
        this.clockId = clockId
        val locationManager = manager ?: return
        if (!permitted()) return
        val order = providerOrder(
            providerEnabled(locationManager, LocationManager.GPS_PROVIDER),
            providerEnabled(locationManager, LocationManager.FUSED_PROVIDER),
            providerEnabled(locationManager, LocationManager.NETWORK_PROVIDER),
        )
        if (order.isEmpty()) return
        val next = LocationListener { location ->
            val fix = location.toRecordedFix(taskId, clockId) ?: return@LocationListener
            latest = fix
            callbacks.execute {
                runCatching { onFix(fix) }.onFailure { Timber.w(it, "Could not record location") }
            }
        }
        for (provider in order) {
            try {
                // minTime is a lower hint. A 1 second request is not a delivered 1 Hz track.
                locationManager.requestLocationUpdates(
                    provider,
                    RECORDING_LOCATION_INTERVAL_MS,
                    RECORDING_LOCATION_MIN_DISTANCE_M,
                    next,
                    Looper.getMainLooper(),
                )
                listener = next
                return
            } catch (_: SecurityException) {
                return
            } catch (_: IllegalArgumentException) {
                continue
            } catch (error: RuntimeException) {
                Timber.w(error, "Could not request recording location")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun cachedFixes(): List<RecordedFix> {
        val locationManager = manager ?: return emptyList()
        if (!permitted() || taskId.isEmpty() || clockId.isEmpty()) return emptyList()
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider)?.toRecordedFix(taskId, clockId) }.getOrNull()
        }
    }

    /** The newest fix already delivered to this session, or a qualified cached fix. */
    fun latestFix(): RecordedFix? {
        val current = latest?.takeIf { it.taskId == taskId }
        return current ?: selectCachedFix(cachedFixes())
    }

    fun stop() {
        listener?.let { current -> runCatching { manager?.removeUpdates(current) } }
        listener = null
        latest = null
    }

    private fun permitted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providerEnabled(locationManager: LocationManager, provider: String): Boolean =
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
}

private fun Location.toRecordedFix(taskId: String, clockId: String): RecordedFix? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    val elapsed = elapsedRealtimeNanos / 1_000_000L
    return RecordedFix(
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        provider = provider,
        ageMs = SystemClock.elapsedRealtime() - elapsed,
        wallTimeMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        elapsedRealtimeMs = elapsed,
        clockId = clockId,
        taskId = taskId,
    )
}

internal class RecordingLocationCoordinator(
    private val sidecar: RecordingLocationSidecar,
    private val feed: RecordingLocationFeed,
    private val recallMode: (String) -> SurveyLocationMode?,
    private val clockId: () -> String,
    private val floorPlans: CqtFloorPlanLibrary,
) {
    fun onSessionStarted(taskId: String) {
        if (sidecar.isAccepting()) {
            Timber.w("Location sidecar was still open")
            return
        }
        val mode = recallMode(taskId)
        if (!shouldOpenLocationSidecar(recordingSessionOpen = true, mode)) return
        val clock = clockId()
        val stored = if (mode == SurveyLocationMode.CQT) floorPlans.recall(taskId) else null
        try {
            sidecar.open(taskId, mode!!, clock, stored?.plan ?: CqtFloorPlan.blank(), stored?.image)
        } catch (error: Exception) {
            Timber.w(error, "Could not open location sidecar")
            return
        }
        feed.start(taskId, clock) { fix ->
            if (mode == SurveyLocationMode.DT) sidecar.accept(fix)
        }
        if (mode != SurveyLocationMode.CQT) return
        val plan = stored?.plan ?: CqtFloorPlan.blank()
        CqtFloorMarks.bind(
            plan = plan,
            onMark = { point ->
                sidecar.mark(point.copy(clockId = clock, taskId = taskId, gnss = feed.latestFix()))
            },
            onUndo = { sidecar.undo() },
            onReplaceFloor = { next, image -> sidecar.replaceFloor(next, image) },
        )
    }

    suspend fun onSessionFinished(retainSidecar: Boolean) {
        sidecar.stopAccepting()
        feed.stop()
        CqtFloorMarks.clear()
        if (!retainSidecar) {
            runCatching { sidecar.discard() }.onFailure { Timber.w(it, "Could not discard location sidecar") }
            return
        }
        runCatching { sidecar.finish() }.onFailure { Timber.w(it, "Could not archive location sidecar") }
    }
}
