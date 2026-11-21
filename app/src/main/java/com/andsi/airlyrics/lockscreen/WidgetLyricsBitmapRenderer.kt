package com.andsi.airlyrics.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders crisp, Binder-safe (<500 KB) anti-aliased custom-font lyric bitmaps for interactive
 * Lock Screen AppWidgets when Spotify (Figtree) or Apple Music (Inter) is selected.
 * Never used on Always-On Display (AOD), where pure native TextViews are enforced.
 */
internal object WidgetLyricsBitmapRenderer {
    private const val MAX_CARD_WIDTH_PX = 520
    private const val MAX_CARD_HEIGHT_PX = 290
    private const val MAX_DOCK_WIDTH_PX = 440
    private const val MAX_DOCK_HEIGHT_PX = 56

    fun renderCard(
        context: Context,
        meta: String,
        previousLine: String,
        currentLine: String,
        nextLine: String,
        primaryColor: Int,
        textScale: Float,
        widthDp: Int,
        heightDp: Int
    ): Bitmap? = runCatching {
        val density = context.resources.displayMetrics.density
        val renderScale = min(density, 1.85f)
        val widthPx = (widthDp.coerceIn(160, 320) * renderScale)
            .roundToInt()
            .coerceIn(220, MAX_CARD_WIDTH_PX)
        val maxHeightPx = (heightDp.coerceIn(96, 220) * renderScale)
            .roundToInt()
            .coerceIn(120, MAX_CARD_HEIGHT_PX)

        val padH = (12f * renderScale).roundToInt()
        val padV = (8f * renderScale).roundToInt()
        val contentWidth = (widthPx - padH * 2).coerceAtLeast(80)
        val preset = AppSettingsStore.getLyricsFontPreset(context)

        val boldFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = true)
        val regularFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = false)
        val boldWeight = if (preset == AppSettingsStore.FONT_PRESET_SPOTIFY) 700 else 600
        val regularWeight = if (preset == AppSettingsStore.FONT_PRESET_SPOTIFY) 500 else 450

        fun buildPaint(sp: Float, color: Int, bold: Boolean): TextPaint {
            return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    sp * textScale,
                    context.resources.displayMetrics
                ) * (renderScale / density.coerceAtLeast(1f))
                this.color = color
                typeface = if (bold) boldFace else regularFace
                fontVariationSettings = "'wght' ${if (bold) boldWeight else regularWeight}"
                setShadowLayer(3.5f, 0f, 1.5f, 0xCC000000.toInt())
            }
        }

        fun layoutOf(text: String, paint: TextPaint, maxLines: Int, lineMult: Float): StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, lineMult)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setMaxLines(maxLines)
                .build()
        }

        val blocks = mutableListOf<Pair<StaticLayout, Int>>()
        val gapSmall = (6f * renderScale).roundToInt()
        val gapMedium = (8f * renderScale).roundToInt()

        if (meta.isNotEmpty()) {
            val paint = buildPaint(11.5f, ColorUtils.setAlphaComponent(primaryColor, 185), bold = false)
            blocks += layoutOf(meta, paint, 1, 1.05f) to gapMedium
        }
        if (previousLine.isNotEmpty()) {
            val paint = buildPaint(13f, ColorUtils.setAlphaComponent(primaryColor, 130), bold = false)
            blocks += layoutOf(previousLine, paint, 1, 1.1f) to gapSmall
        }
        if (currentLine.isNotEmpty()) {
            val paint = buildPaint(20f, primaryColor or (0xFF shl 24), bold = true)
            blocks += layoutOf(currentLine, paint, 3, 1.14f) to gapSmall
        }
        if (nextLine.isNotEmpty()) {
            val paint = buildPaint(14f, ColorUtils.setAlphaComponent(primaryColor, 200), bold = false)
            blocks += layoutOf(nextLine, paint, 2, 1.1f) to 0
        }
        if (blocks.isEmpty()) return null

        val totalContentHeight = blocks.sumOf { (layout, gap) -> layout.height + gap } -
            blocks.last().second
        val bitmapHeight = (totalContentHeight + padV * 2).coerceIn(80, maxHeightPx)

        val bitmap = Bitmap.createBitmap(widthPx, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        var y = ((bitmapHeight - totalContentHeight) / 2f).coerceAtLeast(padV.toFloat())
        blocks.forEachIndexed { index, (layout, gap) ->
            canvas.save()
            canvas.translate(padH.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + if (index < blocks.lastIndex) gap else 0
        }
        bitmap
    }.getOrNull()

    fun renderDockLine(
        context: Context,
        line: String,
        primaryColor: Int,
        textScale: Float
    ): Bitmap? = runCatching {
        if (line.isBlank()) return null
        val density = context.resources.displayMetrics.density
        val renderScale = min(density, 1.85f)
        val widthPx = MAX_DOCK_WIDTH_PX
        val heightPx = (24f * textScale.coerceIn(0.85f, 1.3f) * renderScale)
            .roundToInt()
            .coerceIn(32, MAX_DOCK_HEIGHT_PX)
        val preset = AppSettingsStore.getLyricsFontPreset(context)
        val boldWeight = if (preset == AppSettingsStore.FONT_PRESET_SPOTIFY) 700 else 600

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f * textScale.coerceIn(0.85f, 1.25f),
                context.resources.displayMetrics
            ) * (renderScale / density.coerceAtLeast(1f))
            color = primaryColor or (0xFF shl 24)
            typeface = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = true)
            fontVariationSettings = "'wght' $boldWeight"
        }

        val layout = StaticLayout.Builder.obtain(line, 0, line.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(1)
            .build()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val y = ((heightPx - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(0f, y)
        layout.draw(canvas)
        canvas.restore()
        bitmap
    }.getOrNull()
}