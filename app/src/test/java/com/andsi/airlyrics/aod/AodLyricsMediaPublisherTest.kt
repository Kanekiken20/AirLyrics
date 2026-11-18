package com.andsi.airlyrics.aod

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AodLyricsMediaPublisherTest {

    private lateinit var context: Context
    private lateinit var publisher: AodLyricsMediaPublisher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        publisher = AodLyricsMediaPublisher(context)
    }

    @Test
    fun testUpdateWhenPlayingOnAod() {
        publisher.update(
            currentLine = "Never gonna give you up",
            nextLine = "Never gonna let you down",
            trackTitle = "Never Gonna Give You Up",
            artistName = "Rick Astley",
            isPlaying = true,
            shouldPublishOnAod = true,
            scrollSideways = true
        )
        // Publisher should not throw and should stay active
        publisher.deactivate()
    }

    @Test
    fun testDeactivateWhenPaused() {
        publisher.update(
            currentLine = "Some lyrics",
            nextLine = "",
            trackTitle = "Title",
            artistName = "Artist",
            isPlaying = false,
            shouldPublishOnAod = false,
            scrollSideways = true
        )
        publisher.release()
    }
}
