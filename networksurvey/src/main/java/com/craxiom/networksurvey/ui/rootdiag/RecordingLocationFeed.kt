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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.Executors

/** UI observes this to offer another CQT mark. The listener itself is not tied to the screen. */
internal object RecordingLocationMarks {
    val active = MutableStateFlow(false)
    @Volatile var remark: (() -> Unit)? = null

    fun bind(onRemark: () -> Unit) {
        remark = onRemark
        active.value = true
    }

    fun clear() {
        remark = null
        active.value = false
    }
}

/**
 * One location request owned by the recording session.
 * It keeps running after the map screen pauses. Updates stop only when the session does.
 */
internal class RecordingLocationFeed(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private val callbacks by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recording-location").apply { isDaemon = true }
        }
    }
    private val updates = Channel<RecordedFix>(Channel.BUFFERED)
    private var listener: LocationListener? = null
    private var taskId: String = ""
    private var clockId: String = ""

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
            callbacks.execute {
                runCatching { onFix(fix) }.onFailure { Timber.w(it, "Could not record location") }
            }
            updates.trySend(fix)
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

    suspend fun collect(timeoutMs: Long): List<RecordedFix> {
        val found = mutableListOf<RecordedFix>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val next = withTimeoutOrNull(remaining) { updates.receive() } ?: break
            if (next.taskId != taskId) continue
            found += next
            if (selectCachedFix(found) != null) break
        }
        return found
    }

    fun drainPending() {
        while (updates.tryReceive().isSuccess) Unit
    }

    fun stop() {
        listener?.let { current -> runCatching { manager?.removeUpdates(current) } }
        listener = null
        drainPending()
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
    private val scope: CoroutineScope,
    private val recallMode: (String) -> SurveyLocationMode?,
    private val clockId: () -> String,
) {
    private var job: Job? = null
    private var remarks = Channel<Unit>(Channel.CONFLATED)

    fun onSessionStarted(taskId: String) {
        if (sidecar.isAccepting()) {
            Timber.w("Location sidecar was still open")
            return
        }
        val mode = recallMode(taskId)
        if (!shouldOpenLocationSidecar(recordingSessionOpen = true, mode)) return
        val clock = clockId()
        try {
            sidecar.open(taskId, mode!!, clock)
        } catch (error: Exception) {
            Timber.w(error, "Could not open location sidecar")
            return
        }
        feed.start(taskId, clock) { fix ->
            if (mode == SurveyLocationMode.DT) sidecar.accept(fix)
        }
        if (mode != SurveyLocationMode.CQT) return
        remarks = Channel(Channel.CONFLATED)
        val incoming = remarks
        RecordingLocationMarks.bind { incoming.trySend(Unit) }
        job = scope.launch(Dispatchers.IO) {
            writeCqt(remark = false)
            for (ignored in incoming) {
                if (!sidecar.isAccepting()) break
                writeCqt(remark = true)
            }
        }
    }

    suspend fun onSessionFinished() {
        sidecar.stopAccepting()
        feed.stop()
        val running = job
        job = null
        running?.cancel()
        running?.join()
        RecordingLocationMarks.clear()
        runCatching { sidecar.finish() }.onFailure { Timber.w(it, "Could not archive location sidecar") }
    }

    private suspend fun writeCqt(remark: Boolean) {
        if (!sidecar.isAccepting()) return
        if (remark && !sidecar.allowRemark()) return
        val cached = selectCachedFix(feed.cachedFixes())
        if (cached != null) {
            sidecar.captureCqt(cached, coarse = false)
            return
        }
        if (!sidecar.isAccepting()) return
        if (remark) feed.drainPending()
        val samples = feed.collect(CQT_WAIT_MS)
        if (!sidecar.isAccepting()) return
        val qualified = selectCachedFix(samples)
        if (qualified != null) sidecar.captureCqt(qualified, coarse = false)
        else sidecar.captureCqt(chooseCoarseFix(samples), coarse = true)
    }
}
