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
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import com.andsi.airlyrics.R

/**
 * Compact bubbly Now Playing Dock widget for Samsung Good Lock LockStar and Home Screen.
 * Displays track title, artist, play/pause indicator, and a live synced one-line lyric snippet.
 */
class AirLyricsDockWidgetProvider : AppWidgetProvider() {

    private data class DockVisualKey(
        val trackTitle: String,
        val artistName: String,
        val currentLine: String,
        val isPlaying: Boolean
    )

    companion object {
        private const val MIN_INTERACTIVE_UPDATE_MS = 600L
        private const val MIN_AOD_DOZE_UPDATE_MS = 1_500L
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
            val visualKey = snapshot.toDockKey()

            synchronized(lock) {
                lastSnapshot = snapshot
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
                    pushNowLocked(appContext, manager, ids, snapshot, visualKey)
                } else {
                    val delayMs = (minIntervalMs - elapsed).coerceAtLeast(100L)
                    val task = Runnable {
                        synchronized(lock) {
                            pendingRunnable = null
                            val latest = lastSnapshot ?: return@Runnable
                            val latestKey = latest.toDockKey()
                            if (latestKey == lastVisualKey) return@Runnable
                            val mgr = AppWidgetManager.getInstance(appContext)
                            val comp = ComponentName(appContext, AirLyricsDockWidgetProvider::class.java)
                            val activeIds = mgr.getAppWidgetIds(comp)
                            if (activeIds != null && activeIds.isNotEmpty()) {
                                pushNowLocked(appContext, mgr, activeIds, latest, latestKey)
                            }
                        }
                    }
                    pendingRunnable = task
                    mainHandler.postDelayed(task, delayMs)
                }
            }
        }

        fun pushLatest(context: Context, force: Boolean = true) {
            val snapshot = lastSnapshot ?: idleSnapshot()
            updateAllWidgets(
                context = context,
                snapshot = snapshot,
                isInteractive = isDisplayInteractive(context),
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
            visualKey: DockVisualKey
        ) {
            lastVisualKey = visualKey
            lastUpdateElapsedMs = SystemClock.elapsedRealtime()
            manager.updateAppWidget(ids, buildDockViews(context, snapshot))
        }

        private fun buildDockViews(
            context: Context,
            snapshot: LockScreenLyricsSnapshot
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.notification_lockscreen_lyrics_dock)

            views.setTextViewText(
                R.id.tv_dock_play_state,
                if (snapshot.isPlaying) PLAY_GLYPH else PAUSE_GLYPH
            )

            val meta = listOf(snapshot.trackTitle.trim(), snapshot.artistName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" $BULLET ")
                .ifEmpty { "AirLyrics" }
            views.setTextViewText(R.id.tv_dock_track_meta, meta)

            val snippet = snapshot.currentLine
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: if (snapshot.isPlaying) "$MUSIC_NOTE Listening for synced lyrics\u2026" else "Playback paused"
            views.setTextViewText(R.id.tv_dock_lyric_line, snippet)

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

        private fun idleSnapshot() = LockScreenLyricsSnapshot(
            trackTitle = "AirLyrics",
            artistName = "",
            currentLine = "",
            isPlaying = false
        )

        private fun LockScreenLyricsSnapshot.toDockKey() = DockVisualKey(
            trackTitle = trackTitle.trim(),
            artistName = artistName.trim(),
            currentLine = currentLine.trim(),
            isPlaying = isPlaying
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
        val snapshot = lastSnapshot ?: idleSnapshot()
        appWidgetManager.updateAppWidget(appWidgetIds, buildDockViews(context, snapshot))
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val snapshot = lastSnapshot ?: idleSnapshot()
        appWidgetManager.updateAppWidget(appWidgetId, buildDockViews(context, snapshot))
    }
}