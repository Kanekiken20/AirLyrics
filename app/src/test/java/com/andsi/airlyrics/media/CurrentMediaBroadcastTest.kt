package com.andsi.airlyrics.media

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentMediaBroadcastTest {
    @Test
    fun mediaUpdateIntent_roundTripsCurrentMediaInfo() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val media = CurrentMediaInfo(
            sourcePackage = "com.spotify.music",
            spotifyTrackId = "5l94Up12BJSjhH7Mhr2ji4",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            isPlaying = true,
            positionMs = 42_000L,
            snapshotSequence = 7L,
            playbackSpeed = 1.5f,
            sampledAtElapsedRealtimeMs = 12_345L
        )

        val decoded = CurrentMediaBroadcast.readMediaUpdate(
            CurrentMediaBroadcast.mediaUpdateIntent(context, media)
        )

        assertEquals(media, decoded)
    }

    @Test
    fun legacySnapshot_defaultsToNormalSpeedAndUnspecifiedSample() {
        val intent = Intent("com.andsi.airlyrics.MEDIA_UPDATE")
            .putExtra("sourcePackage", "com.example.player")
            .putExtra("title", "Song")
            .putExtra("position", 42_000L)

        val decoded = requireNotNull(CurrentMediaBroadcast.readMediaUpdate(intent))

        assertEquals(1f, decoded.playbackSpeed, 0f)
        assertEquals(0L, decoded.sampledAtElapsedRealtimeMs)
        assertEquals(42_000L, decoded.positionMs)
    }

    @Test
    fun invalidClockFields_areNormalizedOnReceipt() {
        for (invalidSpeed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val intent = Intent("com.andsi.airlyrics.MEDIA_UPDATE")
                .putExtra("sourcePackage", "com.example.player")
                .putExtra("title", "Song")
                .putExtra("playbackSpeed", invalidSpeed)
                .putExtra("sampledAtElapsedRealtimeMs", -1L)

            val decoded = requireNotNull(CurrentMediaBroadcast.readMediaUpdate(intent))

            assertEquals(1f, decoded.playbackSpeed, 0f)
            assertEquals(0L, decoded.sampledAtElapsedRealtimeMs)
        }
    }

    @Test
    fun mediaSourceLostIntent_roundTripsSourcePackage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = CurrentMediaBroadcast.mediaSourceLostIntent(context, "com.example.player")

        assertNotNull(intent)
        assertEquals("com.example.player", CurrentMediaBroadcast.readMediaSourceLost(intent))
    }

    @Test
    fun readMediaUpdate_ignoresWrongActionAndBlankTitle() {
        val wrongAction = Intent("com.example.UNRELATED")
        val blankTitle = CurrentMediaBroadcast.mediaUpdateIntent(
            ApplicationProvider.getApplicationContext(),
            CurrentMediaInfo(
                sourcePackage = "com.example.player",
                title = "",
                artist = "Artist",
                album = "Album",
                durationMs = 1L,
                isPlaying = false,
                positionMs = 0L
            )
        )

        assertNull(CurrentMediaBroadcast.readMediaUpdate(wrongAction))
        assertNull(CurrentMediaBroadcast.readMediaUpdate(blankTitle))
    }
}
