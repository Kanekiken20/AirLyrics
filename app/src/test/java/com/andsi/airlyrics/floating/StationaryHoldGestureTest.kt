package com.andsi.airlyrics.floating

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationaryHoldGestureTest {

    @Test
    fun `stationary hold triggers strictly after 3000ms when drift is below 10px`() {
        var triggeredCount = 0
        val gesture = StationaryHoldGesture(holdDurationMs = { 3000L }) {
            triggeredCount++
        }
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val t0 = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 200f, 300f, 0)
        val slightJitter = MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_MOVE, 206f, 307f, 0)
        gesture.onTouchDown(down, enabled = true)
        assertFalse(gesture.onTouchMove(slightJitter))

        looper.idleFor(Duration.ofMillis(2900))
        assertEquals(0, triggeredCount)
        assertFalse(gesture.wasTriggered())

        looper.idleFor(Duration.ofMillis(150))
        assertEquals(1, triggeredCount)
        assertTrue(gesture.wasTriggered())

        down.recycle()
        slightJitter.recycle()
    }

    @Test
    fun `stationary hold cancels when pointer drifts 10px or more`() {
        var triggeredCount = 0
        val gesture = StationaryHoldGesture(holdDurationMs = { 3000L }) {
            triggeredCount++
        }
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val t0 = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 200f, 300f, 0)
        val drag = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_MOVE, 211f, 300f, 0)

        gesture.onTouchDown(down, enabled = true)
        assertTrue(gesture.onTouchMove(drag))

        looper.idleFor(Duration.ofMillis(4000))
        assertEquals(0, triggeredCount)
        assertFalse(gesture.wasTriggered())

        down.recycle()
        drag.recycle()
    }
}
