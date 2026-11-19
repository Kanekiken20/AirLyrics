package com.andsi.airlyrics.lockscreen

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.andsi.airlyrics.R

class AirLyricsAodWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val MIN_WIDGET_UPDATE_MS = 450L

        @Volatile
        private var lastSnapshot: LockScreenLyricsSnapshot? = null

        @Volatile
        private var lastUpdateElapsedMs = 0L

        fun updateAllWidgets(context: Context, snapshot: LockScreenLyricsSnapshot, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && snapshot == lastSnapshot) return
            if (!force && (now - lastUpdateElapsedMs) < MIN_WIDGET_UPDATE_MS) return

            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AirLyricsAodWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids == null || ids.isEmpty()) return

            lastSnapshot = snapshot
            lastUpdateElapsedMs = now

            val views = buildWidgetViews(context, snapshot)
            manager.updateAppWidget(ids, views)
        }

        private fun buildWidgetViews(context: Context, snapshot: LockScreenLyricsSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.notification_lockscreen_lyrics_card)

            val meta = listOf(snapshot.trackTitle.trim(), snapshot.artistName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
                .ifEmpty { "AirLyrics" }

            views.setTextViewText(R.id.tv_track_meta, meta)
            views.setViewVisibility(R.id.tv_track_meta, View.VISIBLE)

            val prev = snapshot.previousLine.trim()
            views.setTextViewText(R.id.tv_lyric_prev, prev)
            views.setViewVisibility(R.id.tv_lyric_prev, if (prev.isNotEmpty()) View.VISIBLE else View.GONE)

            val current = snapshot.currentLine.trim().ifEmpty {
                if (snapshot.isPlaying) "♪ Waiting for synced lyrics…" else "Playback paused"
            }
            views.setTextViewText(R.id.tv_lyric_current, current)

            val next = snapshot.nextLine.trim()
            views.setTextViewText(R.id.tv_lyric_next, next)
            views.setViewVisibility(R.id.tv_lyric_next, if (next.isNotEmpty()) View.VISIBLE else View.GONE)

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = lastSnapshot ?: LockScreenLyricsSnapshot(
            trackTitle = "AirLyrics",
            artistName = "",
            currentLine = "Play music to see live lyrics",
            isPlaying = false
        )
        appWidgetManager.updateAppWidget(appWidgetIds, buildWidgetViews(context, snapshot))
    }
}
