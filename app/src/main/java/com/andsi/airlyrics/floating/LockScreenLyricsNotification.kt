package com.andsi.airlyrics.floating

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore

/** A plain-text line snapshot from the service's existing timeline. No timer or media session. */
internal data class LockScreenLyricContent(
    val generation: Long,
    val title: String,
    val artist: String,
    val line: String,
    val playing: Boolean
)

/**
 * Best-effort interactive lock-screen presentation owned by the existing foreground service.
 * Samsung/System UI controls placement, disclosure and AOD indicators. This is not an AOD surface.
 * All calls run on the service's serialized main thread.
 */
internal class LockScreenLyricsNotification(private val context: Context) {
    private data class PostedKey(
        val content: LockScreenLyricContent,
        val settings: LyricsDisplaySettings,
        val languageTags: String
    )

    private var lastPosted: PostedKey? = null
    private var cleared = false
    private var newestGeneration = Long.MIN_VALUE
    private var channelLanguageTags: String? = null

    /** Returns true only when a new notification was accepted by NotificationManager. */
    fun update(content: LockScreenLyricContent?, visible: Boolean): Boolean {
        val settings = LyricsDisplaySettingsStore.get(context)
        if (!visible || !settings.enabled || content == null || content.line.isBlank()) {
            clear()
            return false
        }
        if (content.generation < newestGeneration) return false
        newestGeneration = content.generation
        if (!hasNotificationPermission()) {
            clear()
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            clear()
            return false
        }
        val safeContent = content.copy(
            title = content.title.take(MAX_METADATA_LENGTH),
            artist = content.artist.take(MAX_METADATA_LENGTH),
            line = content.line.take(MAX_LINE_LENGTH)
        )
        val key = PostedKey(safeContent, settings, context.resources.configuration.locales.toLanguageTags())
        if (key == lastPosted) return false
        return try {
            manager.notify(NOTIFICATION_ID, build(safeContent, settings))
            lastPosted = key
            cleared = false
            true
        } catch (_: SecurityException) {
            // Permission may be revoked after checking. Keep the service and overlay usable.
            clear()
            false
        }
    }

    fun clear() {
        if (!cleared) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            cleared = true
        }
        lastPosted = null
    }

    private fun hasNotificationPermission(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(manager: NotificationManager) {
        val languageTags = context.resources.configuration.locales.toLanguageTags()
        if (channelLanguageTags == languageTags) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ui_lock_lyrics_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.ui_lock_lyrics_channel_hint)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        })
        channelLanguageTags = languageTags
    }

    internal fun build(content: LockScreenLyricContent, settings: LyricsDisplaySettings): Notification {
        val visibility = when (settings.privacy) {
            LockScreenLyricPrivacy.PRIVATE -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenLyricPrivacy.PUBLIC -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenLyricPrivacy.HIDDEN -> NotificationCompat.VISIBILITY_SECRET
        }
        val metadata = listOf(content.title, content.artist).filter(String::isNotBlank).joinToString(" · ")
        val title = metadata.takeIf { settings.showMetadata && it.isNotBlank() }
            ?: context.getString(R.string.ui_lock_lyrics_title)
        val line = if (settings.compact) content.line.replace(Regex("\\s*\\n\\s*"), " · ") else content.line
        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(line)
            .setVisibility(visibility)
            .setPublicVersion(baseBuilder()
                .setContentTitle(context.getString(R.string.ui_lock_lyrics_title))
                .setContentText(context.getString(R.string.ui_lock_lyrics_redacted))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build())
        if (!settings.compact) builder.setStyle(NotificationCompat.BigTextStyle().bigText(line))
        return builder.build()
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_air_notification)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

    private fun openAppIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return null
        return PendingIntent.getActivity(context, 2000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        internal const val NOTIFICATION_ID = 2
        internal const val CHANNEL_ID = "lock_screen_lyrics_v1"
        private const val MAX_METADATA_LENGTH = 256
        private const val MAX_LINE_LENGTH = 2048
    }
}
