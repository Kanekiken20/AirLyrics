package com.andsi.airlyrics.floating

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyricPanelPaletteTest {
    @Test
    fun differentArtworkTintsTheBackgroundAndSameBitmapCanBeResampled() {
        val artwork = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            artwork.eraseColor(Color.RED)
            val red = LyricPanelPalette.fromBitmap(artwork)
            artwork.eraseColor(Color.BLUE)
            val blue = LyricPanelPalette.fromBitmap(artwork)

            assertNotEquals(red.background, blue.background)
            assertTrue(Color.red(red.background) > Color.blue(red.background))
            assertTrue(Color.blue(blue.background) > Color.red(blue.background))
            assertFalse(artwork.isRecycled)
            assertEquals(Color.BLUE, artwork.getPixel(0, 0))
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun brightDarkAndSaturatedArtworkAlwaysProduceOpaqueReadablePanels() {
        val artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            for (red in listOf(0, 127, 255)) {
                for (green in listOf(0, 127, 255)) {
                    for (blue in listOf(0, 127, 255)) {
                        artwork.eraseColor(Color.rgb(red, green, blue))
                        assertReadable(LyricPanelPalette.fromBitmap(artwork))
                    }
                }
            }
            assertReadable(LyricPanelPalette.fallback)
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun transparentArtworkFallsBackAndTransparentPixelsDoNotDiluteVisibleTint() {
        val artwork = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        try {
            artwork.eraseColor(Color.TRANSPARENT)
            assertEquals(LyricPanelPalette.fallback, LyricPanelPalette.fromBitmap(artwork))
            artwork.setPixel(0, 0, Color.GREEN)
            val palette = LyricPanelPalette.fromBitmap(artwork)

            assertTrue(Color.green(palette.background) > Color.red(palette.background))
            assertTrue(Color.green(palette.background) > Color.blue(palette.background))
            assertReadable(palette)
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun absentAndRecycledArtworkUseTheReadableFallback() {
        assertEquals(LyricPanelPalette.fallback, LyricPanelPalette.fromBitmap(null))
        val artwork = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        artwork.recycle()

        assertEquals(LyricPanelPalette.fallback, LyricPanelPalette.fromBitmap(artwork))
        assertReadable(LyricPanelPalette.fallback)
    }

    @Test
    fun hardwareArtworkUsesFallbackWithoutSoftwareReadback() {
        val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val hardware = artwork.copy(Bitmap.Config.HARDWARE, false)
        try {
            assertEquals(Bitmap.Config.HARDWARE, hardware.config)
            assertEquals(LyricPanelPalette.fallback, LyricPanelPalette.fromBitmap(hardware))
            assertFalse(hardware.isRecycled)
        } finally {
            hardware.recycle()
            artwork.recycle()
        }
    }

    private fun assertReadable(colors: LyricPanelColors) {
        assertEquals(255, Color.alpha(colors.background))
        assertEquals(255, Color.alpha(colors.foreground))
        assertTrue(ColorUtils.calculateLuminance(colors.background) < 0.1)
        assertTrue(ColorUtils.calculateContrast(colors.foreground, colors.background) >= 4.5)
    }
}
