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
import kotlin.math.roundToInt

/**
 * Dual-surface Lock Screen & Samsung Good Lock LockStar AOD AppWidget:
 * - Interactive Lock Screen (isInteractive == true): multi-line lyrics with live text-size
 *   scaling, Automatic (Album Art) / Manual colors, and Spotify/Apple Music custom fonts.
 * - Always-On Display (isInteractive == false): drops bitmap layers completely and renders
 *   a pure native TextView surface with 100% opaque high-contrast white text over a
 *   transparent root so One UI's AOD compositor never strips or dims it into invisibility.
 */
class AirLyricsAodWidgetProvider : AppWidgetProvider() {
    private data class WidgetVisualKey(
        val trackTitle: String,
        val artistName: String,
        val previousLine: String,
        val currentLine: String,
        val nextLine: String,
        val isPlaying: Boolean,
        val isInteractive: Boolean,
        val fontPreset: String,
        val textScalePercent: Int,
        val primaryColor: Int
    )

    companion object {
        private const val CACHE_PREFS = "airlyrics_aod_widget_cache"
        private const val MIN_INTERACTIVE_UPDATE_MS = 500L
        private const val MIN_AOD_DOZE_UPDATE_MS = 1_400L
        private const val BURN_IN_SHIFT_INTERVAL_MS = 30_000L
        private const val PIXEL_SHIFT_DP = 2
        private const val BULLET = "\u2022"
        private const val MUSIC_NOTE = "\u266A"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val lock = Any()

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
        private var lastShiftElapsedMs = 0L

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
            val visualKey = buildVisualKey(appContext, snapshot, isInteractive)

            synchronized(lock) {
                lastSnapshot = snapshot
                persistSnapshot(appContext, snapshot)
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
                            val latest = lastSnapshot ?: loadPersistedSnapshot(appContext) ?: return@Runnable
                            val interactiveNow = isDisplayInteractive(appContext)
                            val latestKey = buildVisualKey(appContext, latest, interactiveNow)
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
                                    interactiveNow
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
            val now = SystemClock.elapsedRealtime()
            lastVisualKey = visualKey
            lastUpdateElapsedMs = now
            val (shiftXdp, shiftYdp) = if (!isInteractive && snapshot.isPlaying) {
                if (now - lastShiftElapsedMs >= BURN_IN_SHIFT_INTERVAL_MS) {
                    lastShiftElapsedMs = now
                    shiftIndex = (shiftIndex + 1) % shiftOffsetsDp.size
                }
                shiftOffsetsDp[shiftIndex]
            } else {
                0 to 0
            }

            val usesCustomFontBitmap = isInteractive &&
                visualKey.fontPreset != AppSettingsStore.FONT_PRESET_DEFAULT
            if (usesCustomFontBitmap) {
                ids.forEach { widgetId ->
                    val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
                    val views = buildWidgetViews(
                        context = context,
                        snapshot = snapshot,
                        isInteractive = true,
                        shiftXdp = 0,
                        shiftYdp = 0,
                        options = options
                    )
                    manager.updateAppWidget(widgetId, views)
                }
            } else {
                val views = buildWidgetViews(
                    context = context,
                    snapshot = snapshot,
                    isInteractive = isInteractive,
                    shiftXdp = shiftXdp,
                    shiftYdp = shiftYdp,
                    options = null
                )
                manager.updateAppWidget(ids, views)
            }
        }

        private fun buildWidgetViews(
            context: Context,
            snapshot: LockScreenLyricsSnapshot,
            isInteractive: Boolean,
            shiftXdp: Int,
            shiftYdp: Int,
            options: Bundle?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lockstar_aod_lyrics)
            views.setInt(R.id.aod_widget_root, "setBackgroundColor", Color.TRANSPARENT)

            val fontPreset = AppSettingsStore.getLyricsFontPreset(context)
            val textScale = AppSettingsStore.getWidgetTextScalePercent(context) / 100f
            val primaryColor = resolvePrimaryColor(context, snapshot, isInteractive)

            val meta = listOf(snapshot.trackTitle.trim(), snapshot.artistName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" $BULLET ")
            val prev = if (snapshot.isPlaying && isInteractive) snapshot.previousLine.trim() else ""
            val current = snapshot.currentLine.trim().ifEmpty {
                if (snapshot.isPlaying && meta.isNotEmpty()) "$MUSIC_NOTE $meta" else "$MUSIC_NOTE AirLyrics"
            }
            val next = if (snapshot.isPlaying) snapshot.nextLine.trim() else ""

            val renderedBitmap = if (isInteractive && fontPreset != AppSettingsStore.FONT_PRESET_DEFAULT) {
                val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
                    ?.takeIf { it > 0 } ?: 240
                val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
                    ?.takeIf { it > 0 } ?: 140
                WidgetLyricsBitmapRenderer.renderCard(
                    context = context,
                    meta = if (snapshot.isPlaying) meta else "",
                    previousLine = prev,
                    currentLine = current,
                    nextLine = next,
                    primaryColor = primaryColor,
                    textScale = textScale,
                    widthDp = widthDp,
                    heightDp = heightDp
                )
            } else {
                null
            }

            if (renderedBitmap != null) {
                views.setImageViewBitmap(R.id.iv_widget_styled_lyrics, renderedBitmap)
                views.setViewVisibility(R.id.iv_widget_styled_lyrics, View.VISIBLE)
                views.setViewVisibility(R.id.aod_widget_content, View.GONE)
            } else {
                views.setViewVisibility(R.id.iv_widget_styled_lyrics, View.GONE)
                views.setViewVisibility(R.id.aod_widget_content, View.VISIBLE)

                val density = context.resources.displayMetrics.density
                val basePadH = (14f * density).roundToInt()
                val basePadV = (10f * density).roundToInt()
                val dx = (shiftXdp * density).roundToInt()
                val dy = (shiftYdp * density).roundToInt()
                views.setViewPadding(
                    R.id.aod_widget_content,
                    (basePadH + dx).coerceAtLeast(0),
                    (basePadV + dy).coerceAtLeast(0),
                    (basePadH - dx).coerceAtLeast(0),
                    (basePadV - dy).coerceAtLeast(0)
                )

                // On AOD (!isInteractive), enforce 100% opaque bright tones so One UI's
                // hardware AOD dimming never reduces translucent alpha text to invisibility.
                val metaColor = if (isInteractive) {
                    ColorUtils.setAlphaComponent(primaryColor, 190)
                } else {
                    Color.rgb(208, 208, 208)
                }
                val prevColor = ColorUtils.setAlphaComponent(primaryColor, 140)
                val currentColor = if (isInteractive) primaryColor else Color.WHITE
                val nextColor = if (isInteractive) {
                    ColorUtils.setAlphaComponent(primaryColor, 205)
                } else {
                    Color.rgb(224, 224, 224)
                }

                if (meta.isNotEmpty() && snapshot.isPlaying) {
                    views.setTextViewText(R.id.tv_aod_track_meta, meta)
                    views.setTextViewTextSize(R.id.tv_aod_track_meta, TypedValue.COMPLEX_UNIT_SP, 11.5f * textScale)
                    views.setTextColor(R.id.tv_aod_track_meta, metaColor)
                    views.setViewVisibility(R.id.tv_aod_track_meta, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.tv_aod_track_meta, View.GONE)
                }

                if (prev.isNotEmpty()) {
                    views.setTextViewText(R.id.tv_aod_lyric_prev, prev)
                    views.setTextViewTextSize(R.id.tv_aod_lyric_prev, TypedValue.COMPLEX_UNIT_SP, 13f * textScale)
                    views.setTextColor(R.id.tv_aod_lyric_prev, prevColor)
                    views.setViewVisibility(R.id.tv_aod_lyric_prev, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.tv_aod_lyric_prev, View.GONE)
                }

                views.setTextViewText(R.id.tv_aod_lyric_current, current)
                views.setTextViewTextSize(R.id.tv_aod_lyric_current, TypedValue.COMPLEX_UNIT_SP, 20f * textScale)
                views.setTextColor(R.id.tv_aod_lyric_current, currentColor)

                if (next.isNotEmpty()) {
                    views.setTextViewText(R.id.tv_aod_lyric_next, next)
                    views.setTextViewTextSize(R.id.tv_aod_lyric_next, TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
                    views.setTextColor(R.id.tv_aod_lyric_next, nextColor)
                    views.setViewVisibility(R.id.tv_aod_lyric_next, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.tv_aod_lyric_next, View.GONE)
                }
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
        ): WidgetVisualKey {
            return WidgetVisualKey(
                trackTitle = snapshot.trackTitle.trim(),
                artistName = snapshot.artistName.trim(),
                previousLine = snapshot.previousLine.trim(),
                currentLine = snapshot.currentLine.trim(),
                nextLine = snapshot.nextLine.trim(),
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
                    .putString("prev", snapshot.previousLine)
                    .putString("current", snapshot.currentLine)
                    .putString("next", snapshot.nextLine)
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
                    previousLine = p.getString("prev", "").orEmpty(),
                    currentLine = current,
                    nextLine = p.getString("next", "").orEmpty(),
                    isPlaying = p.getBoolean("playing", false),
                    albumArtAccentColor = if (p.getBoolean("has_accent", false)) p.getInt("accent", Color.WHITE) else null
                )
            }.getOrNull()
        }

        private fun idleSnapshot() = LockScreenLyricsSnapshot(
            trackTitle = "",
            artistName = "",
            currentLine = "",
            nextLine = "",
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
        val interactive = isDisplayInteractive(context)
        appWidgetIds.forEach { widgetId ->
            val options = runCatching { appWidgetManager.getAppWidgetOptions(widgetId) }.getOrNull()
            appWidgetManager.updateAppWidget(
                widgetId,
                buildWidgetViews(context, snapshot, interactive, 0, 0, options)
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val snapshot = lastSnapshot ?: loadPersistedSnapshot(context) ?: idleSnapshot()
        val interactive = isDisplayInteractive(context)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildWidgetViews(context, snapshot, interactive, 0, 0, newOptions)
        )
    }
}
