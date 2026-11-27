package com.craxiom.networksurvey.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.rootdiag.DiagOverlaySnapshot
import com.craxiom.networksurvey.ui.rootdiag.DiagOverlayState
import com.craxiom.networksurvey.ui.rootdiag.RootDiagPrefs
import com.craxiom.networksurvey.ui.rootdiag.imsHeadlineId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that keeps DIAG collection alive after the UI is stopped.
 * It never force-starts overlay permission settings and only manages its own views.
 */
class RootDiagCollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlayJob: Job? = null
    private var window: WindowManager? = null
    private var overlay: View? = null
    private var label: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            serviceState.value = State.Idle
            stopSelf()
            return
        }
        serviceState.value = State.Running
        scope.launch {
            DiagOverlayState.snapshot.collect { snap ->
                CaptureServiceNotification.updateDiag(this@RootDiagCollectorService, snap.isTask())
            }
        }
        maybeShowOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested.value = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_OVERLAY -> maybeShowOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceState.value = State.Idle
        hideOverlay()
        scope.cancel()
        CaptureServiceNotification.stopDiag(this)
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        try {
            CaptureServiceNotification.startDiag(
                this,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                DiagOverlayState.snapshot.value.isTask(),
            )
            return true
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to start the DIAG foreground service")
            return false
        }
    }

    private fun DiagOverlaySnapshot.isTask() = recording

    private fun maybeShowOverlay() {
        if (!RootDiagPrefs.overlayWanted(this) || !Settings.canDrawOverlays(this)) {
            hideOverlay()
            return
        }
        if (overlay != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        window = manager
        val text = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(8, 4, 8, 4)
        }
        label = text
        val close = TextView(this).apply {
            setTextColor(0xFFFFC107.toInt())
            setText("×")
            textSize = 16f
            setPadding(12, 4, 8, 4)
            setOnClickListener {
                RootDiagPrefs.setOverlayWanted(this@RootDiagCollectorService, false)
                hideOverlay()
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC1B1B1B.toInt())
            setPadding(4, 2, 4, 2)
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
            elevation = 8f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        var lastX = 0
        var lastY = 0
        var startX = 0
        var startY = 0
        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = lastX + event.rawX.toInt() - startX
                    params.y = lastY + event.rawY.toInt() - startY
                    runCatching { manager.updateViewLayout(card, params) }
                    true
                }
                else -> false
            }
        }
        try {
            manager.addView(card, params)
            overlay = card
            overlayJob = scope.launch {
                DiagOverlayState.snapshot.collect { snap -> label?.text = formatOverlay(snap) }
            }
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to add the DIAG overlay")
            hideOverlay()
        }
    }

    private fun formatOverlay(snap: DiagOverlaySnapshot): String {
        val parts = mutableListOf<String>()
        parts += when {
            snap.recording || snap.status == "recording" -> getString(R.string.diag_overlay_recording)
            snap.status == "task" -> getString(R.string.diag_overlay_task_open)
            snap.status == "listening" -> getString(R.string.diag_overlay_listening)
            snap.status == "standby" -> getString(R.string.diag_overlay_standby)
            else -> snap.status.ifBlank { getString(R.string.diag_overlay_standby) }
        }
        if (RootDiagPrefs.showRf(this)) {
            val rf = buildString {
                if (snap.rat.isNotBlank()) append(snap.rat)
                snap.pci?.let { append(" PCI ").append(it) }
                snap.rsrp?.let { append(" ").append("%.0f".format(it)).append(" dBm") }
                snap.sinr?.let { append(" / ").append("%.1f".format(it)).append(" dB") }
            }
            if (rf.isNotBlank()) parts += rf
        }
        if (RootDiagPrefs.showIms(this)) {
            val ims = when {
                snap.imsAvailable == false && snap.imsReg.isBlank() -> getString(R.string.diag_ims_source_unavailable)
                snap.imsReg.startsWith("ims_") -> {
                    val headline = getString(imsHeadlineId(snap.imsReg))
                    val call = when (snap.imsCall) {
                        "calling" -> getString(R.string.diag_ims_call_calling)
                        "alerting" -> getString(R.string.diag_ims_call_alerting)
                        "connected" -> getString(R.string.diag_ims_call_connected)
                        else -> ""
                    }
                    val line = getString(R.string.diag_ims_line, headline)
                    if (call.isBlank()) line else "$line · $call"
                }
                else -> buildString {
                    append(snap.imsReg.ifBlank { getString(R.string.diag_ims_headline_unknown_waiting) })
                    if (snap.imsCall.isNotBlank() && snap.imsCall != "idle") append(" · ").append(snap.imsCall)
                }
            }
            parts += ims
        }
        if (RootDiagPrefs.showQueue(this) && snap.decodeDropped > 0) {
            parts += getString(R.string.diag_overlay_dropped, snap.decodeDropped)
        }
        return parts.joinToString("\n")
    }

    private fun hideOverlay() {
        overlayJob?.cancel()
        overlayJob = null
        overlay?.let { view ->
            runCatching { window?.removeView(view) }
        }
        overlay = null
        label = null
    }

    companion object {
        const val ACTION_STOP = "com.craxiom.networksurvey.action.STOP_ROOT_DIAG"
        const val ACTION_REFRESH_OVERLAY = "com.craxiom.networksurvey.action.REFRESH_OVERLAY"
        internal enum class State { Idle, Starting, Running }
        private val serviceState = MutableStateFlow(State.Idle)
        internal val state = serviceState.asStateFlow()
        val stopRequested = MutableStateFlow(false)
        internal val isMaintained get() = serviceState.value != State.Idle

        fun start(context: Context) {
            if (isMaintained) return
            val intent = Intent(context, RootDiagCollectorService::class.java)
            serviceState.value = State.Starting
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (error: RuntimeException) {
                Timber.w(error, "Unable to launch the DIAG foreground service")
                serviceState.value = State.Idle
            }
        }

        fun stop(context: Context) {
            if (!isMaintained) return
            context.stopService(Intent(context, RootDiagCollectorService::class.java))
            serviceState.value = State.Idle
        }

        fun refreshOverlay(context: Context) {
            if (serviceState.value == State.Running) {
                context.startService(Intent(context, RootDiagCollectorService::class.java).setAction(ACTION_REFRESH_OVERLAY))
            }
        }
    }
}
