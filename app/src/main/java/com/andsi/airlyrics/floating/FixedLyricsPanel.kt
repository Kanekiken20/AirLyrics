package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.LyricPanelAppearance
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import kotlin.math.roundToInt

/** Passive RemoteViews surface for an external widget host. */
internal class FixedLyricsPanel(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private var ids = intArrayOf()
    private var lastPosted: Presentation? = null
    private var newestGeneration = Long.MIN_VALUE

    val hasInstances: Boolean get() = ids.isNotEmpty()

    fun refreshInstances() {
        val next = widgetIds(context, manager)
        if (!ids.contentEquals(next)) {
            lastPosted = null
            if (next.isEmpty()) cached = Content(null, true, LyricPanelPalette.fallback)
        }
        ids = next
    }

    fun update(
        content: LockScreenLyricContent?,
        interactive: Boolean,
        colors: LyricPanelColors,
        ambient: Boolean = false
    ): Boolean {
        if (content != null && content.generation < newestGeneration) return false
        if (content != null) newestGeneration = content.generation
        if (ids.isEmpty()) return false
        val previous = cached.lyric
        val child = if (interactive && previous != null && content != null && previous.line != content.line &&
            !LyricsDisplaySettingsStore.isMinimalMotion(context)) {
            1 - cached.child
        } else cached.child
        cached = Content(content, interactive, colors, ambient, child)
        val frame = presentation(context)
        if (frame == lastPosted) return false
        return runCatching {
            post(context, manager, ids, frame)
            lastPosted = frame
            true
        }.getOrDefault(false)
    }

    fun clear() {
        update(null, interactive = true, colors = LyricPanelPalette.fallback)
    }

    private data class Content(
        val lyric: LockScreenLyricContent?,
        val interactive: Boolean,
        val colors: LyricPanelColors,
        val ambient: Boolean = false,
        val child: Int = 0
    )
    private data class Presentation(
        val text: String,
        val colors: LyricPanelColors,
        val visible: Boolean,
        val attribution: String = "",
        val sizeSp: Int = 14,
        val typeface: LyricPanelTypeface = LyricPanelTypeface.SYSTEM,
        val plain: Boolean = false,
        val previous: String? = null,
        val next: String? = null,
        val bodyLines: Int = if (attribution.isEmpty()) 2 else 1,
        val child: Int = 0,
        val motion: Boolean = false,
        val hasLyrics: Boolean = false,
        val compactPadding: Boolean = false
    )

    private data class WidgetSlot(val widthPx: Int, val heightPx: Int, val hostReportsHeight: Boolean)

    companion object {
        // Memory only: no lyrics or artwork persisted for widget restoration after process death.
        private var cached = Content(null, true, LyricPanelPalette.fallback)

        internal fun previewAlbumColors(): Pair<Int, Int> = cached.colors.let { it.background to it.foreground }

        private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, FixedLyricsPanelProvider::class.java))

        internal fun renderCached(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            runCatching {
                post(context, manager, ids, presentation(context))
            }
        }

        internal fun refreshPolicy(context: Context) {
            // Settings must redact the host's cached RemoteViews even when no service runs.
            val manager = AppWidgetManager.getInstance(context)
            renderCached(context, manager, widgetIds(context, manager))
        }

        private fun post(
            context: Context, manager: AppWidgetManager, ids: IntArray, presentation: Presentation
        ) {
            if (!presentation.visible || (presentation.plain && !presentation.hasLyrics)) {
                manager.updateAppWidget(ids, views(context, presentation))
                return
            }
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            val rendered = ids.groupBy { id ->
                val options = manager.getAppWidgetOptions(id)
                val category = options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)
                val keyguard = category != 0 && (category and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0
                val slot = widgetSlot(context, options)
                // Always fit 3-line squircle layout when slot metrics are present or on keyguard/AOD.
                if (presentation.plain || (samsung && keyguard) || (presentation.hasLyrics && slot.hostReportsHeight)) {
                    fitBody(context, presentation, slot)
                } else presentation
            }
            rendered.forEach { (style, widgetIds) ->
                manager.updateAppWidget(widgetIds.toIntArray(), views(context, style))
            }
        }

        private fun presentation(context: Context): Presentation {
            val settings = LyricsDisplaySettingsStore.get(context)
            val fallback = LyricPanelPalette.fallback
            val monochrome = LyricPanelColors(Color.BLACK, Color.WHITE)
            if (!settings.panelEnabled) {
                return Presentation("", fallback, visible = false)
            }
            if (settings.panelDiagnostic) {
                return Presentation(context.getString(R.string.ui_panel_diagnostic_lines), monochrome, true,
                    sizeSp = 12, plain = true)
            }
            if (settings.privacy == LockScreenLyricPrivacy.HIDDEN) {
                return Presentation("", fallback, visible = false)
            }
            val interactive = cached.interactive && context.getSystemService(PowerManager::class.java).isInteractive
            // A host can cache and reuse this view on keyguard. Only explicit PUBLIC may ever
            // receive lyric text or song-derived colors, even when updated while unlocked.
            if (settings.privacy != LockScreenLyricPrivacy.PUBLIC) {
                val colors = if (!interactive) monochrome else fallback.copy(
                    background = if (settings.panelTransparent) Color.TRANSPARENT else fallback.background
                )
                return Presentation(context.getString(R.string.ui_lock_lyrics_redacted), colors, true,
                    plain = !interactive)
            }
            if (!interactive && (!cached.ambient || !settings.panelAod)) {
                return Presentation(context.getString(R.string.ui_panel_ambient), monochrome, true, plain = true)
            }
            val line = cached.lyric?.line?.takeIf(String::isNotBlank)
            val attribution = cached.lyric?.attribution?.takeIf { line != null }.orEmpty()
            val body = (if (attribution.isEmpty()) line else line?.removeSuffix("\n$attribution"))?.take(2048)
            val showNeighbor = line != null && attribution.isEmpty()
            val previous = cached.lyric?.previousLine?.take(512).takeIf { showNeighbor }
            val next = cached.lyric?.nextLine?.take(512).takeIf { showNeighbor }
            val singleBodyLine = previous != null || next != null
            val album = if (line != null) cached.colors else fallback
            val (background, foreground) = LyricPanelAppearance.colors(settings, album.background, album.foreground)
            return Presentation(
                body?.let { if (singleBodyLine) it.substringBefore('\n') else it }
                    ?: context.getString(R.string.ui_panel_idle),
                if (interactive) LyricPanelColors(background, foreground) else monochrome,
                true, attribution, settings.panelTextSizeSp,
                settings.panelTypeface,
                plain = !interactive, previous = previous, next = next,
                bodyLines = if (singleBodyLine || attribution.isNotEmpty()) 1 else 2,
                child = cached.child,
                motion = interactive && !settings.minimalMotion && body != null,
                hasLyrics = body != null
            )
        }

        @Suppress("DEPRECATION") // Widget hosts send a list of framework SizeF values in the bundle.
        private fun widgetSlot(context: Context, options: Bundle): WidgetSlot {
            val sizes = (options.get(AppWidgetManager.OPTION_APPWIDGET_SIZES) as? List<*>)
                ?.filterIsInstance<SizeF>().orEmpty()
            val width = sizes.map { it.width }.filter { it >= 8 && it.isFinite() }.minOrNull()
                ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
            val height = sizes.map { it.height }.filter { it >= 8 && it.isFinite() }.minOrNull()
                ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
            val display = context.resources.displayMetrics
            fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, display)
            // Stock Samsung slots can report zero. A resize-capable host supplies its own dimensions.
            val widthPx = if (width >= 8) px(width - 4) else display.widthPixels * 0.30f
            val heightPx = px(if (height >= 8) height - 6 else 48f)
            return WidgetSlot(widthPx.roundToInt().coerceAtLeast(1), heightPx.roundToInt().coerceAtLeast(1), height >= 8)
        }

        private fun neighborSize(heroSizeSp: Int): Int {
            return (heroSizeSp * 0.72f).roundToInt().coerceIn(9, (heroSizeSp - 2).coerceAtLeast(9))
        }

        private fun fitBody(context: Context, presentation: Presentation, slot: WidgetSlot): Presentation {
            val display = context.resources.displayMetrics
            fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, display)
            val compact = slot.heightPx < px(72f)
            val innerW = (slot.widthPx - px(if (compact) 16f else 28f)).roundToInt().coerceAtLeast(1)
            val innerH = (slot.heightPx - px(if (compact) 8f else 20f)).roundToInt().coerceAtLeast(1)
            val gapPx = px(if (compact) 2f else 5f).roundToInt()

            val text = LyricPanelAppearance.text(presentation.text, presentation.typeface)
            fun lineHeight(sizeSp: Int) =
                lyricPanelPaint(context, sizeSp, presentation.typeface).fontMetricsInt
                    .let { (it.descent - it.ascent).coerceAtLeast(1) }

            val minimum = minOf(presentation.sizeSp, LyricsDisplaySettings.MIN_PANEL_TEXT_SIZE_SP)
            val hasNeighbors = presentation.attribution.isEmpty() &&
                (presentation.previous != null || presentation.next != null)

            if (hasNeighbors && !compact) {
                // Keep all 3 lines (prev / hero / next) vertically centered on LockStar & Home squircle cards.
                for (size in presentation.sizeSp downTo minimum) {
                    val layout = StaticLayout.Builder.obtain(
                        text, 0, text.length, lyricPanelPaint(context, size, presentation.typeface), innerW
                    ).setIncludePad(false).setMaxLines(2).build()
                    val nHeight = lineHeight(neighborSize(size))
                    val prevSpace = if (presentation.previous != null) nHeight + gapPx else 0
                    val nextSpace = if (presentation.next != null) nHeight + gapPx else 0
                    if (layout.height + prevSpace + nextSpace <= innerH) {
                        return presentation.copy(
                            sizeSp = size,
                            bodyLines = layout.lineCount.coerceIn(1, 2),
                            compactPadding = false
                        )
                    }
                }
            }

            val paint = lyricPanelPaint(context, presentation.sizeSp, presentation.typeface)
            val wrap = presentation.attribution.isEmpty() &&
                ('\n' in presentation.text || Layout.getDesiredWidth(text, paint) > innerW)
            if (!wrap) {
                val reserved = lineHeight(presentation.sizeSp) +
                    if (presentation.attribution.isNotEmpty()) lineHeight(11) else 0
                val nHeight = lineHeight(neighborSize(presentation.sizeSp)) + gapPx
                val neighbors = ((innerH - reserved) / nHeight.coerceAtLeast(1))
                    .coerceAtLeast(if (presentation.plain && !slot.hostReportsHeight) 1 else 0)
                return presentation.copy(
                    previous = presentation.previous.takeIf { neighbors >= 2 || (neighbors == 1 && presentation.next == null) },
                    next = presentation.next.takeIf { neighbors >= 1 },
                    bodyLines = if (presentation.hasLyrics) 1 else presentation.bodyLines,
                    compactPadding = compact
                )
            }

            val size = (presentation.sizeSp downTo minimum).first { size ->
                size == minimum || StaticLayout.Builder.obtain(
                    text, 0, text.length, lyricPanelPaint(context, size, presentation.typeface), innerW
                )
                    .setIncludePad(false).build().let { it.lineCount <= 2 && it.height <= innerH }
            }
            val heroLayout = StaticLayout.Builder.obtain(
                text, 0, text.length, lyricPanelPaint(context, size, presentation.typeface), innerW
            ).setIncludePad(false).setMaxLines(2).build()
            val nHeight = lineHeight(neighborSize(size)) + gapPx
            val neighbors = ((innerH - heroLayout.height) / nHeight.coerceAtLeast(1)).coerceAtLeast(0)
            return presentation.copy(
                sizeSp = size,
                previous = presentation.previous.takeIf { neighbors >= 2 },
                next = presentation.next.takeIf { neighbors >= 1 },
                bodyLines = heroLayout.lineCount.coerceIn(1, 2),
                compactPadding = compact
            )
        }

        private fun resolvedTypeface(context: Context, typeface: LyricPanelTypeface): Typeface = when (typeface) {
            LyricPanelTypeface.SYSTEM -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            LyricPanelTypeface.SPOTIFY -> Typeface.create(
                runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.air_spotify) }
                    .getOrNull() ?: Typeface.DEFAULT_BOLD,
                Typeface.BOLD
            )
            LyricPanelTypeface.APPLE_MUSIC ->
                runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.air_apple_music) }
                    .getOrNull() ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun lyricPanelPaint(
            context: Context, sizeSp: Int, typeface: LyricPanelTypeface = LyricPanelTypeface.SYSTEM
        ) = TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), context.resources.displayMetrics)
            this.typeface = resolvedTypeface(context, typeface)
        }

        private fun layoutFor(presentation: Presentation): Int = when (presentation.typeface) {
            LyricPanelTypeface.SPOTIFY ->
                if (presentation.motion) R.layout.fixed_lyrics_panel_animated_spotify else R.layout.fixed_lyrics_panel_spotify
            LyricPanelTypeface.APPLE_MUSIC ->
                if (presentation.motion) R.layout.fixed_lyrics_panel_animated_apple else R.layout.fixed_lyrics_panel_apple
            LyricPanelTypeface.SYSTEM ->
                if (presentation.motion) R.layout.fixed_lyrics_panel_animated else R.layout.fixed_lyrics_panel
        }

        private fun views(context: Context, presentation: Presentation) =
            RemoteViews(context.packageName, layoutFor(presentation)).apply {
                setViewVisibility(R.id.lyric_panel_content, if (presentation.visible) View.VISIBLE else View.GONE)
                val current = LyricPanelAppearance.text(presentation.text, presentation.typeface)
                fun setCurrent(id: Int) {
                    setTextViewText(id, current)
                    setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, presentation.sizeSp.toFloat())
                    setInt(id, "setMaxLines", presentation.bodyLines)
                    setTextColor(id, presentation.colors.foreground)
                }
                setCurrent(R.id.lyric_panel_text)
                if (presentation.motion) {
                    setCurrent(R.id.lyric_panel_text_alternate)
                    setDisplayedChild(R.id.lyric_panel_transition, presentation.child)
                }
                val display = context.resources.displayMetrics
                val padH = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, if (presentation.compactPadding) 8f else 14f, display
                ).roundToInt()
                val padV = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, if (presentation.compactPadding) 4f else 10f, display
                ).roundToInt()
                setViewPadding(R.id.lyric_panel_content, padH, padV, padH, padV)

                val (background, foreground) = presentation.colors
                // 58% opacity muted previous/next lines create a crisp 3-line One UI hierarchy.
                val neighborColor = ColorUtils.setAlphaComponent(foreground, LyricPanelAppearance.NEIGHBOR_LINE_ALPHA)
                val neighborSize = neighborSize(presentation.sizeSp).toFloat()
                fun setNeighbor(id: Int, line: String?) {
                    setTextViewText(id, line.orEmpty())
                    setTextColor(id, neighborColor)
                    setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, neighborSize)
                    setViewVisibility(id, if (line.isNullOrBlank()) View.GONE else View.VISIBLE)
                }
                setNeighbor(R.id.lyric_panel_previous, presentation.previous)
                setNeighbor(R.id.lyric_panel_next, presentation.next)
                setTextViewText(R.id.lyric_panel_attribution, presentation.attribution)
                setViewVisibility(R.id.lyric_panel_attribution,
                    if (presentation.attribution.isEmpty()) View.GONE else View.VISIBLE)
                setTextColor(R.id.lyric_panel_attribution, neighborColor)

                val frostedBackground = LyricPanelAppearance.frostedBackground(background)
                when {
                    presentation.plain -> {
                        if (Build.VERSION.SDK_INT >= 31) {
                            setColorStateList(R.id.lyric_panel_content, "setBackgroundTintList", null)
                        }
                        setInt(R.id.lyric_panel_content, "setBackgroundResource", R.drawable.bg_fixed_lyrics_panel_aod)
                    }
                    background == Color.TRANSPARENT -> {
                        if (Build.VERSION.SDK_INT >= 31) {
                            setColorStateList(R.id.lyric_panel_content, "setBackgroundTintList", null)
                        }
                        setInt(R.id.lyric_panel_content, "setBackgroundResource", 0)
                    }
                    Build.VERSION.SDK_INT >= 31 -> {
                        setInt(R.id.lyric_panel_content, "setBackgroundResource", R.drawable.bg_fixed_lyrics_panel)
                        setColorStateList(
                            R.id.lyric_panel_content,
                            "setBackgroundTintList",
                            ColorStateList.valueOf(frostedBackground)
                        )
                    }
                    else -> {
                        setInt(R.id.lyric_panel_content, "setBackgroundColor", frostedBackground)
                    }
                }
            }
    }
}
