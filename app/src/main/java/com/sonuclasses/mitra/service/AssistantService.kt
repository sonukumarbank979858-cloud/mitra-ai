package com.sonuclasses.mitra.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.sonuclasses.mitra.MitraApp
import com.sonuclasses.mitra.R
import com.sonuclasses.mitra.core.Assistant
import com.sonuclasses.mitra.core.Prefs
import com.sonuclasses.mitra.core.VState
import com.sonuclasses.mitra.ui.MainActivity
import com.sonuclasses.mitra.ui.overlay.BubbleView
import com.sonuclasses.mitra.ui.overlay.EdgeRingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Foreground service (type microphone) that keeps the voice session alive and hosts the overlay bubble + edge ring. */
class AssistantService : Service() {
    companion object {
        const val ACTION_START = "com.sonuclasses.mitra.START"
        const val ACTION_SYNC = "com.sonuclasses.mitra.SYNC"
        const val ACTION_STOP_VOICE = "com.sonuclasses.mitra.STOP_VOICE"
        const val ACTION_TOGGLE = "com.sonuclasses.mitra.TOGGLE"
        private const val NOTIF_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var ring: EdgeRingView? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        scope.launch {
            combine(Assistant.state, Assistant.level) { s, l -> s to l }.collect { (s, l) ->
                bubble?.update(s, l)
                ring?.update(s, l)
                if (foreground) updateNotification(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VOICE -> Assistant.stopVoice(this)
            ACTION_TOGGLE -> Assistant.toggleVoice(this)
        }
        // Always satisfy startForegroundService() first, then decide whether we are still needed.
        if (!foreground) goForeground()
        val needed = Assistant.active || (Prefs.overlayEnabled && Settings.canDrawOverlays(this))
        if (!needed) { stopEverything(); return START_NOT_STICKY }
        syncOverlay()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = buildNotification(Assistant.state.value)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
            foreground = true
        } catch (e: Exception) {
            // e.g. microphone permission missing or started from background on Android 14
            foreground = false
        }
    }

    private fun buildNotification(s: VState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP_VOICE), PendingIntent.FLAG_IMMUTABLE)
        val text = when (s) {
            VState.IDLE -> "तैयार — बबल दबाकर बोलें"
            VState.CONNECTING -> "जुड़ रहा है…"
            VState.LISTENING -> "सुन रहा हूँ…"
            VState.THINKING -> "सोच रहा हूँ…"
            VState.SPEAKING -> "बोल रहा हूँ…"
        }
        val b = NotificationCompat.Builder(this, MitraApp.CH_SESSION)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(Prefs.personaName)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        if (s != VState.IDLE) b.addAction(0, "बंद करें", stop)
        return b.build()
    }

    private fun updateNotification(s: VState) {
        try { getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s)) } catch (_: Exception) {}
        if (s == VState.IDLE && !(Prefs.overlayEnabled && Settings.canDrawOverlays(this))) stopEverything()
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun syncOverlay() {
        val can = Prefs.overlayEnabled && Settings.canDrawOverlays(this)
        if (!can) { removeOverlay(); return }
        val w = wm ?: return
        if (ring == null) {
            val r = EdgeRingView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            try { w.addView(r, lp); ring = r } catch (_: Exception) {}
        }
        if (bubble == null) {
            val b = BubbleView(this)
            val size = (64 * resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = resources.displayMetrics.widthPixels - size - 16
                y = resources.displayMetrics.heightPixels / 2
            }
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            b.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { w.updateViewLayout(v, lp) } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) Assistant.toggleVoice(this); true }
                    else -> false
                }
            }
            b.setOnLongClickListener { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            try { w.addView(b, lp); bubble = b } catch (_: Exception) {}
        }
        bubble?.update(Assistant.state.value, Assistant.level.value)
        ring?.update(Assistant.state.value, Assistant.level.value)
    }

    private fun removeOverlay() {
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        ring?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null; ring = null
    }

    private fun stopEverything() {
        removeOverlay()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }
}
