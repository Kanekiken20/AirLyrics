package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import android.widget.ViewFlipper
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
        fun background() = root().backgroundTintList?.defaultColor
        assertEquals(android.graphics.Color.CYAN, text().currentTextColor)
        assertEquals(
            com.andsi.airlyrics.core.color.LyricPanelAppearance.frostedBackground(android.graphics.Color.BLUE),
            background()
        )
        LyricsDisplaySettingsStore.setPanelTransparent(context, true)
        assertTrue(panel.update(content(), true, album))
        assertNull(background())
        assertNull(root().background)
        LyricsDisplaySettingsStore.setPanelAlbumColors(context, true)
        LyricsDisplaySettingsStore.setPanelTransparent(context, false)
        assertTrue(panel.update(content(), true, album))
        assertEquals(album.foreground, text().currentTextColor)
        assertEquals(
            com.andsi.airlyrics.core.color.LyricPanelAppearance.frostedBackground(album.background),
            background()
        )
    }

    @Test fun diagnosticsShowStaticTextWithNoLiveLyricsAndDisableRestoresPrivacy() {
        val store = LyricsDisplaySettingsStore
        store.setPanelEnabled(context, true)
        store.setPrivacy(context, LockScreenLyricPrivacy.HIDDEN)
        store.setPanelDiagnostic(context, true)
        FixedLyricsPanelProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.ui_panel_diagnostic_lines), text().text.toString())
        assertEquals(2, text().maxLines)
        assertEquals(android.graphics.Color.WHITE, text().currentTextColor)
        assertNotNull(root().background)
        assertNull(root().backgroundTintList)
        assertTrue(panel.update(content(), true, LyricPanelPalette.fallback))
        assertFalse(panel.update(content(line = "Another lyric"), true, LyricPanelPalette.fallback))
        store.setPanelDiagnostic(context, false)
        FixedLyricsPanel.refreshPolicy(context)
        assertEquals(View.GONE, root().visibility)
        assertEquals("", text().text.toString())
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun longAodLyricsUseBothRowsBeforeShowingTheNextLyric() {
        enablePublic()
        LyricsDisplaySettingsStore.setPanelAod(context, true)
        LyricsDisplaySettingsStore.setPanelTextSize(context, 24)
        panel.update(content(line = "Home"), interactive = false, LyricPanelPalette.fallback, ambient = true)
        val fullSize = text().textSize
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 24f,
            context.resources.displayMetrics), fullSize, 0.01f)
        val lyric = "This current lyric needs more room to be read across the compact widget"
        val snapshot = content(line = lyric).copy(nextLine = "The next lyric")

        panel.update(snapshot, interactive = false, LyricPanelPalette.fallback, ambient = true)

        val next = shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.lyric_panel_next)
        assertEquals(lyric, text().text.toString())
        assertEquals(2, text().maxLines)
        assertTrue(text().textSize < fullSize)
        assertEquals(View.GONE, next.visibility)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun resizingTheSameAodWidgetMakesTheActiveLyricLarger() {
        enablePublic()
        LyricsDisplaySettingsStore.setPanelAod(context, true)
        LyricsDisplaySettingsStore.setPanelTextSize(context, 32)
        val lyric = "Across the quiet river tonight"
        panel.update(content(line = lyric), interactive = false, LyricPanelPalette.fallback, ambient = true)
        val compactSize = text().textSize

        val resized = android.os.Bundle().apply {
            putSerializable(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(android.util.SizeF(320f, 110f)))
        }
        manager.updateAppWidgetOptions(widgetId, resized)
        FixedLyricsPanelProvider().onAppWidgetOptionsChanged(context, manager, widgetId, resized)

        assertEquals(lyric, text().text.toString())
        assertEquals(2, text().maxLines)
        assertTrue(text().textSize > compactSize)
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 32f,
            context.resources.displayMetrics), text().textSize, 0.01f)
    }

    @Test fun samsungKeyguardUsesFrostedSquircleBackgroundWhenAwake() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        enablePublic()
        manager.updateAppWidgetOptions(widgetId, android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 8192)
        })
        panel.update(content(), interactive = true, LyricPanelPalette.fallback)

        assertEquals("Current lyric", text().text.toString())
        assertNotNull(root().background)
        assertEquals(
            com.andsi.airlyrics.core.color.LyricPanelAppearance.frostedBackground(LyricPanelPalette.fallback.background),
            root().backgroundTintList?.defaultColor
        )
    }

    @Test fun onlyAwakeLyricChangesAdvanceTheBubbleTransition() {
        enablePublic()
        LyricsDisplaySettingsStore.setPanelAod(context, true)
        fun bubble() = root().findViewById<ViewFlipper>(R.id.lyric_panel_transition)
        panel.update(content(line = "First lyric"), true, LyricPanelPalette.fallback)
        val first = bubble().displayedChild
        assertNotNull(bubble().inAnimation)
        panel.update(content(line = "Next lyric"), true, LyricPanelPalette.fallback)
        assertNotEquals(first, bubble().displayedChild)
        assertEquals("Next lyric", (bubble().currentView as TextView).text.toString())

        LyricsDisplaySettingsStore.setMinimalMotion(context, true)
        panel.update(content(line = "Reduced motion"), true, LyricPanelPalette.fallback)
        assertNull(bubble())
        assertEquals("Reduced motion", text().text.toString())
        LyricsDisplaySettingsStore.setMinimalMotion(context, false)
        panel.update(content(line = "Always On Display"), false, LyricPanelPalette.fallback, ambient = true)
        assertNull(bubble())
        assertEquals("Always On Display", text().text.toString())
    }

    @Test fun manualBackgroundImprovesLowContrastTextAndUpdatesTypographyWithoutChangingLyrics() {
        enablePublic()
        val store = LyricsDisplaySettingsStore
        store.setPanelAlbumColors(context, false)
        store.setPanelColor(context, false, android.graphics.Color.WHITE)
        store.setPanelColor(context, true, android.graphics.Color.WHITE)
        panel.update(content(), true, LyricPanelPalette.fallback)
        assertEquals(android.graphics.Color.BLACK, text().currentTextColor)
        store.setPanelTypeface(context, com.andsi.airlyrics.core.model.LyricPanelTypeface.SPOTIFY)
        store.setPanelTextSize(context, 18)
        assertTrue(panel.update(content(), true, LyricPanelPalette.fallback))
        assertEquals("Current lyric", text().text.toString())
        val spotifySpanned = text().text as android.text.Spanned
        assertTrue(spotifySpanned.getSpans(0, text().length(), android.text.style.StyleSpan::class.java).isNotEmpty())
        assertTrue(spotifySpanned.getSpans(0, text().length(), android.text.style.TypefaceSpan::class.java).isEmpty())

        store.setPanelTypeface(context, com.andsi.airlyrics.core.model.LyricPanelTypeface.APPLE_MUSIC)
        assertTrue(panel.update(content(), true, LyricPanelPalette.fallback))
        assertEquals("Current lyric", text().text.toString())
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
