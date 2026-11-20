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
import android.view.View
import android.widget.RemoteViews
import com.andsi.airlyrics.R
import kotlin.math.roundToInt

/**
 * Pure-black (#000000) AMOLED AppWidget built for Samsung Good Lock LockStar AOD
 * placement below the clock, with battery-conscious adaptive throttling and subtle
 * OLED burn-in pixel shifting.
 */
class AirLyricsAodWidgetProvider : AppWidgetProvider() {
    private data class WidgetVisualKey(
        val trackTitle: String,
        val artistName: String,
        val previousLine: String,
        val currentLine: String,
        val nextLine: String,
        val isPlaying: Boolean
    )

    companion object {
        private const val MIN_INTERACTIVE_UPDATE_MS = 600L
        private const val MIN_AOD_DOZE_UPDATE_MS = 1_500L
        private const val PIXEL_SHIFT_DP = 2
        private const val BULLET = "\u2022"
        private const val MUSIC_NOTE = "\u266A"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val lock = Any()

        // Deterministic subtle shift cycle (dxDp, dyDp) for AMOLED burn-in protection on AOD.
        private val shiftOffsetsDp = arrayOf(
            0 to 0,
            PIXEL_SHIFT_DP to 0,
            PIXEL_SHIFT_DP to PIXEL_SHIFT_DP,
            0 to PIXEL_SHIFT_DP,
            -PIXEL_SHIFT_DP to 0,
            -PIXEL_SHIFT_DP to -PIXEL_SHIFT_DP,
            0 to -PIXEL_SHIFT_DP
        )

        @Volatile
        private var lastSnapshot: LockScreenLyricsSnapshot? = null

        @Volatile
        private var lastVisualKey: WidgetVisualKey? = null

        @Volatile
        private var lastUpdateElapsedMs = 0L

        @Volatile
        private var shiftIndex = 0

        @Volatile
        private var pendingRunnable: Runnable? = null

        fun hasActiveWidgets(context: Context): Boolean {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, AirLyricsAodWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)?.isNotEmpty() == true
        }

        fun updateAllWidgets(
            context: Context,
            snapshot: LockScreenLyricsSnapshot,
            isInteractive: Boolean = isDisplayInteractive(context),
            force: Boolean = false
        ) {
            val appContext = context.applicationContext
            val visualKey = snapshot.toVisualKey()

            synchronized(lock) {
                lastSnapshot = snapshot
                if (!force && visualKey == lastVisualKey) return

                val manager = AppWidgetManager.getInstance(appContext)
                val component = ComponentName(appContext, AirLyricsAodWidgetProvider::class.java)
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
                            val latest = lastSnapshot ?: return@Runnable
                            val latestKey = latest.toVisualKey()
                            if (latestKey == lastVisualKey) return@Runnable
                            val mgr = AppWidgetManager.getInstance(appContext)
                            val comp = ComponentName(appContext, AirLyricsAodWidgetProvider::class.java)
                            val activeIds = mgr.getAppWidgetIds(comp)
                            if (activeIds != null && activeIds.isNotEmpty()) {
                                pushNowLocked(
                                    appContext,
                                    mgr,
                                    activeIds,
                                    latest,
                                    latestKey,
                                    isDisplayInteractive(appContext)
                                )
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
            val idle = idleSnapshot()
            updateAllWidgets(
                context = context,
                snapshot = idle,
                isInteractive = isDisplayInteractive(context),
                force = true
            )
        }

        private fun pushNowLocked(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            snapshot: LockScreenLyricsSnapshot,
            visualKey: WidgetVisualKey,
            isInteractive: Boolean
        ) {
            lastVisualKey = visualKey
            lastUpdateElapsedMs = SystemClock.elapsedRealtime()
            val applyBurnInShift = !isInteractive && snapshot.isPlaying
            val (shiftXdp, shiftYdp) = if (applyBurnInShift) {
                shiftIndex = (shiftIndex + 1) % shiftOffsetsDp.size
                shiftOffsetsDp[shiftIndex]
            } else {
                0 to 0
            }
            val views = buildWidgetViews(context, snapshot, shiftXdp, shiftYdp)
            manager.updateAppWidget(ids, views)
        }

        private fun buildWidgetViews(
            context: Context,
            snapshot: LockScreenLyricsSnapshot,
            shiftXdp: Int,
            shiftYdp: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lockstar_aod_lyrics)
            val density = context.resources.displayMetrics.density
            val basePadH = (16f * density).roundToInt()
            val basePadV = (14f * density).roundToInt()
            val dx = (shiftXdp * density).roundToInt()
            val dy = (shiftYdp * density).roundToInt()
            views.setViewPadding(
                R.id.aod_widget_content,
                (basePadH + dx).coerceAtLeast(0),
                (basePadV + dy).coerceAtLeast(0),
                (basePadH - dx).coerceAtLeast(0),
                (basePadV - dy).coerceAtLeast(0)
            )
            val meta = listOf(snapshot.trackTitle.trim(), snapshot.artistName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" $BULLET ")
            if (meta.isNotEmpty() && snapshot.isPlaying) {
                views.setTextViewText(R.id.tv_aod_track_meta, meta)
                views.setViewVisibility(R.id.tv_aod_track_meta, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_aod_track_meta, View.GONE)
            }
            val prev = if (snapshot.isPlaying) snapshot.previousLine.trim() else ""
            if (prev.isNotEmpty()) {
                views.setTextViewText(R.id.tv_aod_lyric_prev, prev)
                views.setViewVisibility(R.id.tv_aod_lyric_prev, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_aod_lyric_prev, View.GONE)
            }
            val current = snapshot.currentLine.trim().ifEmpty {
                if (snapshot.isPlaying && meta.isNotEmpty()) "$MUSIC_NOTE $meta" else "$MUSIC_NOTE AirLyrics"
            }
            views.setTextViewText(R.id.tv_aod_lyric_current, current)

            val next = if (snapshot.isPlaying) snapshot.nextLine.trim() else ""
            if (next.isNotEmpty()) {
                views.setTextViewText(R.id.tv_aod_lyric_next, next)
                views.setViewVisibility(R.id.tv_aod_lyric_next, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_aod_lyric_next, View.GONE)
            }

            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    4103,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.aod_widget_root, pendingIntent)
            }

            return views
        }

        private fun idleSnapshot() = LockScreenLyricsSnapshot(
            trackTitle = "",
            artistName = "",
            currentLine = "",
            nextLine = "",
            isPlaying = false
        )

        private fun LockScreenLyricsSnapshot.toVisualKey() = WidgetVisualKey(
            trackTitle = trackTitle.trim(),
            artistName = artistName.trim(),
            previousLine = previousLine.trim(),
            currentLine = currentLine.trim(),
            nextLine = nextLine.trim(),
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
        appWidgetManager.updateAppWidget(appWidgetIds, buildWidgetViews(context, snapshot, 0, 0))
    }
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val snapshot = lastSnapshot ?: idleSnapshot()
        appWidgetManager.updateAppWidget(appWidgetId, buildWidgetViews(context, snapshot, 0, 0))
    }
}
