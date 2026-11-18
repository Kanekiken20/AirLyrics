package com.andsi.airlyrics.lockscreen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockScreenComponentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testNotificationSuppressorLifecycle() {
        val suppressor = LockScreenNotificationSuppressor(context)
        assertFalse(suppressor.hasSecureSettingsPermission())
        // setSuppressed without permissions should gracefully fall back without throwing
        suppressor.setSuppressed(true)
        suppressor.restoreIfNeeded()
    }

    @Test
    fun testLockScreenCardWindowUpdateAndHide() {
        val window = LockScreenLyricsCardWindow(context)
        window.update(
            currentLine = "Blinding Lights in the city",
            nextLine = "I said, ooh, I'm blinded by the lights",
            trackTitle = "Blinding Lights",
            artistName = "The Weeknd",
            shouldShow = true
        )
        window.hide()
    }
}
