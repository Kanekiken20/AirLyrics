package com.andsi.airlyrics.aod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andsi.airlyrics.R

/**
 * Publishes live lyrics directly into Samsung One UI 6's native AOD Music widget
 * via an active MediaSession when the screen is off/dozing.
 *
 * - Never requests audio focus so the underlying music player keeps playing.
 * - Forwards transport controls (play/pause/skip) to the active external MediaController.
 * - Animates long lyric lines sideways (horizontal marquee window) for OLED burn-in safety.
 */
class AodLyricsMediaPublisher(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var mediaSession: MediaSession? = null

    @Volatile
    private var upstreamController: MediaController? = null

    private var currentLine: String = ""
    private var nextLine: String = ""
    private var trackTitle: String = ""
    private var artistName: String = ""
    private var isPlaying: Boolean = false

    private var isAodActive = false
    private var horizontalScrollEnabled = true
    private var scrollOffset = 0
    private var marqueeRunning = false

    private val marqueeRunnable = object : Runnable {
        override fun run() {
            if (!isAodActive || !horizontalScrollEnabled) {
                marqueeRunning = false
                return
            }
            val rawLine = effectiveLyricText()
            if (rawLine.length <= MARQUEE_WINDOW_CHARS) {
                marqueeRunning = false
                return
            }
            scrollOffset = (scrollOffset + MARQUEE_STEP_CHARS) % (rawLine.length + MARQUEE_GAP.length)
            publishMetadataSlice()
            mainHandler.postDelayed(this, MARQUEE_TICK_MS)
        }
    }

    init {
        ensureNotificationChannel()
    }

    fun setUpstreamController(controller: MediaController?) {
        upstreamController = controller
    }

    fun update(
        currentLine: String,
        nextLine: String,
        trackTitle: String,
        artistName: String,
        isPlaying: Boolean,
        shouldPublishOnAod: Boolean,
        scrollSideways: Boolean
    ) {
        val lineChanged = this.currentLine != currentLine
        this.currentLine = currentLine
        this.nextLine = nextLine
        this.trackTitle = trackTitle
        this.artistName = artistName
        this.isPlaying = isPlaying
        this.horizontalScrollEnabled = scrollSideways

        val hasLyrics = currentLine.isNotBlank() || trackTitle.isNotBlank()
        if (!shouldPublishOnAod || !isPlaying || !hasLyrics) {
            deactivate()
            return
        }

        ensureSession()
        if (lineChanged) {
            scrollOffset = 0
        }

        isAodActive = true
        publishMetadataSlice()
        restartMarqueeIfNeeded()
    }

    fun deactivate() {
        stopMarquee()
        if (!isAodActive && mediaSession == null) return
        isAodActive = false

        mediaSession?.apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                    .build()
            )
            isActive = false
        }
        notificationManager.cancel(AOD_NOTIFICATION_ID)
    }

    fun release() {
        deactivate()
        mediaSession?.release()
        mediaSession = null
    }

    private fun ensureSession() {
        if (mediaSession != null) return

        mediaSession = MediaSession(context, "AirLyricsAodSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    upstreamController?.transportControls?.play()
                }

                override fun onPause() {
                    upstreamController?.transportControls?.pause()
                }

                override fun onSkipToNext() {
                    upstreamController?.transportControls?.skipToNext()
                }

                override fun onSkipToPrevious() {
                    upstreamController?.transportControls?.skipToPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    upstreamController?.transportControls?.seekTo(pos)
                }
            })
        }
    }

    private fun restartMarqueeIfNeeded() {
        stopMarquee()
        val rawLine = effectiveLyricText()
        if (!isAodActive || !horizontalScrollEnabled || rawLine.length <= MARQUEE_WINDOW_CHARS) {
            return
        }

        marqueeRunning = true
        // Brief pause at start of line before scrolling sideways
        mainHandler.postDelayed(marqueeRunnable, 1400L)
    }

    private fun stopMarquee() {
        mainHandler.removeCallbacks(marqueeRunnable)
        marqueeRunning = false
    }

    private fun publishMetadataSlice() {
        val session = mediaSession ?: return
        val lyricText = effectiveLyricText()

        val displayLine = if (horizontalScrollEnabled && lyricText.length > MARQUEE_WINDOW_CHARS) {
            val looped = lyricText + MARQUEE_GAP + lyricText
            val start = scrollOffset.coerceIn(0, (lyricText.length + MARQUEE_GAP.length - 1).coerceAtLeast(0))
            val end = (start + MARQUEE_WINDOW_CHARS).coerceAtMost(looped.length)
            if (start < end) looped.substring(start, end) else lyricText
        } else {
            lyricText
        }

        val subtitle = buildString {
            if (nextLine.isNotBlank()) {
                append("Next: ").append(nextLine)
            } else if (trackTitle.isNotBlank()) {
                append(trackTitle)
                if (artistName.isNotBlank()) {
                    append(" • ").append(artistName)
                }
            }
        }

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, displayLine)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayLine)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .build()

        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
            .build()

        session.setMetadata(metadata)
        session.setPlaybackState(state)
        session.isActive = true

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayLine)
            .setContentText(subtitle)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val token = session.sessionToken
            builder.style = Notification.MediaStyle().setMediaSession(token)
        }

        notificationManager.notify(AOD_NOTIFICATION_ID, builder.build())
    }

    private fun effectiveLyricText(): String {
        return currentLine.ifBlank {
            listOf(trackTitle, artistName)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirLyrics AOD Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Feeds synced lyrics to Samsung Always-On Display"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "airlyrics_aod_media"
        private const val AOD_NOTIFICATION_ID = 4102

        private const val MARQUEE_WINDOW_CHARS = 24
        private const val MARQUEE_STEP_CHARS = 4
        private const val MARQUEE_TICK_MS = 1350L
        private const val MARQUEE_GAP = "   •   "
    }
}
