package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.MediaArtwork
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class LyricPanelArtworkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val media = CurrentMediaInfo("player", "Song", "Artist", "", 100_000, true, 1_000)
    private fun artwork(color: Int): MediaArtwork.BitmapImage =
        MediaArtwork.BitmapImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    @Test fun lateArtworkAndSameTrackRevisionsUpdateWithoutReadingOnLyricTicks() {
        val worker = Executors.newSingleThreadExecutor()
        var current: MediaArtwork? = null
        var reads = 0
        var changes = 0
        val loader = LyricPanelArtwork(context, Handler(Looper.getMainLooper()), { changes++ },
            readArtwork = { reads++; current }, executorFactory = { worker })
        fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS); ShadowLooper.idleMainLooper() }
        try {
            loader.update(media)
            drain()
            assertEquals(LyricPanelPalette.fallback, loader.colors)
            repeat(100) { loader.update(media) }
            drain()
            assertEquals(1, reads)
            current = artwork(Color.RED)
            loader.update(media, refreshMetadata = true)
            drain()
            val red = loader.colors
            assertNotEquals(LyricPanelPalette.fallback, red)
            assertEquals(1, changes)
            loader.update(media, refreshMetadata = true)
            drain()
            assertEquals(1, changes)
            current = artwork(Color.BLUE)
            loader.update(media, refreshMetadata = true)
            drain()
            assertNotEquals(red, loader.colors)
            assertEquals(2, changes)
        } finally { loader.close() }
    }

    @Test fun trackSwitchAndTeardownRejectQueuedOldArtworkCallbacks() {
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader = LyricPanelArtwork(context, Handler(Looper.getMainLooper()), {},
            readArtwork = { value ->
                if (value.title == "Song") {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    artwork(Color.RED)
                } else artwork(Color.BLUE)
            }, executorFactory = { worker })
        try {
            loader.update(media)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            loader.update(media.copy(title = "Replacement"))
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals(LyricPanelPalette.fromBitmap(artwork(Color.BLUE).bitmap), loader.colors)
            loader.close()
            ShadowLooper.idleMainLooper()
            assertEquals(LyricPanelPalette.fallback, loader.colors)
        } finally { release.countDown(); loader.close() }
    }

    @Test fun metadataArrivingDuringAnInFlightReadIsCoalescedAndRechecked() {
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val loader = LyricPanelArtwork(context, Handler(Looper.getMainLooper()), {},
            readArtwork = {
                reads++
                if (reads == 1) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    null
                } else artwork(Color.BLUE)
            }, executorFactory = { worker })
        try {
            loader.update(media)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(10) { loader.update(media, refreshMetadata = true) }
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals(2, reads)
            assertNotEquals(LyricPanelPalette.fallback, loader.colors)
        } finally { release.countDown(); loader.close() }
    }
}
