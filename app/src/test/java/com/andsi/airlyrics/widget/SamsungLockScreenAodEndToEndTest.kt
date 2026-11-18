package com.andsi.airlyrics.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.floating.DisplayPowerMode
import com.andsi.airlyrics.floating.FloatingServicePresentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class SamsungLockScreenAodEndToEndTest {

    private lateinit var context: Context
    private lateinit var originalManufacturer: String
    private lateinit var originalModel: String
    private lateinit var originalDevice: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalManufacturer = Build.MANUFACTURER
        originalModel = Build.MODEL
        originalDevice = Build.DEVICE

        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "SM-A256E")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "a25x")

        FixedLyricsPanelProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        FixedLyricsPanelProvider.resetForTesting()
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", originalModel)
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", originalDevice)
    }

    @Test
    fun `remoteViews inflates cleanly in Samsung 1x1 and 2x1 bounds with high contrast`() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            FixedLyricsPanelProvider::class.java,
            R.layout.widget_fixed_lyrics_panel
        )

        FixedLyricsPanelProvider.updateLyrics(
            context = context,
            lyricLine = "You got me in limbo babe",
            trackTitle = "Limbo"
        )

        val hostView = shadowManager.getViewFor(widgetId)
        assertNotNull(hostView)

        // Verify 2x1 AOD Complication (156dp x 56dp)
        assertContainerInflation(widthDp = 156f, heightDp = 56f, expected = "You got me in limbo babe")

        // Verify 1x1 Lock Screen Squircle (70dp x 70dp)
        assertContainerInflation(widthDp = 70f, heightDp = 70f, expected = "You got me in limbo babe")
    }

    @Test
    fun `60Hz playback tick flood respects 800ms throttle and never drops final lyric`() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            FixedLyricsPanelProvider::class.java,
            R.layout.widget_fixed_lyrics_panel
        )

        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

        // Simulate 60Hz playback progress ticks (16ms steps) across a lyric line change
        for (tick in 0..20) {
            val line = if (tick < 10) "Line 1 streaming" else "Line 2 final settled"
            FixedLyricsPanelProvider.updateLyrics(
                context = context,
                lyricLine = line,
                trackTitle = "Limbo"
            )
            mainLooper.idleFor(Duration.ofMillis(16))
        }

        // Advance past 800ms throttle window and confirm final line is delivered
        mainLooper.idleFor(Duration.ofMillis(900))

        val hostView = shadowManager.getViewFor(widgetId)
        assertNotNull(hostView)
        val textView = hostView.findViewById<TextView>(R.id.lyric_panel_text)
        assertNotNull(textView)

        assertEquals("Line 2 final settled", textView.text.toString())
        assertFalse(textView.text.toString().contains("Live AOD unavailable", ignoreCase = true))
    }

    @Test
    fun `doze suspend and screen wake transitions preserve lyrics and force refresh`() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(
            FixedLyricsPanelProvider::class.java,
            R.layout.widget_fixed_lyrics_panel
        )

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val shadowPower = Shadows.shadowOf(powerManager)
        val shadowDisplay = Shadows.shadowOf(defaultDisplay)

        val modesObserved = mutableListOf<DisplayPowerMode>()
        val presentation = FloatingServicePresentation(context) { modesObserved += it }
        presentation.start()

        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

        try {
            // 1. Push lyric while screen is ON
            shadowPower.setIsInteractive(true)
            shadowDisplay.setState(Display.STATE_ON)
            assertEquals(DisplayPowerMode.INTERACTIVE_ON, presentation.resolveDisplayMode())

            FixedLyricsPanelProvider.updateLyrics(
                context = context,
                lyricLine = "Active screen lyric",
                trackTitle = "Limbo"
            )
            mainLooper.idleFor(Duration.ofMillis(900))

            // 2. Transition into Screen-Off -> DOZE -> DOZE_SUSPEND (Samsung A25 5G AOD state)
            shadowPower.setIsInteractive(false)
            shadowDisplay.setState(Display.STATE_DOZE)
            assertEquals(DisplayPowerMode.DOZE, presentation.resolveDisplayMode())

            shadowDisplay.setState(Display.STATE_DOZE_SUSPEND)
            assertEquals(DisplayPowerMode.DOZE_SUSPEND, presentation.resolveDisplayMode())

            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            mainLooper.idle()

            // Update lyric while device is in DOZE_SUSPEND
            FixedLyricsPanelProvider.updateLyrics(
                context = context,
                lyricLine = "Synced during DOZE_SUSPEND",
                trackTitle = "Limbo"
            )
            mainLooper.idleFor(Duration.ofMillis(900))

            // 3. Wake display with ACTION_SCREEN_ON and ACTION_USER_PRESENT
            shadowPower.setIsInteractive(true)
            shadowDisplay.setState(Display.STATE_ON)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            mainLooper.idle()

            val hostView = shadowManager.getViewFor(widgetId)
            assertNotNull(hostView)
            val widgetText = hostView.findViewById<TextView>(R.id.lyric_panel_text).text.toString()
            assertEquals("Synced during DOZE_SUSPEND", widgetText)
        } finally {
            presentation.stop()
        }
    }

    private fun assertContainerInflation(
        widthDp: Float,
        heightDp: Float,
        expected: String
    ) {
        val widthPx = dp(widthDp)
        val heightPx = dp(heightDp)
        val container = FrameLayout(context)

        val views = android.widget.RemoteViews(context.packageName, R.layout.widget_fixed_lyrics_panel).apply {
            setTextViewText(R.id.lyric_panel_text, expected)
            setContentDescription(R.id.lyric_panel_text, expected)
        }
        val inflated = views.apply(context, container)
        container.addView(inflated, FrameLayout.LayoutParams(widthPx, heightPx))

        container.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, widthPx, heightPx)

        val bgView = inflated.findViewById<View>(android.R.id.background)
        val textView = inflated.findViewById<TextView>(R.id.lyric_panel_text)

        assertNotNull(bgView)
        assertTrue(bgView.background is GradientDrawable)
        assertNotNull(textView)
        assertEquals(View.VISIBLE, textView.visibility)
        assertTrue("TextView width must be > 0", textView.measuredWidth > 0)
        assertTrue("TextView height must be > 0", textView.measuredHeight > 0)
        assertEquals(expected, textView.text.toString())
        assertFalse(textView.text.toString().contains("Live AOD unavailable", ignoreCase = true))
        assertEquals(Color.WHITE, textView.currentTextColor)
        assertNotEquals(Color.WHITE, Color.parseColor("#CC121212"))
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).roundToInt()
}
