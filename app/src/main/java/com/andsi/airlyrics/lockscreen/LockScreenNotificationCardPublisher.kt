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
    val isPlaying: Boolean = true
)

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
 * Publishes an ungrouped, non-transport ongoing notification whose default customContentView
 * renders the multi-line rounded AMOLED square card immediately on One UI 8.5 / LockStar 10011
 * without requiring a manual expand tap.
 */
class LockScreenNotificationCardPublisher(
    private val context: Context,
    private val launchActivityClass: Class<*>? = null
) {
    companion object {
        const val CHANNEL_ID = "airlyrics_lockscreen_card_v5"
        private val LEGACY_CHANNEL_IDS = listOf(
            "airlyrics_lockscreen_card",
            "airlyrics_lockscreen_card_v4"
        )
        const val NOTIFICATION_ID = 4024
        const val STANDALONE_GROUP_KEY = "airlyrics_standalone_card_4024"
        private const val SORT_KEY_TOP = "000_airlyrics_card"

        // Set to false so One UI 8.5 displays the pure square card without standard header chrome.
        // Flip to true only if a specific LockStar theme hides undecorated RemoteViews.
        const val USE_DECORATED_STYLE = false

        private const val BULLET = "\u2022"
        private const val MUSIC_NOTE = "\u266A"

        internal val DEFAULT_SPEC = CardNotificationSpec(
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            groupKey = STANDALONE_GROUP_KEY,
            category = NotificationCompat.CATEGORY_STATUS,
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

    @Volatile
    private var lastPublishedSnapshot: LockScreenLyricsSnapshot? = null

    init {
        ensureNotificationChannel()
    }

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        LEGACY_CHANNEL_IDS.forEach { oldId ->
            if (notificationManager.getNotificationChannel(oldId) != null) {
                notificationManager.deleteNotificationChannel(oldId)
            }
        }

        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null &&
            existing.importance == NotificationManager.IMPORTANCE_HIGH &&
            existing.lockscreenVisibility == Notification.VISIBILITY_PUBLIC
        ) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lock Screen & AOD Lyrics Card",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Displays synchronized lyrics as a standalone card below the Lock Screen clock."
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

        val defaultSquareViews = buildDefaultSquareRemoteViews(snapshot)
        val expandedSquareViews = buildExpandedRemoteViews(snapshot)
        val contentIntent = buildContentPendingIntent()

        val trackMeta = formatTrackMeta(snapshot.trackTitle, snapshot.artistName)
        val currentText = snapshot.currentLine.ifBlank { "$MUSIC_NOTE Listening for lyrics\u2026" }

        val builder = NotificationCompat.Builder(context, DEFAULT_SPEC.channelId)
            .setSmallIcon(R.drawable.ic_airlyrics_note)
            .setContentTitle("AirLyrics")
            .setSubText(trackMeta)
            .setContentText(currentText)
            .setCustomContentView(defaultSquareViews)
            .setCustomBigContentView(expandedSquareViews)
            .setCategory(DEFAULT_SPEC.category)
            .setPriority(DEFAULT_SPEC.priority)
            .setVisibility(DEFAULT_SPEC.visibility)
            .setGroup(DEFAULT_SPEC.groupKey)
            .setGroupSummary(DEFAULT_SPEC.isGroupSummary)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSortKey(SORT_KEY_TOP)

        if (DEFAULT_SPEC.usesDecoratedCustomViewStyle) {
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build().apply {
            flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
    }

    fun publish(snapshot: LockScreenLyricsSnapshot, force: Boolean = false): Boolean {
        if (!hasNotificationPermission()) return false
        if (!force && snapshot == lastPublishedSnapshot) return true

        val notification = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notification)
        lastPublishedSnapshot = snapshot
        return true
    }

    fun clear() {
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
        return views
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
