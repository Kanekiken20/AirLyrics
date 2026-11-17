package com.andsi.airlyrics.media

import android.app.Application
import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaNotificationListenerServiceTest {
    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun ownLyricNotificationsDoNotRescanButPlayerNotificationBurstsStillRecoverSessions() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val manager = application.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val session = MediaSession(application, "notification-rescan")
        val controller = MediaController(application, session.sessionToken)
        val serviceController = Robolectric.buildService(MediaNotificationListenerService::class.java).create()
        val service = serviceController.get()
        try {
            shadowOf(controller).setPackageName("com.example.player")
            shadowOf(controller).setMetadata(
                MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Song").build()
            )
            shadowOf(controller).setPlaybackState(
                PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 100L, 1f).build()
            )
            shadowOf(manager).addController(controller)
            val before = mediaUpdateCount(application)

            service.onNotificationPosted(notification(application.packageName))
            service.onNotificationRemoved(notification(application.packageName))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))

            assertEquals(before, mediaUpdateCount(application))

            service.onNotificationPosted(notification("com.example.player"))
            service.onNotificationRemoved(notification("com.example.player"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))

            assertEquals(before + 1, mediaUpdateCount(application))
            assertTrue(shadowOf(controller).callbacks.isNotEmpty())
        } finally {
            serviceController.destroy()
            shadowOf(manager).clearControllers()
            session.release()
        }
    }

    private fun mediaUpdateCount(application: Application): Int =
        shadowOf(application).broadcastIntents.count { CurrentMediaBroadcast.readMediaUpdate(it) != null }

    private fun notification(packageName: String) = StatusBarNotification(
        packageName, packageName, 1, null, 0, 0, 0, Notification(), UserHandle.getUserHandleForUid(0), 0L
    )
}
