package com.andsi.airlyrics.floating

import android.graphics.Bitmap
import android.graphics.Color

internal data class LyricPanelColors(val background: Int, val foreground: Int)

internal object LyricPanelPalette {
    val fallback = LyricPanelColors(
        background = Color.rgb(32, 33, 39),
        foreground = Color.rgb(244, 245, 248)
    )

    /** Samples at most 64 pixels without copying or retaining the source artwork. */
    fun fromBitmap(bitmap: Bitmap?): LyricPanelColors {
        if (bitmap == null) return fallback
        return try {
            // Hardware pixels require a potentially large readback. Keep this work bounded.
            if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return fallback
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return fallback
            val columns = width.coerceAtMost(8)
            val rows = height.coerceAtMost(8)
            var red = 0
            var green = 0
            var blue = 0
            var weight = 0
            for (row in 0 until rows) {
                val y = ((2L * row + 1) * height / (2L * rows)).toInt()
                for (column in 0 until columns) {
                    val x = ((2L * column + 1) * width / (2L * columns)).toInt()
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    red += Color.red(pixel) * alpha
                    green += Color.green(pixel) * alpha
                    blue += Color.blue(pixel) * alpha
                    weight += alpha
                }
            }
            if (weight == 0) return fallback
            val hsv = FloatArray(3)
            Color.colorToHSV(Color.rgb(red / weight, green / weight, blue / weight), hsv)
            hsv[1] *= 0.45f
            // Every background channel is at most 57: near-white text exceeds 10:1 contrast.
            hsv[2] = 0.22f
            LyricPanelColors(Color.HSVToColor(hsv), fallback.foreground)
        } catch (_: IllegalStateException) {
            // The owner can recycle artwork while the grid is being sampled.
            fallback
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
