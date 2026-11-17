package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat

/** Broadcasts are hints; query authoritative state after coalescing on the service looper. */
internal class LyricsScreenMonitor(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: () -> Unit
) : AutoCloseable {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var started = false
    private val refresh = Runnable { if (started) onChanged() }
    private val settledRefresh = Runnable { if (started) onChanged() }
    private fun schedule(recheck: Boolean = false) {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        if (recheck) {
            // Keyguard can settle after SCREEN_ON/display callbacks. One bounded recheck,
            // rather than a lock-screen polling loop, handles that ordering.
            handler.removeCallbacks(settledRefresh)
            handler.postDelayed(settledRefresh, 250L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = schedule(intent?.action == Intent.ACTION_SCREEN_ON)
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedule()
    }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = schedule()
        override fun onDisplayRemoved(displayId: Int) = schedule()
        override fun onDisplayChanged(displayId: Int) = schedule(recheck = true)
    }

    fun screenClass(): LyricsScreenClass = when (displays.getDisplay(Display.DEFAULT_DISPLAY)?.state) {
        Display.STATE_DOZE -> LyricsScreenClass.DOZE
        Display.STATE_DOZE_SUSPEND -> LyricsScreenClass.DOZE_SUSPEND
        Display.STATE_OFF -> LyricsScreenClass.OFF
        Display.STATE_ON -> when {
            !power.isInteractive -> LyricsScreenClass.OFF
            keyguard.isKeyguardLocked -> LyricsScreenClass.LOCKED_INTERACTIVE
            else -> LyricsScreenClass.UNLOCKED
        }
        else -> LyricsScreenClass.UNKNOWN
    }

    fun allowsMotion(): Boolean = !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()

    fun start() {
        if (started) return
        started = true
        displays.registerDisplayListener(listener, handler)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
        )
    }

    override fun close() {
        if (!started) return
        started = false
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(settledRefresh)
        displays.unregisterDisplayListener(listener)
        runCatching { context.unregisterReceiver(receiver) }
        context.contentResolver.unregisterContentObserver(observer)
    }
}
