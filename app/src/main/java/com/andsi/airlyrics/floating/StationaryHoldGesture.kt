package com.andsi.airlyrics.floating

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

class StationaryHoldGesture(
    private val holdDurationMs: () -> Long,
    private val driftThresholdPx: Float = DEFAULT_DRIFT_THRESHOLD_PX,
    private val onHoldTriggered: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var downRawX = 0f
    private var downRawY = 0f
    private var active = false
    private var triggered = false

    private val triggerRunnable = Runnable {
        if (active) {
            triggered = true
            active = false
            onHoldTriggered()
        }
    }

    fun onTouchDown(event: MotionEvent, enabled: Boolean) {
        cancel()
        if (!enabled) return
        downRawX = event.rawX
        downRawY = event.rawY
        active = true
        triggered = false
        handler.postDelayed(triggerRunnable, holdDurationMs())
    }

    fun onTouchMove(event: MotionEvent): Boolean {
        if (!active) return false
        val dx = abs(event.rawX - downRawX)
        val dy = abs(event.rawY - downRawY)
        if (dx >= driftThresholdPx || dy >= driftThresholdPx) {
            cancel()
            return true
        }
        return false
    }

    fun cancel() {
        active = false
        handler.removeCallbacks(triggerRunnable)
    }

    fun wasTriggered(): Boolean = triggered

    fun resetTriggerFlag() {
        triggered = false
    }

    companion object {
        const val DEFAULT_DRIFT_THRESHOLD_PX = 10f
    }
}