package com.andsi.airlyrics.aod

import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Reverted: AirLyrics no longer creates a shadow MediaSession or hijacks Samsung's native
 * AOD media player area. Real music apps keep full control of native AOD track metadata,
 * while AOD lyrics render inside the dedicated LockStar AppWidget ([com.andsi.airlyrics.lockscreen.AirLyricsAodWidgetProvider]).
 */
@Deprecated("Native AOD MediaSession hijacking reverted; use AirLyricsAodWidgetProvider for LockStar AOD.")
class AodLyricsMediaPublisher(
    context: Context,
    @Suppress("UNUSED_PARAMETER")
    mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        deactivate()
    }

    fun setUpstreamController(@Suppress("UNUSED_PARAMETER") controller: MediaController?) = Unit

    fun update(
        @Suppress("UNUSED_PARAMETER") currentLine: String,
        @Suppress("UNUSED_PARAMETER") nextLine: String,
        @Suppress("UNUSED_PARAMETER") trackTitle: String,
        @Suppress("UNUSED_PARAMETER") artistName: String,
        @Suppress("UNUSED_PARAMETER") isPlaying: Boolean,
        @Suppress("UNUSED_PARAMETER") shouldPublishOnAod: Boolean,
        @Suppress("UNUSED_PARAMETER") scrollSideways: Boolean
    ) {
        deactivate()
    }

    fun deactivate() {
        runCatching { notificationManager.cancel(AOD_NOTIFICATION_ID) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { notificationManager.deleteNotificationChannel(CHANNEL_ID) }
        }
    }

    fun release() {
        deactivate()
    }

    companion object {
        const val CHANNEL_ID = "airlyrics_aod_media"
        const val AOD_NOTIFICATION_ID = 4102
    }
}
