package com.andsi.airlyrics.lockscreen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.R

data class LockScreenLyricsSnapshot(
    val trackTitle: String,
    val artistName: String,
    val previousLine: String = "",
    val currentLine: String,
    val nextLine: String = "",
    val isPlaying: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    internal fun quantizedProgressBucket(): Int {
        if (durationMs <= 0L) return -1
        val pct = ((positionMs.coerceIn(0L, durationMs) * 100L) / durationMs).toInt()
        // Quantize to 2% increments so millisecond ticks never spam NotificationManager.
        return (pct / 2) * 2
    }

    internal fun visuallyEquivalentTo(other: LockScreenLyricsSnapshot?): Boolean {
        if (other == null) return false
        return trackTitle == other.trackTitle &&
            artistName == other.artistName &&
            previousLine == other.previousLine &&
            currentLine == other.currentLine &&
            nextLine == other.nextLine &&
            isPlaying == other.isPlaying &&
            quantizedProgressBucket() == other.quantizedProgressBucket()
    }
}

internal data class CardNotificationSpec(
    val channelId: String,
    val notificationId: Int,
    val groupKey: String,
    val category: String,
    val priority: Int,
    val visibility: Int,
    val isGroupSummary: Boolean,
    val usesDecoratedCustomViewStyle: Boolean
)

/**
 * Publishes an ongoing notification with rate-limit protection and clean VISIBILITY_PUBLIC
 * rendering the multi-line rounded AMOLED square card immediately on One UI 8.5 / LockStar.
 */
class LockScreenNotificationCardPublisher(
    private val context: Context,
    private val launchActivityClass: Class<*>? = null
) {

    companion object {
        // Bumped to v6 so Android creates a fresh channel with explicit VISIBILITY_PUBLIC
        // (modifying lockscreenVisibility on existing v5 is ignored by Android).
        const val CHANNEL_ID = "airlyrics_lockscreen_card_v6"
        const val NOTIFICATION_ID = 4024
        const val STANDALONE_GROUP_KEY = "airlyrics_standalone_card_4024"
        const val USE_DECORATED_STYLE = false

        private const val LEGACY_AOD_MEDIA_NOTIFICATION_ID = 4102
        private val LEGACY_CHANNEL_IDS = listOf(
            "airlyrics_lockscreen_card",
            "airlyrics_lockscreen_card_v4",
            "airlyrics_lockscreen_card_v5",
            "airlyrics_aod_media"
        )
        private const val SORT_KEY_TOP = "000_airlyrics_card"
        private const val MIN_UPDATE_INTERVAL_MS = 450L

        private const val BULLET = "\u2022"
        private const val MUSIC_NOTE = "\u266A"

        internal val DEFAULT_SPEC = CardNotificationSpec(
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            groupKey = STANDALONE_GROUP_KEY,
            category = NotificationCompat.CATEGORY_TRANSPORT,
            priority = NotificationCompat.PRIORITY_MAX,
            visibility = NotificationCompat.VISIBILITY_PUBLIC,
            isGroupSummary = false,
            usesDecoratedCustomViewStyle = USE_DECORATED_STYLE
        )
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationManagerCompat: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isPublished = false

    @Volatile
    private var lastPublishedSnapshot: LockScreenLyricsSnapshot? = null

    @Volatile
    private var pendingSnapshot: LockScreenLyricsSnapshot? = null

    @Volatile
    private var lastNotifyElapsedMs = 0L

    private val flushPendingRunnable = Runnable {
        val queued = pendingSnapshot ?: return@Runnable
        pendingSnapshot = null
        publishInternal(queued, force = false)
    }

    init {
        cleanupLegacyNotificationsAndChannels()
        ensureNotificationChannel()
    }

    fun cleanupLegacyNotificationsAndChannels() {
        runCatching { notificationManager.cancel(LEGACY_AOD_MEDIA_NOTIFICATION_ID) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LEGACY_CHANNEL_IDS.forEach { oldId ->
                runCatching { notificationManager.deleteNotificationChannel(oldId) }
            }
        }
    }

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lock Screen Lyrics Card",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Standalone rounded lyrics card on the Lock Screen."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return notificationManagerCompat.areNotificationsEnabled()
    }

    fun buildNotification(snapshot: LockScreenLyricsSnapshot): Notification {
        ensureNotificationChannel()

        val defaultViews = buildDefaultSquareRemoteViews(snapshot)
        val expandedViews = buildExpandedRemoteViews(snapshot)
        val contentIntent = buildContentPendingIntent()

        val trackMeta = formatTrackMeta(snapshot.trackTitle, snapshot.artistName)
        val currentText = snapshot.currentLine.ifBlank { "$MUSIC_NOTE Listening for lyrics\u2026" }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_airlyrics_note)
            .setContentTitle(trackMeta)
            .setContentText(currentText)
            .setCustomContentView(defaultViews)
            .setCustomBigContentView(expandedViews)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSortKey(SORT_KEY_TOP)
            .setGroup(STANDALONE_GROUP_KEY)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(false)
        if (DEFAULT_SPEC.usesDecoratedCustomViewStyle) {
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun publish(snapshot: LockScreenLyricsSnapshot, force: Boolean = false): Boolean {
        if (!hasNotificationPermission()) return false
        if (!force && isPublished && snapshot.visuallyEquivalentTo(lastPublishedSnapshot)) return true

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastNotifyElapsedMs
        if (!force && isPublished && elapsed < MIN_UPDATE_INTERVAL_MS) {
            // Coalesce rapid ticks so we stay <= 2.2 updates/sec (well below Android 16's 5.0/sec cap)
            pendingSnapshot = snapshot
            mainHandler.removeCallbacks(flushPendingRunnable)
            mainHandler.postDelayed(flushPendingRunnable, MIN_UPDATE_INTERVAL_MS - elapsed)
            return true
        }

        mainHandler.removeCallbacks(flushPendingRunnable)
        pendingSnapshot = null
        return publishInternal(snapshot, force)
    }

    private fun publishInternal(snapshot: LockScreenLyricsSnapshot, force: Boolean): Boolean {
        if (!hasNotificationPermission()) return false
        if (!force && isPublished && snapshot.visuallyEquivalentTo(lastPublishedSnapshot)) return true

        val notification = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notification)
        lastPublishedSnapshot = snapshot
        lastNotifyElapsedMs = SystemClock.elapsedRealtime()
        isPublished = true
        return true
    }

    fun clear() {
        mainHandler.removeCallbacks(flushPendingRunnable)
        pendingSnapshot = null
        // Strict guard: never spam NotificationManager.cancel() when already cleared
        if (!isPublished && lastPublishedSnapshot == null) return
        isPublished = false
        lastPublishedSnapshot = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    internal fun buildDefaultSquareRemoteViews(snapshot: LockScreenLyricsSnapshot): RemoteViews {
        val views = RemoteViews(
            context.packageName,
            R.layout.notification_lockscreen_lyrics_card_default
        )
        val meta = formatTrackMeta(snapshot.trackTitle, snapshot.artistName)
        views.setTextViewText(R.id.tv_default_track_meta, meta)
        views.setViewVisibility(
            R.id.tv_default_track_meta,
            if (meta.isBlank()) View.GONE else View.VISIBLE
        )

        val current = snapshot.currentLine.trim().ifEmpty {
            if (snapshot.isPlaying) "$MUSIC_NOTE Instrumental / Waiting for lyrics\u2026" else "Playback paused"
        }
        views.setTextViewText(R.id.tv_default_lyric_current, current)

        val next = snapshot.nextLine.trim()
        if (next.isNotEmpty()) {
            views.setTextViewText(R.id.tv_default_lyric_next, next)
            views.setViewVisibility(R.id.tv_default_lyric_next, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_default_lyric_next, View.GONE)
        }

        bindProgressBar(views, R.id.pb_default_card_progress, snapshot)
        return views
    }

    internal fun buildExpandedRemoteViews(snapshot: LockScreenLyricsSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_lockscreen_lyrics_card)
        val meta = formatTrackMeta(snapshot.trackTitle, snapshot.artistName)
        views.setTextViewText(R.id.tv_track_meta, meta)
        views.setViewVisibility(
            R.id.tv_track_meta,
            if (meta.isBlank()) View.GONE else View.VISIBLE
        )

        val prev = snapshot.previousLine.trim()
        if (prev.isNotEmpty()) {
            views.setTextViewText(R.id.tv_lyric_prev, prev)
            views.setViewVisibility(R.id.tv_lyric_prev, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_lyric_prev, View.GONE)
        }

        val current = snapshot.currentLine.trim().ifEmpty {
            if (snapshot.isPlaying) "$MUSIC_NOTE Instrumental / Waiting for lyrics\u2026" else "Playback paused"
        }
        views.setTextViewText(R.id.tv_lyric_current, current)

        val next = snapshot.nextLine.trim()
        if (next.isNotEmpty()) {
            views.setTextViewText(R.id.tv_lyric_next, next)
            views.setViewVisibility(R.id.tv_lyric_next, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_lyric_next, View.GONE)
        }

        bindProgressBar(views, R.id.pb_card_progress, snapshot)
        return views
    }

    private fun bindProgressBar(
        views: RemoteViews,
        progressBarId: Int,
        snapshot: LockScreenLyricsSnapshot
    ) {
        if (snapshot.durationMs > 0L) {
            val pct = ((snapshot.positionMs.coerceIn(0L, snapshot.durationMs) * 100L) / snapshot.durationMs)
                .toInt()
                .coerceIn(0, 100)
            views.setProgressBar(progressBarId, 100, pct, false)
            views.setViewVisibility(progressBarId, View.VISIBLE)
        } else {
            views.setViewVisibility(progressBarId, View.GONE)
        }
    }

    internal fun formatTrackMeta(trackTitle: String, artistName: String): String {
        val title = trackTitle.trim()
        val artist = artistName.trim()
        return when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title $BULLET $artist"
            title.isNotEmpty() -> title
            artist.isNotEmpty() -> artist
            else -> "Now Playing"
        }
    }

    private fun buildContentPendingIntent(): PendingIntent? {
        val targetClass = launchActivityClass
        val intent = if (targetClass != null) {
            Intent(context, targetClass)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
