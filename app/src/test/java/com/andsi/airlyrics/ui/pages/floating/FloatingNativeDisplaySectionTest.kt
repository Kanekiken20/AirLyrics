package com.andsi.airlyrics.ui.pages.floating

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.settings.store.FloatingOverlayStore
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.pages.floating.sections.addNativeDisplaySection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingNativeDisplaySectionTest {
    @Before
    @After
    fun clearPreferences() {
        val context = RuntimeEnvironment.getApplication()
        listOf("lyrics_display", "floating_overlay_geometry").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun nativeControlsPersistThroughTheRealHostAndDoNotOfferUnverifiedAodMode() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val host = activity.graph.uiHost
            val list = LinearLayout(activity)
            FloatingPageScope(host).addNativeDisplaySection(list)
            val controls = list.getChildAt(2)
            assertEquals(View.GONE, controls.visibility)
            list.getChildAt(1).performClick()
            assertEquals(View.VISIBLE, controls.visibility)
            fun switch(id: Int) = descendants(list).filterIsInstance<SwitchCompat>()
                .single { it.text.toString() == activity.getString(id) }
            val optIn = switch(R.string.ui_lock_lyrics_enable)
            assertFalse(optIn.isChecked)
            assertEquals(LockScreenLyricPrivacy.PRIVATE, LyricsDisplaySettingsStore.get(activity).privacy)
            optIn.isChecked = true
            assertTrue(LyricsDisplaySettingsStore.get(activity).enabled)
            switch(R.string.ui_lock_lyrics_metadata).isChecked = false
            switch(R.string.ui_lock_lyrics_compact).isChecked = false
            switch(R.string.ui_minimal_motion).isChecked = true
            val state = LyricsDisplaySettingsStore.get(activity)
            assertFalse(state.showMetadata)
            assertFalse(state.compact)
            assertTrue(state.minimalMotion)
            descendants(list).filterIsInstance<TextView>()
                .single { it.text.toString() == activity.getString(R.string.ui_lock_lyrics_public) }
                .performClick()
            assertEquals(LockScreenLyricPrivacy.PUBLIC, LyricsDisplaySettingsStore.get(activity).privacy)
            switch(R.string.ui_hold_to_edit).isChecked = false
            switch(R.string.ui_edge_docking).isChecked = false
            assertFalse(FloatingOverlayStore.isHoldToEditEnabled(activity))
            assertFalse(FloatingOverlayStore.isDockingEnabled(activity))
            assertTrue(descendants(list).filterIsInstance<TextView>()
                .any { it.text.toString() == activity.getString(R.string.ui_aod_unverified_hint) })
            assertFalse(descendants(list).filterIsInstance<SwitchCompat>()
                .any { it.text.toString().contains("AOD", ignoreCase = true) })
            list.getChildAt(1).performClick()
            assertEquals(View.GONE, controls.visibility)
        }
    }
}
