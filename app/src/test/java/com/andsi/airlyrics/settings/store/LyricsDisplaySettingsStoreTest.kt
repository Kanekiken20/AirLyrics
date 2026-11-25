package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsDisplaySettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("lyrics_display", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsArePrivateAndOptInWithoutChangingExistingStyle() {
        assertEquals(LyricsDisplaySettings(), LyricsDisplaySettingsStore.get(context))
        assertFalse(LyricsDisplaySettingsStore.isMinimalMotion(context))
    }

    @Test
    fun preferencesRoundTripIndependently() {
        LyricsDisplaySettingsStore.setEnabled(context, true)
        LyricsDisplaySettingsStore.setPrivacy(context, LockScreenLyricPrivacy.PUBLIC)
        LyricsDisplaySettingsStore.setShowMetadata(context, false)
        LyricsDisplaySettingsStore.setCompact(context, false)
        LyricsDisplaySettingsStore.setMinimalMotion(context, true)
        assertEquals(LyricsDisplaySettings(true, LockScreenLyricPrivacy.PUBLIC, false, false, true),
            LyricsDisplaySettingsStore.get(context))
        LyricsDisplaySettingsStore.setEnabled(context, false)
        assertEquals(LockScreenLyricPrivacy.PUBLIC, LyricsDisplaySettingsStore.get(context).privacy)
        assertTrue(LyricsDisplaySettingsStore.isMinimalMotion(context))
    }

    @Test
    fun unknownPrivacyKeyFailsClosedToPrivate() {
        context.getSharedPreferences("lyrics_display", Context.MODE_PRIVATE).edit()
            .putString("lock_screen_privacy", "future_or_corrupt").commit()
        assertEquals(LockScreenLyricPrivacy.PRIVATE, LyricsDisplaySettingsStore.get(context).privacy)
        assertEquals(LockScreenLyricPrivacy.PRIVATE, LockScreenLyricPrivacy.fromKey(null))
    }

    @Test fun panelColorsRoundTripAndInvisibleTextAlphaIsRejected() {
        LyricsDisplaySettingsStore.setPanelAlbumColors(context, false)
        LyricsDisplaySettingsStore.setPanelTransparent(context, false)
        LyricsDisplaySettingsStore.setPanelColor(context, true, 0x0000FF00)
        LyricsDisplaySettingsStore.setPanelColor(context, false, 0x000000FF)
        val settings = LyricsDisplaySettingsStore.get(context)
        assertFalse(settings.panelAlbumColors)
        assertFalse(settings.panelTransparent)
        assertEquals(0xFF00FF00.toInt(), settings.panelTextColor)
        assertEquals(0xFF0000FF.toInt(), settings.panelBackgroundColor)
    }

    @Test fun panelOptInIsIndependentOfNotificationOptIn() {
        LyricsDisplaySettingsStore.setPanelEnabled(context, true)
        assertTrue(LyricsDisplaySettingsStore.get(context).panelEnabled)
        assertFalse(LyricsDisplaySettingsStore.get(context).enabled)
        LyricsDisplaySettingsStore.setEnabled(context, true)
        LyricsDisplaySettingsStore.setPanelEnabled(context, false)
        assertFalse(LyricsDisplaySettingsStore.get(context).panelEnabled)
        assertTrue(LyricsDisplaySettingsStore.get(context).enabled)
    }

    @Test fun firstWidgetPlacementEnablesPanelButPreservesPrivacyAndAnExplicitOffChoice() {
        LyricsDisplaySettingsStore.enablePanelOnFirstPlacement(context)
        assertTrue(LyricsDisplaySettingsStore.get(context).panelEnabled)
        assertTrue(LyricsDisplaySettingsStore.get(context).panelAod)
        assertEquals(LockScreenLyricPrivacy.PRIVATE, LyricsDisplaySettingsStore.get(context).privacy)
        LyricsDisplaySettingsStore.setPanelEnabled(context, false)
        LyricsDisplaySettingsStore.setPanelAod(context, false)
        LyricsDisplaySettingsStore.enablePanelOnFirstPlacement(context)
        assertFalse(LyricsDisplaySettingsStore.get(context).panelEnabled)
        assertFalse(LyricsDisplaySettingsStore.get(context).panelAod)
    }

    @Test fun panelAodScreenDefaultsOffAndRoundTrips() {
        assertFalse(LyricsDisplaySettingsStore.get(context).panelAodScreen)
        LyricsDisplaySettingsStore.setPanelAodScreen(context, true)
        assertTrue(LyricsDisplaySettingsStore.get(context).panelAodScreen)
        LyricsDisplaySettingsStore.setPanelAodScreen(context, false)
        assertFalse(LyricsDisplaySettingsStore.get(context).panelAodScreen)
    }
}
