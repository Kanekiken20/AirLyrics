package com.andsi.airlyrics.floating

import org.junit.Assert.*
import org.junit.Test

class StationaryHoldGestureTest {
    @Test fun stationaryHoldIsConsumedOnceAndBoundaryUsesTouchSlop() {
        val gesture = StationaryHoldGesture(8)
        gesture.start(10f, 20f)
        assertTrue(gesture.move(18f, 12f))
        assertTrue(gesture.consume())
        assertFalse(gesture.consume())
        assertEquals(3_000L, StationaryHoldGesture.HOLD_DURATION_MS)
    }

    @Test fun excursionCancelsEvenWhenFingerReturnsToStart() {
        val gesture = StationaryHoldGesture(8)
        gesture.start(10f, 20f)
        assertFalse(gesture.move(19f, 20f))
        assertFalse(gesture.move(10f, 20f))
        assertFalse(gesture.consume())
        gesture.start(0f, 0f)
        gesture.cancel()
        assertFalse(gesture.consume())
    }
}
