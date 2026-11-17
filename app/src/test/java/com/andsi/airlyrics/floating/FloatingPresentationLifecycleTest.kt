package com.andsi.airlyrics.floating

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingPresentationLifecycleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ServiceController<FloatingLyricsService>
    private lateinit var service: FloatingLyricsService
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        listOf("floating_quick_control", "floating_lyrics_style", "lyrics_display", "floating_overlay_geometry")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        controller = Robolectric.buildService(FloatingLyricsService::class.java).create()
        service = controller.get()
        service.screenMonitor?.close()
        service.screenMonitor = null
        service.screenClass = LyricsScreenClass.UNLOCKED
        service.showLyrics()
        service.currentMedia = CurrentMediaInfo("player", "Song", "Artist", "", 100_000, false, 1_000)
        service.renderer.updatePlayback(1_000, false)
        service.renderer.parseAndShow("[00:01.00]First\n[00:04.00]Second", emptyText = "")
        LyricsDisplaySettingsStore.setEnabled(service, true)
        ShadowLooper.idleMainLooper()
    }

    @After fun teardown() { controller.destroy() }
    private fun setScreen(screen: LyricsScreenClass) {
        service.screenClass = screen
        service.refreshLyricsPresentation()
        ShadowLooper.idleMainLooper()
    }
    private fun notification() = shadowOf(manager).getNotification(LockScreenLyricsNotification.NOTIFICATION_ID)
    private fun line() = notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun optedInCardIsPostedAutomaticallyBeforeLockAndUsesExpandedText() {
        assertEquals("First", line())
        assertEquals("First", notification()?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        setScreen(LyricsScreenClass.LOCKED_INTERACTIVE)
        assertEquals("First", line())
        service.applyLyricsOffset(4_000)
        assertEquals("Second", line())
    }

    @Test fun lockUnlockAndAmbientKeepDataButStopAllLiveRenderTimers() {
        val view = service.lyricsView
        setScreen(LyricsScreenClass.LOCKED_INTERACTIVE)
        assertFalse(service.windowController.isContentVisible)
        assertSame(view, service.lyricsView)
        assertEquals("First", line())
        assertFalse(service.syncHandler.hasCallbacks(service.syncRunnable)) // paused
        setScreen(LyricsScreenClass.DOZE_SUSPEND)
        assertNull(notification())
        assertFalse(service.syncHandler.hasCallbacks(service.syncRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.currentMediaRefreshRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.mediaRestoreRunnable))
        setScreen(LyricsScreenClass.UNLOCKED)
        assertTrue(service.windowController.isContentVisible)
        assertSame(view, service.lyricsView)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertEquals("First", service.renderer.displaySnapshot().currentLine)
    }

    @Test fun pausedOffsetAndReplacementImmediatelyUpdateOrClearNotification() {
        setScreen(LyricsScreenClass.LOCKED_INTERACTIVE)
        service.applyLyricsOffset(4_000)
        assertEquals("Second", line())
        service.renderer.show("Loading replacement")
        ShadowLooper.idleMainLooper()
        assertNull(notification())
        service.renderer.parseAndShow("[00:01.00]Replacement", emptyText = "")
        ShadowLooper.idleMainLooper()
        assertEquals("Replacement", line())
        service.hideLyrics()
        assertNull(notification())
    }

    @Test fun stoppedSourceCannotKeepAnOldLineOnLockScreen() {
        setScreen(LyricsScreenClass.LOCKED_INTERACTIVE)
        service.selectedSourcePackage = "player"
        service.activeLyricsLookupRequestKey = LyricsLookupRequestKey("inflight")
        service.lastPlaybackLyricsKey = service.currentMedia.playbackLyricsKey()
        service.handleMediaSourceLost("player")
        assertNull(notification())
        assertNull(service.lastPlaybackLyricsKey)
        service.publishLockScreenLine()
        assertNull(notification())
    }

    @Test fun successfulRetryRestoresPlaybackAnchorAfterAnUnavailableResult() {
        service.renderer.clear()
        service.currentMedia = service.currentMedia.copy(positionMs = 4_000L, isPlaying = false)
        service.applyLyricsResult(Result.success(LyricsProviderResult("test", "Test",
            "[00:01.00]First\n[00:04.00]Recovered")), service.currentMedia)
        setScreen(LyricsScreenClass.LOCKED_INTERACTIVE)
        assertEquals("Recovered", line())
        assertFalse(service.syncHandler.hasCallbacks(service.syncRunnable))
    }

    @Test fun destroyingPlayingServiceCancelsPredictionAndRecoveryTimers() {
        service.selectedSourcePackage = "player"
        service.currentMedia = service.currentMedia.copy(isPlaying = true)
        service.renderer.updatePlayback(1_000, true)
        service.scheduleNextLyricsSync()
        service.scheduleSelectedCurrentMediaInfoRefresh()
        assertTrue(service.syncHandler.hasCallbacks(service.syncRunnable))
        assertTrue(service.syncHandler.hasCallbacks(service.currentMediaRefreshRunnable))
        controller.destroy()
        assertFalse(service.syncHandler.hasCallbacks(service.syncRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.currentMediaRefreshRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.presentationRefreshRunnable))
    }
}
