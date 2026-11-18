package com.andsi.airlyrics.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixedLyricsPanelProviderTest {

    @Test
    fun stateFormatting_neverOutputsLiveAodUnavailable() {
        FixedLyricsPanelProvider.LyricsWidgetState.update(
            lyricLine = "You got me in limbo babe",
            trackTitle = "Limbo"
        )
        val text = FixedLyricsPanelProvider.LyricsWidgetState.formatDisplayText()
        assertEquals("You got me in limbo babe", text)
        assertFalse(text.contains("Live AOD unavailable", ignoreCase = true))

        FixedLyricsPanelProvider.LyricsWidgetState.update(
            lyricLine = "   ",
            trackTitle = "Limbo"
        )
        val fallbackText = FixedLyricsPanelProvider.LyricsWidgetState.formatDisplayText()
        assertEquals("\u266A Limbo", fallbackText)

        FixedLyricsPanelProvider.LyricsWidgetState.update(
            lyricLine = "",
            trackTitle = ""
        )
        val defaultText = FixedLyricsPanelProvider.LyricsWidgetState.formatDisplayText()
        assertEquals("Waiting for lyrics", defaultText)
    }

    @Test
    fun neverSuppressesLyricsOnSamsungModelAndPushesTrailingThrottledLine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            FixedLyricsPanelProvider::class.java,
            R.layout.widget_fixed_lyrics_panel
        )

        // Rapid burst within 800ms throttle window
        FixedLyricsPanelProvider.updateLyrics(context, "First line", "Limbo")
        FixedLyricsPanelProvider.updateLyrics(context, "You got me in limbo babe", "Limbo")

        // Advance main looper past 800ms trailing debounce
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))

        val hostView = shadowManager.getViewFor(widgetId)
        assertNotNull(hostView)

        val textView = hostView.findViewById<TextView>(R.id.lyric_panel_text)
        assertNotNull(textView)
        assertEquals("You got me in limbo babe", textView.text.toString())
        assertFalse(textView.text.toString().contains("Live AOD unavailable", ignoreCase = true))
    }

    @Test
    fun fallsBackToTrackTitleWhenLyricIsBlankAndRefreshesOnScreenOn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            FixedLyricsPanelProvider::class.java,
            R.layout.widget_fixed_lyrics_panel
        )

        FixedLyricsPanelProvider.updateLyrics(context, lyricLine = "   ", trackTitle = "After Hours")
        FixedLyricsPanelProvider().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        val hostView = shadowManager.getViewFor(widgetId)
        val textView = hostView?.findViewById<TextView>(R.id.lyric_panel_text)

        assertEquals("\u266A After Hours", textView?.text?.toString())
    }
}
