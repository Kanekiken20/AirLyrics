package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore

/** One passive two-line RemoteViews surface. No input actions, frame clock, alarms or media session. */
internal class FixedLyricsPanel(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private var ids = intArrayOf()
    private var lastPosted: Presentation? = null
    private var newestGeneration = Long.MIN_VALUE

    val hasInstances: Boolean get() = ids.isNotEmpty()

    fun refreshInstances() {
        val next = manager.getAppWidgetIds(ComponentName(context, FixedLyricsPanelProvider::class.java))
        if (!ids.contentEquals(next)) lastPosted = null
        ids = next
    }

    fun update(content: LockScreenLyricContent?, interactive: Boolean, colors: LyricPanelColors): Boolean {
        if (content != null && content.generation < newestGeneration) return false
        if (content != null) newestGeneration = content.generation
        cached = Content(content, interactive, colors)
        val presentation = presentation(context)
        if (ids.isEmpty() || presentation == lastPosted) return false
        return runCatching {
            manager.updateAppWidget(ids, views(context, presentation))
            lastPosted = presentation
            true
        }.getOrDefault(false)
    }

    fun clear() {
        update(null, interactive = true, colors = LyricPanelPalette.fallback)
    }

    private data class Content(
        val lyric: LockScreenLyricContent?, val interactive: Boolean, val colors: LyricPanelColors
    )
    private data class Presentation(
        val text: String, val colors: LyricPanelColors, val visible: Boolean, val attribution: String = ""
    )

    companion object {
        // Memory only: no lyrics or artwork persisted for widget restoration after process death.
        private var cached = Content(null, true, LyricPanelPalette.fallback)

        internal fun renderCached(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            runCatching { manager.updateAppWidget(ids, views(context, presentation(context))) }
        }

        internal fun refreshPolicy(context: Context) {
            // Settings must redact the host's cached RemoteViews even when no service runs.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FixedLyricsPanelProvider::class.java))
            renderCached(context, manager, ids)
        }

        private fun presentation(context: Context): Presentation {
            val settings = LyricsDisplaySettingsStore.get(context)
            val fallback = LyricPanelPalette.fallback
            if (!settings.panelEnabled || settings.privacy == LockScreenLyricPrivacy.HIDDEN) {
                return Presentation("", fallback, visible = false)
            }
            // A host can cache and reuse this view on keyguard. Only explicit PUBLIC may ever
            // receive lyric text or song-derived colors, even when updated while unlocked.
            if (settings.privacy != LockScreenLyricPrivacy.PUBLIC) {
                return Presentation(context.getString(R.string.ui_lock_lyrics_redacted), fallback, true)
            }
            val interactive = cached.interactive && context.getSystemService(PowerManager::class.java).isInteractive
            if (!interactive) return Presentation(context.getString(R.string.ui_panel_ambient), fallback, true)
            val line = cached.lyric?.line?.takeIf(String::isNotBlank)
            val attribution = cached.lyric?.attribution?.takeIf { line != null }.orEmpty()
            val body = if (attribution.isEmpty()) line else line?.removeSuffix("\n$attribution")
            return Presentation(body?.take(2048) ?: context.getString(R.string.ui_panel_idle),
                if (line == null) fallback else cached.colors, true, attribution)
        }

        private fun views(context: Context, presentation: Presentation) =
            RemoteViews(context.packageName, R.layout.fixed_lyrics_panel).apply {
                setViewVisibility(R.id.lyric_panel_content, if (presentation.visible) View.VISIBLE else View.GONE)
                setTextViewText(R.id.lyric_panel_text, presentation.text)
                setInt(R.id.lyric_panel_text, "setMaxLines", if (presentation.attribution.isEmpty()) 2 else 1)
                setTextViewText(R.id.lyric_panel_attribution, presentation.attribution)
                setViewVisibility(R.id.lyric_panel_attribution,
                    if (presentation.attribution.isEmpty()) View.GONE else View.VISIBLE)
                setContentDescription(R.id.lyric_panel_text, presentation.text)
                setTextColor(R.id.lyric_panel_text, presentation.colors.foreground)
                setTextColor(R.id.lyric_panel_attribution, presentation.colors.foreground)
                setInt(R.id.lyric_panel_content, "setBackgroundColor", presentation.colors.background)
            }
    }
}
