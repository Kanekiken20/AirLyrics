package com.andsi.airlyrics.aod

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lockscreen.AirLyricsAodWidgetProvider
import com.andsi.airlyrics.lockscreen.LockScreenLyricsSnapshot
import com.andsi.airlyrics.lockscreen.LockScreenNotificationCardPublisher
import com.andsi.airlyrics.settings.store.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class LockScreenAodLyricsActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun standaloneAodScreen_isDisabledByDefault_andFinishesSafelyIfLaunched() {
        assertFalse(AppSettingsStore.isStandaloneAodScreenEnabled(context))
        assertTrue(AppSettingsStore.isNowBarLiveUpdatesEnabled(context))

        val controller = Robolectric.buildActivity(
            LockScreenAodLyricsActivity::class.java,
            LockScreenAodLyricsActivity.createIntent(context, aodMode = true)
        ).create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun standaloneAodScreen_rendersClockDateAndThreeLineLyricsWhenOptedIn() {
        AppSettingsStore.setStandaloneAodScreenEnabled(context, true)
        LyricsStateHolder.updateLyrics(
            trackTitle = "Exchange",
            artist = "Bryson Tiller",
            previousLine = "Previous line here",
            currentLine = "Current bold lyric line",
            nextLine = "Upcoming next line",
            isPlaying = true
        )

        val controller = Robolectric.buildActivity(
            LockScreenAodLyricsActivity::class.java,
            LockScreenAodLyricsActivity.createIntent(context, aodMode = true)
        ).create().start().resume()
        val activity = controller.get()

        assertFalse(activity.isFinishing)
        assertNotNull(activity.findViewById<TextClock>(R.id.aodClock))
        assertNotNull(activity.findViewById<TextClock>(R.id.aodDate))
        assertEquals("Previous line here", activity.findViewById<TextView>(R.id.aodPreviousLyric).text.toString())
        assertEquals("Current bold lyric line", activity.findViewById<TextView>(R.id.aodCurrentLyric).text.toString())
        assertEquals("Upcoming next line", activity.findViewById<TextView>(R.id.aodNextLyric).text.toString())
    }

    @Test
    fun nowBarLiveUpdateBuilder_formatsPromotedChipAndNotificationWithoutMediaSession() {
        val publisher = LockScreenNotificationCardPublisher(context)
        val snapshot = LockScreenLyricsSnapshot(
            trackTitle = "Exchange",
            artistName = "Bryson Tiller",
            previousLine = "Prev line",
            currentLine = "Giving me all of your love tonight baby",
            nextLine = "Next line",
            isPlaying = true,
            positionMs = 45_000L,
            durationMs = 180_000L
        )
        val chip = publisher.buildShortNowBarChip(snapshot.currentLine)
        assertTrue(chip.length <= 24)
        assertTrue(chip.endsWith("\u2026"))

        val notification = publisher.buildNowBarLiveUpdateNotification(snapshot)
        assertFalse(notification.extras.containsKey("android.mediaSession"))
    }

    @Test
    fun aodWidgetProvider_rendersAllThreeLinesOnBothAodAndLockScreen() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            AirLyricsAodWidgetProvider::class.java,
            R.layout.widget_lockstar_aod_lyrics
        )
        val snapshot = LockScreenLyricsSnapshot(
            trackTitle = "Exchange",
            artistName = "Bryson Tiller",
            previousLine = "Waiting in a car",
            currentLine = "Waiting for a ride in the dark",
            nextLine = "The night city grows",
            isPlaying = true
        )

        AirLyricsAodWidgetProvider.updateAllWidgets(
            context = context,
            snapshot = snapshot,
            isInteractive = false,
            force = true
        )
        val aodView = shadowManager.getViewFor(widgetId) as FrameLayout
        assertEquals(View.VISIBLE, aodView.findViewById<View>(R.id.tv_aod_lyric_prev).visibility)
        assertEquals(
            "Waiting in a car",
            aodView.findViewById<TextView>(R.id.tv_aod_lyric_prev).text.toString()
        )
        assertEquals(
            "Waiting for a ride in the dark",
            aodView.findViewById<TextView>(R.id.tv_aod_lyric_current).text.toString()
        )
        assertEquals(
            "The night city grows",
            aodView.findViewById<TextView>(R.id.tv_aod_lyric_next).text.toString()
        )
    }
}
