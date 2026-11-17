package com.andsi.airlyrics.floating

import kotlin.math.abs

/** State only; the view owns the single delayed callback and cancels it on every invalidation. */
internal class StationaryHoldGesture(private val slop: Int) {
    private var startX = 0f
    private var startY = 0f
    var isPending = false
        private set

    fun start(x: Float, y: Float) {
        startX = x
        startY = y
        isPending = true
    }

    fun move(x: Float, y: Float): Boolean {
        if (abs(x - startX) > slop || abs(y - startY) > slop) cancel()
        return isPending
    }

    fun consume(): Boolean = isPending.also { cancel() }
    fun cancel() { isPending = false }

    companion object { const val HOLD_DURATION_MS = 5_000L }
}
