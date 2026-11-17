package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingOverlayStoreTest {
    @Test fun corruptedGeometryIsClampedAndLegacyStyleKeysAreUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
        legacy.edit().clear().putInt("pos_x", 42).commit()
        val prefs = context.getSharedPreferences("floating_overlay_geometry", Context.MODE_PRIVATE)
        prefs.edit().clear().putFloat("width_fraction", Float.NaN)
            .putFloat("height_fraction", 8f).putFloat("dock_vertical", Float.POSITIVE_INFINITY)
            .putString("dock_side", "unknown").commit()
        val geometry = FloatingOverlayStore.geometry(context)
        assertNull(geometry.widthFraction)
        assertEquals(1f, geometry.heightFraction)
        assertEquals(0.5f, geometry.dockVerticalFraction)
        assertEquals(FloatingOverlayStore.DockSide.NONE, geometry.dockSide)
        assertTrue(FloatingOverlayStore.isHoldToEditEnabled(context))
        assertTrue(FloatingOverlayStore.isDockingEnabled(context))
        FloatingOverlayStore.setDockingEnabled(context, false)
        FloatingOverlayStore.saveGeometry(context, FloatingOverlayStore.Geometry(0.4f, 0.2f, FloatingOverlayStore.DockSide.RIGHT, 0.7f))
        assertEquals(FloatingOverlayStore.DockSide.RIGHT, FloatingOverlayStore.geometry(context).dockSide)
        FloatingOverlayStore.resetGeometry(context)
        assertFalse(FloatingOverlayStore.isDockingEnabled(context))
        assertEquals(FloatingOverlayStore.Geometry(), FloatingOverlayStore.geometry(context))
        assertEquals(42, legacy.getInt("pos_x", 0))
    }
}
