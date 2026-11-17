package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.i18n.localizedFloatingFontFamilyTitle
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class BundledFloatingTypographyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serializedFamiliesKeepExistingKeysAndUnknownValuesUseSystemDefault() {
        val expected = mapOf(
            "system_default" to FloatingLyricsFontFamily.SYSTEM_DEFAULT,
            "sans_serif" to FloatingLyricsFontFamily.SANS_SERIF,
            "serif" to FloatingLyricsFontFamily.SERIF,
            "monospace" to FloatingLyricsFontFamily.MONOSPACE,
            "custom" to FloatingLyricsFontFamily.CUSTOM,
            "soft_inter" to FloatingLyricsFontFamily.SOFT_INTER,
            "bold_manrope" to FloatingLyricsFontFamily.BOLD_MANROPE
        )
        expected.forEach { (key, family) ->
            assertEquals(family, FloatingLyricsFontFamily.fromKey(key))
            assertEquals(key, family.key)
            assertTrue(context.localizedFloatingFontFamilyTitle(family).isNotBlank())
        }
        assertEquals(FloatingLyricsFontFamily.SYSTEM_DEFAULT, FloatingLyricsFontFamily.fromKey("unknown"))
        assertEquals(FloatingLyricsFontFamily.SYSTEM_DEFAULT, FloatingLyricsFontFamily.fromKey(null))
    }

    @Test
    fun bundledAssetsMatchPinnedUpstreamAndIncludeRedistributionLicenses() {
        val hashes = mapOf(
            "fonts/inter.ttf" to "29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031",
            "fonts/manrope.ttf" to "3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6"
        )
        hashes.forEach { (asset, expected) ->
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(asset, expected, actual)
        }
        listOf("Inter-OFL.txt", "Manrope-OFL.txt").forEach { name ->
            val license = context.assets.open("fonts/$name").bufferedReader().use { it.readText() }
            assertTrue(license.contains("SIL OPEN FONT LICENSE Version 1.1"))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun bundledFacesLoadAtRequestedWeightsAndReuseCachedTypefaces() {
        val families = listOf(FloatingLyricsFontFamily.SOFT_INTER, FloatingLyricsFontFamily.BOLD_MANROPE)
        families.forEach { family ->
            listOf(400, 600, 800).forEach { weight ->
                val face = FloatingLyricsFontStore.resolveTypeface(context, family, weight)
                assertEquals(weight, face.weight)
                assertNotEquals(Typeface.create(Typeface.SANS_SERIF, weight, false), face)
                assertSame(face, FloatingLyricsFontStore.resolveTypeface(context, family, weight))
                val paint = Paint().apply { typeface = face; textSize = 24f }
                assertTrue(paint.measureText("AirLyrics café") > 0f)
            }
        }
    }
}
