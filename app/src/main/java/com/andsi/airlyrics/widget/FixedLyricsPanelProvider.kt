package com.andsi.airlyrics.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import com.andsi.airlyrics.R

class FixedLyricsPanelProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_SCREEN_ON) {
            pushLatest(context, force = true)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val displayText = LyricsWidgetState.formatDisplayText()
        pushUpdate(context.applicationContext, appWidgetManager, appWidgetIds, displayText)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        pushLatest(context, force = true)
    }

    object LyricsWidgetState {
        private val lock = Any()

        @Volatile
        private var cachedLyricLine: String? = null

        @Volatile
        private var cachedTrackTitle: String? = null

        fun update(lyricLine: String?, trackTitle: String?) {
            synchronized(lock) {
                cachedLyricLine = lyricLine?.trim()?.takeIf { it.isNotEmpty() }
                if (trackTitle != null) {
                    cachedTrackTitle = trackTitle.trim().takeIf { it.isNotEmpty() }
                }
            }
        }

        fun formatDisplayText(): String {
            synchronized(lock) {
                val lyric = cachedLyricLine
                if (!lyric.isNullOrEmpty()) {
                    return lyric
                }
                val title = cachedTrackTitle
                if (!title.isNullOrEmpty()) {
                    return "♪ $title"
                }
                return DEFAULT_PLACEHOLDER
            }
        }
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 800L
        private const val DEFAULT_PLACEHOLDER = "Waiting for music…"
        private const val MAIN_ACTIVITY_CLASS = "com.andsi.airlyrics.app.MainActivity"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val throttleLock = Any()

        @Volatile
        private var lastUpdateElapsedMs: Long = 0L

        @Volatile
        private var lastPushedText: String? = null

        @Volatile
        private var pendingRunnable: Runnable? = null

        fun updateLyrics(context: Context, lyricLine: String?, trackTitle: String? = null) {
            LyricsWidgetState.update(lyricLine, trackTitle)
            val appContext = context.applicationContext
            val displayText = LyricsWidgetState.formatDisplayText()

            synchronized(throttleLock) {
                if (displayText == lastPushedText && pendingRunnable == null) {
                    return
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastUpdateElapsedMs

                pendingRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingRunnable = null

                if (elapsed >= MIN_UPDATE_INTERVAL_MS) {
                    executePush(appContext, displayText)
                } else {
                    val delayMs = MIN_UPDATE_INTERVAL_MS - elapsed
                    val runnable = Runnable {
                        synchronized(throttleLock) {
                            pendingRunnable = null
                            val latestText = LyricsWidgetState.formatDisplayText()
                            if (latestText != lastPushedText) {
                                executePush(appContext, latestText)
                            }
                        }
                    }
                    pendingRunnable = runnable
                    mainHandler.postDelayed(runnable, delayMs)
                }
            }
        }

        fun pushLatest(context: Context, force: Boolean = true) {
            val appContext = context.applicationContext
            synchronized(throttleLock) {
                pendingRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingRunnable = null

                val latestText = LyricsWidgetState.formatDisplayText()
                if (!force && latestText == lastPushedText) {
                    return
                }
                executePush(appContext, latestText)
            }
        }

        private fun executePush(appContext: Context, displayText: String) {
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, FixedLyricsPanelProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) {
                return
            }

            lastUpdateElapsedMs = SystemClock.elapsedRealtime()
            lastPushedText = displayText
            pushUpdate(appContext, manager, ids, displayText)
        }

        private fun pushUpdate(
            appContext: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            text: String
        ) {
            val launchIntent = (appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: Intent().setClassName(appContext.packageName, MAIN_ACTIVITY_CLASS)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_fixed_lyrics_panel).apply {
                    setTextViewText(R.id.lyric_panel_text, text)
                    setContentDescription(R.id.lyric_panel_text, text)
                    setOnClickPendingIntent(android.R.id.background, pendingIntent)
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
