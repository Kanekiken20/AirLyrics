package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixedLyricsPanelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = AppWidgetManager.getInstance(context)
    private lateinit var panel: FixedLyricsPanel
    private var widgetId = 0
    private fun text() = shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.lyric_panel_text)
    private fun root() = shadowOf(manager).getViewFor(widgetId).findViewById<View>(R.id.lyric_panel_content)
    private fun content(generation: Long = 1, line: String = "Current lyric") =
        LockScreenLyricContent(generation, "Private song title", "Private artist", line, true)

    @Before fun setup() {
        context.getSharedPreferences("lyrics_display", Context.MODE_PRIVATE).edit().clear().commit()
        LyricsDisplaySettingsStore.setPanelEnabled(context, false)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        widgetId = shadowOf(manager).createWidget(FixedLyricsPanelProvider::class.java, R.layout.fixed_lyrics_panel)
        panel = FixedLyricsPanel(context)
        panel.refreshInstances()
        panel.clear()
    }

    private fun enablePublic() {
        LyricsDisplaySettingsStore.setPanelEnabled(context, true)
        LyricsDisplaySettingsStore.setPrivacy(context, LockScreenLyricPrivacy.PUBLIC)
    }

    @Test fun optInAndPublicDisclosureAreRequiredEvenOnHomeScreen() {
        panel.update(content(), true, LyricPanelPalette.fallback)
        assertEquals(View.GONE, root().visibility)
        LyricsDisplaySettingsStore.setPanelEnabled(context, true)
        panel.update(content(), true, LyricPanelPalette.fallback)
        assertEquals(context.getString(R.string.ui_lock_lyrics_redacted), text().text.toString())
        assertFalse(text().text.contains("Private"))
        enablePublic()
        panel.update(content(), true, LyricPanelPalette.fallback)
        assertEquals("Current lyric", text().text.toString())
        LyricsDisplaySettingsStore.setPrivacy(context, LockScreenLyricPrivacy.PRIVATE)
        FixedLyricsPanelProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.ui_lock_lyrics_redacted), text().text.toString())
    }

    @Test fun twoLinePanelHasNoClickableControlsAndDeduplicatesUpdates() {
        enablePublic()
        assertTrue(panel.update(content(line = "Line one\nLine two\nAdditional content"), true, LyricPanelPalette.fallback))
        assertEquals(2, text().maxLines)
        assertFalse(text().isClickable)
        assertFalse(root().isClickable)
        assertFalse(panel.update(content(line = "Line one\nLine two\nAdditional content"), true, LyricPanelPalette.fallback))
        assertFalse(text().text.contains("Private song title"))
    }

    @Test fun staleGenerationsCannotReplaceCurrentTrackAndClearRemovesOldLyrics() {
        enablePublic()
        panel.update(content(2, "New track lyric"), true, LyricPanelPalette.fallback)
        assertFalse(panel.update(content(1, "Stale old lyric"), true, LyricPanelPalette.fallback))
        assertEquals("New track lyric", text().text.toString())
        panel.clear()
        assertEquals(context.getString(R.string.ui_panel_idle), text().text.toString())
        FixedLyricsPanelProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertFalse(text().text.contains("New track"))
    }

    @Test fun ambientTransitionAndWidgetRecreationCannotPresentOldTextAsLiveAod() {
        enablePublic()
        panel.update(content(), true, LyricPanelPalette.fallback)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        FixedLyricsPanelProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.ui_panel_ambient), text().text.toString())
        panel.update(null, false, LyricPanelPalette.fallback)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        FixedLyricsPanelProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.ui_panel_ambient), text().text.toString())
        panel.update(content(2, "Current after wake"), true, LyricPanelPalette.fallback)
        assertEquals("Current after wake", text().text.toString())
    }

    @Test fun hiddenPolicyRemovesBothContentAndPanel() {
        enablePublic()
        panel.update(content(), true, LyricPanelPalette.fallback)
        LyricsDisplaySettingsStore.setPrivacy(context, LockScreenLyricPrivacy.HIDDEN)
        panel.update(content(), true, LyricPanelPalette.fallback)
        assertEquals(View.GONE, root().visibility)
        assertEquals("", text().text.toString())
    }

    @Test fun attributedBilingualLyricsReserveTheSecondLineForRequiredCredit() {
        enablePublic()
        panel.update(content(line = "Original\nTranslation\nProvider credit").copy(attribution = "Provider credit"),
            true, LyricPanelPalette.fallback)
        val credit = shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.lyric_panel_attribution)
        assertEquals("Provider credit", credit.text.toString())
        assertEquals(View.VISIBLE, credit.visibility)
        assertEquals(1, text().maxLines)
        assertEquals("Original\nTranslation", text().text.toString())
        assertFalse(text().text.contains("Provider credit"))
        panel.update(content(2, "Unattributed"), true, LyricPanelPalette.fallback)
        assertEquals(View.GONE, credit.visibility)
        assertEquals(2, text().maxLines)
    }

    @Test fun manualAndAlbumColorsCanSwitchAndTransparencyUpdatesWithoutChangingLyrics() {
        enablePublic()
        val album = LyricPanelColors(android.graphics.Color.DKGRAY, android.graphics.Color.WHITE)
        LyricsDisplaySettingsStore.setPanelAlbumColors(context, false)
        LyricsDisplaySettingsStore.setPanelColor(context, true, android.graphics.Color.CYAN)
        LyricsDisplaySettingsStore.setPanelColor(context, false, android.graphics.Color.BLUE)
        LyricsDisplaySettingsStore.setPanelTransparent(context, false)
        panel.update(content(), true, album)
        fun background() = (root().background as android.graphics.drawable.ColorDrawable).color
        assertEquals(android.graphics.Color.CYAN, text().currentTextColor)
        assertEquals(android.graphics.Color.BLUE, background())
        LyricsDisplaySettingsStore.setPanelTransparent(context, true)
        assertTrue(panel.update(content(), true, album))
        assertEquals(android.graphics.Color.TRANSPARENT, background())
        LyricsDisplaySettingsStore.setPanelAlbumColors(context, true)
        LyricsDisplaySettingsStore.setPanelTransparent(context, false)
        assertTrue(panel.update(content(), true, album))
        assertEquals(album.foreground, text().currentTextColor)
        assertEquals(album.background, background())
    }

    @Test fun privacyAndDisableCanRedactHostCacheWithoutRunningAService() {
        enablePublic()
        panel.update(content(), true, LyricPanelPalette.fallback)
        LyricsDisplaySettingsStore.setPrivacy(context, LockScreenLyricPrivacy.PRIVATE)
        FixedLyricsPanel.refreshPolicy(context)
        assertEquals(context.getString(R.string.ui_lock_lyrics_redacted), text().text.toString())
        LyricsDisplaySettingsStore.setPanelEnabled(context, false)
        FixedLyricsPanel.refreshPolicy(context)
        assertEquals(View.GONE, root().visibility)
        assertEquals("", text().text.toString())
    }
}
