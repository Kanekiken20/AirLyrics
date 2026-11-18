package com.andsi.airlyrics.debug

import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.widget.FixedLyricsPanelProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class LockScreenAodSimulationActivityTest {

    private lateinit var originalManufacturer: String
    private lateinit var originalModel: String

    @Before
    fun setUp() {
        originalManufacturer = Build.MANUFACTURER
        originalModel = Build.MODEL
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "SM-A256E")
        FixedLyricsPanelProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        FixedLyricsPanelProvider.resetForTesting()
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", originalModel)
    }

    @Test
    fun `harness executes full verification suite and reaches VERIFICATION_PASS`() {
        val launchIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            LockScreenAodSimulationActivity::class.java
        ).apply {
            putExtra(LockScreenAodSimulationActivity.EXTRA_AUTO_RUN, true)
        }

        val controller = Robolectric.buildActivity(
            LockScreenAodSimulationActivity::class.java,
            launchIntent
        ).setup()

        try {
            val looper = Shadows.shadowOf(Looper.getMainLooper())
            looper.idleFor(Duration.ofMillis(3500))

            val activity = controller.get()
            assertEquals("VERIFICATION_PASS", activity.statusBanner.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
