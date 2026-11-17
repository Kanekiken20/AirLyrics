package com.andsi.airlyrics.floating

import android.app.KeyguardManager
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class LyricsScreenMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private var changes = 0
    private val monitor = LyricsScreenMonitor(context, Handler(Looper.getMainLooper())) { changes++ }

    private fun animationScale(scale: Float) = ReflectionHelpers.callStaticMethod<Unit>(
        ValueAnimator::class.java, "setDurationScale",
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale)
    )

    @After fun cleanup() { monitor.close(); animationScale(1f) }

    @Test fun classifiesTheActualDisplayAndKeyguardIndependently() {
        shadowOf(display).setState(Display.STATE_ON)
        shadowOf(power).setIsInteractive(true)
        shadowOf(keyguard).setKeyguardLocked(false)
        assertEquals(LyricsScreenClass.UNLOCKED, monitor.screenClass())
        shadowOf(keyguard).setKeyguardLocked(true)
        assertEquals(LyricsScreenClass.LOCKED_INTERACTIVE, monitor.screenClass())
        shadowOf(display).setState(Display.STATE_DOZE)
        assertEquals(LyricsScreenClass.DOZE, monitor.screenClass())
        shadowOf(display).setState(Display.STATE_DOZE_SUSPEND)
        assertEquals(LyricsScreenClass.DOZE_SUSPEND, monitor.screenClass())
        shadowOf(display).setState(Display.STATE_OFF)
        assertEquals(LyricsScreenClass.OFF, monitor.screenClass())
        shadowOf(display).setState(Display.STATE_ON)
        shadowOf(power).setIsInteractive(false)
        assertEquals(LyricsScreenClass.OFF, monitor.screenClass())
        shadowOf(display).setState(Display.STATE_UNKNOWN)
        assertEquals(LyricsScreenClass.UNKNOWN, monitor.screenClass())
    }

    @Test fun batterySaverAndDisabledSystemAnimationsSuppressMotion() {
        shadowOf(power).setIsPowerSaveMode(false)
        animationScale(1f)
        assertTrue(monitor.allowsMotion())
        shadowOf(power).setIsPowerSaveMode(true)
        assertFalse(monitor.allowsMotion())
        shadowOf(power).setIsPowerSaveMode(false)
        animationScale(0f)
        assertFalse(monitor.allowsMotion())
    }

    @Test fun receiverAndContentObserverStopAtClose() {
        monitor.start()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes > 0)
        val beforeClose = changes
        monitor.close()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(display).setState(Display.STATE_DOZE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(beforeClose, changes)
        monitor.close()
    }
}
