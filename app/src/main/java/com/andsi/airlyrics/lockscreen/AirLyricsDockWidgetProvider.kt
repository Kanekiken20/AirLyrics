package com.andsi.airlyrics.lockscreen

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.AppSettingsStore

/**
 * Compact bubbly Now Playing Dock widget for Samsung Good Lock LockStar and Home Screen.
 * Uses custom-font rendering and Album Art / Manual colors on the interactive Lock Screen,
 * and drops bitmaps + translucent backgrounds on AOD for pure high-contrast visibility.
 */
class AirLyricsDockWidgetProvider : AppWidgetProvider() {

    private data class DockVisualKey(
        val trackTitle: String,
        val artistName: String,
        val currentLine: String,
        val isPlaying: Boolean,
        val isInteractive: Boolean,
        val fontPreset: String,
        val textScalePercent: Int,
        val primaryColor: Int
    )

    companion object {
        private const val CACHE_PREFS = "airlyrics_dock_widget_cache"
        private const val MIN_INTERACTIVE_UPDATE_MS = 500L
        private const val MIN_AOD_DOZE_UPDATE_MS = 1_400L
        private const val BULLET = "\u2022"
        private const val PLAY_GLYPH = "\u25B6"
        private const val PAUSE_GLYPH = "\u275A\u275A"
        private const val MUSIC_NOTE = "\u266A"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val lock = Any()

        @Volatile
        private var lastSnapshot: LockScreenLyricsSnapshot? = null

        @Volatile
        private var lastVisualKey: DockVisualKey? = null

        @Volatile
        private var lastUpdateElapsedMs = 0L

        @Volatile
        private var pendingRunnable: Runnable? = null

        fun hasActiveWidgets(context: Context): Boolean {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, AirLyricsDockWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)?.isNotEmpty() == true
        }

        fun updateAllWidgets(
            context: Context,
            snapshot: LockScreenLyricsSnapshot,
            isInteractive: Boolean = isDisplayInteractive(context),
            force: Boolean = false
        ) {
            val appContext = context.applicationContext
            val visualKey = buildVisualKey(appContext, snapshot, isInteractive)

            synchronized(lock) {
                lastSnapshot = snapshot
                persistSnapshot(appContext, snapshot)
                if (!force && visualKey == lastVisualKey) return

                val manager = AppWidgetManager.getInstance(appContext)
                val component = ComponentName(appContext, AirLyricsDockWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids == null || ids.isEmpty()) return

                val now = SystemClock.elapsedRealtime()
                val minIntervalMs = if (isInteractive) MIN_INTERACTIVE_UPDATE_MS else MIN_AOD_DOZE_UPDATE_MS
                val elapsed = now - lastUpdateElapsedMs

                pendingRunnable?.let(mainHandler::removeCallbacks)
                pendingRunnable = null

                if (force || lastUpdateElapsedMs == 0L || elapsed >= minIntervalMs) {
                    pushNowLocked(appContext, manager, ids, snapshot, visualKey, isInteractive)
                } else {
                    val delayMs = (minIntervalMs - elapsed).coerceAtLeast(100L)
                    val task = Runnable {
                        synchronized(lock) {
                            pendingRunnable = null
                            val latest = lastSnapshot ?: loadPersistedSnapshot(appContext) ?: return@Runnable
                            val interactiveNow = isDisplayInteractive(appContext)
                            val latestKey = buildVisualKey(appContext, latest, interactiveNow)
                            if (latestKey == lastVisualKey) return@Runnable
                            val mgr = AppWidgetManager.getInstance(appContext)
                            val comp = ComponentName(appContext, AirLyricsDockWidgetProvider::class.java)
                            val activeIds = mgr.getAppWidgetIds(comp)
                            if (activeIds != null && activeIds.isNotEmpty()) {
                                pushNowLocked(appContext, mgr, activeIds, latest, latestKey, interactiveNow)
                            }
                        }
                    }
                    pendingRunnable = task
                    mainHandler.postDelayed(task, delayMs)
                }
            }
        }

        fun pushLatest(context: Context, force: Boolean = true) {
            val appContext = context.applicationContext
            val snapshot = lastSnapshot ?: loadPersistedSnapshot(appContext) ?: idleSnapshot()
            updateAllWidgets(
                context = appContext,
                snapshot = snapshot,
                isInteractive = isDisplayInteractive(appContext),
                force = force
            )
        }

        fun clearWidgets(context: Context) {
            updateAllWidgets(
                context = context,
                snapshot = idleSnapshot(),
                isInteractive = isDisplayInteractive(context),
                force = true
            )
        }

        private fun pushNowLocked(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            snapshot: LockScreenLyricsSnapshot,
            visualKey: DockVisualKey,
            isInteractive: Boolean
        ) {
            lastVisualKey = visualKey
            lastUpdateElapsedMs = SystemClock.elapsedRealtime()
            manager.updateAppWidget(ids, buildDockViews(context, snapshot, isInteractive))
        }

        private fun buildDockViews(
            context: Context,
            snapshot: LockScreenLyricsSnapshot,
            isInteractive: Boolean
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.notification_lockscreen_lyrics_dock)
            val fontPreset = AppSettingsStore.getLyricsFontPreset(context)
            val textScale = (AppSettingsStore.getWidgetTextScalePercent(context) / 100f).coerceIn(0.85f, 1.25f)
            val primaryColor = resolvePrimaryColor(context, snapshot, isInteractive)

            if (isInteractive) {
                views.setInt(R.id.dock_widget_surface, "setBackgroundResource", R.drawable.bg_oneui_lyrics_dock)
            } else {
                views.setInt(R.id.dock_widget_surface, "setBackgroundColor", Color.TRANSPARENT)
            }

            views.setTextViewText(
                R.id.tv_dock_play_state,
                if (snapshot.isPlaying) PLAY_GLYPH else PAUSE_GLYPH
            )
            views.setTextColor(R.id.tv_dock_play_state, if (isInteractive) primaryColor else Color.WHITE)
            views.setInt(R.id.iv_dock_note, "setColorFilter", if (isInteractive) primaryColor else Color.WHITE)

            val meta = listOf(snapshot.trackTitle.trim(), snapshot.artistName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" $BULLET ")
                .ifEmpty { "AirLyrics" }
            views.setTextViewText(R.id.tv_dock_track_meta, meta)
views.setTextColor(
R.id.tv_dock_track_meta,
if (isInteractive) ColorUtils.setAlphaComponent(primaryColor, 185) else Color.rgb(212, 212, 212)
)
views.setTextViewTextSize(R.id.tv_dock_track_meta, TypedValue.COMPLEX_UNIT_SP, 11f * textScale)
val snippet = snapshot.currentLine
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: if (snapshot.isPlaying) "$MUSIC_NOTE Listening for synced lyrics\u2026" else "Playback paused"

        val styledBitmap = if (isInteractive && fontPreset != AppSettingsStore.FONT_PRESET_DEFAULT) {
            WidgetLyricsBitmapRenderer.renderDockLine(
                context = context,
                line = snippet,
                primaryColor = primaryColor,
                textScale = textScale
            )
        } else {
            null
        }

        if (styledBitmap != null) {
            views.setImageViewBitmap(R.id.iv_dock_styled_lyric, styledBitmap)
            views.setViewVisibility(R.id.iv_dock_styled_lyric, View.VISIBLE)
            views.setViewVisibility(R.id.tv_dock_lyric_line, View.GONE)
        } else {
            views.setViewVisibility(R.id.iv_dock_styled_lyric, View.GONE)
            views.setViewVisibility(R.id.tv_dock_lyric_line, View.VISIBLE)
            views.setTextViewText(R.id.tv_dock_lyric_line, snippet)
            views.setTextColor(R.id.tv_dock_lyric_line, if (isInteractive) primaryColor else Color.WHITE)
            views.setTextViewTextSize(R.id.tv_dock_lyric_line, TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
        }

        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context,
                4104,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.dock_widget_root, pendingIntent)
        }

        return views
    }

    private fun resolvePrimaryColor(
        context: Context,
        snapshot: LockScreenLyricsSnapshot,
        isInteractive: Boolean
    ): Int {
        if (!isInteractive) return Color.WHITE
        return if (AppSettingsStore.getWidgetColorMode(context) == AppSettingsStore.WIDGET_COLOR_MODE_MANUAL) {
            AppSettingsStore.getWidgetManualColor(context)
        } else {
            snapshot.albumArtAccentColor ?: Color.WHITE
        }
    }

    private fun buildVisualKey(
        context: Context,
        snapshot: LockScreenLyricsSnapshot,
        isInteractive: Boolean
    ): DockVisualKey {
        return DockVisualKey(
            trackTitle = snapshot.trackTitle.trim(),
            artistName = snapshot.artistName.trim(),
            currentLine = snapshot.currentLine.trim(),
            isPlaying = snapshot.isPlaying,
            isInteractive = isInteractive,
            fontPreset = AppSettingsStore.getLyricsFontPreset(context),
            textScalePercent = AppSettingsStore.getWidgetTextScalePercent(context),
            primaryColor = resolvePrimaryColor(context, snapshot, isInteractive)
        )
    }

    private fun persistSnapshot(context: Context, snapshot: LockScreenLyricsSnapshot) {
        runCatching {
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", snapshot.trackTitle)
                .putString("artist", snapshot.artistName)
                .putString("current", snapshot.currentLine)
                .putBoolean("playing", snapshot.isPlaying)
                .putInt("accent", snapshot.albumArtAccentColor ?: Color.WHITE)
                .putBoolean("has_accent", snapshot.albumArtAccentColor != null)
                .apply()
        }
    }

    private fun loadPersistedSnapshot(context: Context): LockScreenLyricsSnapshot? {
        return runCatching {
            val p = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            val title = p.getString("title", null) ?: return null
            val current = p.getString("current", "").orEmpty()
            if (title.isBlank() && current.isBlank()) return null
            LockScreenLyricsSnapshot(
                trackTitle = title,
                artistName = p.getString("artist", "").orEmpty(),
                currentLine = current,
                isPlaying = p.getBoolean("playing", false),
                albumArtAccentColor = if (p.getBoolean("has_accent", false)) p.getInt("accent", Color.WHITE) else null
            )
        }.getOrNull()
    }

    private fun idleSnapshot() = LockScreenLyricsSnapshot(
        trackTitle = "AirLyrics",
        artistName = "",
        currentLine = "",
        isPlaying = false
    )

    private fun isDisplayInteractive(context: Context): Boolean {
        return context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }
}

override fun onEnabled(context: Context) {
    super.onEnabled(context)
    pushLatest(context, force = true)
}

override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val snapshot = lastSnapshot ?: loadPersistedSnapshot(context) ?: idleSnapshot()
    appWidgetManager.updateAppWidget(appWidgetIds, buildDockViews(context, snapshot, isDisplayInteractive(context)))
}

override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle
) {
    super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    val snapshot = lastSnapshot ?: loadPersistedSnapshot(context) ?: idleSnapshot()
    appWidgetManager.updateAppWidget(appWidgetId, buildDockViews(context, snapshot, isDisplayInteractive(context)))
}
}