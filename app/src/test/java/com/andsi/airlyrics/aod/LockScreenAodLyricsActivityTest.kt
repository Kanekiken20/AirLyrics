package com.andsi.airlyrics.aod

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockScreenAodLyricsActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        LyricsStateHolder.updateLyrics("", "", "", "", false)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `aodMode intent configures low brightness and lock screen flags`() {
        val intent = LockScreenAodLyricsActivity.createIntent(context, aodMode = true)
        val controller = Robolectric.buildActivity(LockScreenAodLyricsActivity::class.java, intent)
        controller.create().start().resume()

        val activity = controller.get()
        assertNotNull(activity)

        val lp = activity.window.attributes
        assertEquals(0.04f, lp.screenBrightness, 0.001f)

        val rootView = activity.findViewById<android.view.View>(R.id.aodRoot)
        assertTrue(rootView.keepScreenOn)

        controller.pause().stop().destroy()
    }

    @Test
    fun `lyricsState updates textviews in real time`() {
        val intent = LockScreenAodLyricsActivity.createIntent(context, aodMode = true)
        val controller = Robolectric.buildActivity(LockScreenAodLyricsActivity::class.java, intent)
        controller.create().start().resume()

        val activity = controller.get()

        LyricsStateHolder.updateLyrics(
            trackTitle = "Blinding Lights",
            artist = "The Weeknd",
            currentLine = "I said, ooh, I'm blinded by the lights",
            nextLine = "No, I can't sleep until I feel your touch",
            isPlaying = true
        )

        val tvTrackInfo = activity.findViewById<TextView>(R.id.aodTrackInfo)
        val tvCurrentLyric = activity.findViewById<TextView>(R.id.aodCurrentLyric)
        val tvNextLyric = activity.findViewById<TextView>(R.id.aodNextLyric)

        assertEquals("Blinding Lights • The Weeknd", tvTrackInfo.text.toString())
        assertEquals("I said, ooh, I'm blinded by the lights", tvCurrentLyric.text.toString())
        assertEquals("No, I can't sleep until I feel your touch", tvNextLyric.text.toString())

        controller.pause().stop().destroy()
    }

    @Test
    fun `proximity sensor pocket detection finishes activity when near`() {
        val intent = LockScreenAodLyricsActivity.createIntent(context, aodMode = true)
        val controller = Robolectric.buildActivity(LockScreenAodLyricsActivity::class.java, intent)
        controller.create().start().resume()

        val activity = controller.get()

        val sensorManager = activity.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (sensor != null) {
            val event = ReflectionHelpers.callConstructor(SensorEvent::class.java)
            ReflectionHelpers.setField(event, "sensor", sensor)
            val values = floatArrayOf(0.0f) // Near / 0 cm distance (pocket)
            ReflectionHelpers.setField(event, "values", values)

            activity.onSensorChanged(event)
            assertTrue(activity.isFinishing)
        }

        controller.pause().stop().destroy()
    }
}
