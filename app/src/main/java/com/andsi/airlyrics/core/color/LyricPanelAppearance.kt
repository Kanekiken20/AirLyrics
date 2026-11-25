package com.andsi.airlyrics.core.color

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.core.model.LyricsDisplaySettings

internal object LyricPanelAppearance {
    const val CORNER_RADIUS_DP = 26
    const val FROSTED_BACKGROUND_ALPHA = 212 // ~83% One UI translucent tint
    const val NEIGHBOR_LINE_ALPHA = 148 // ~58% opacity for prev/next hierarchy

    fun frostedBackground(color: Int): Int {
        if (color == Color.TRANSPARENT) return Color.TRANSPARENT
        return ColorUtils.setAlphaComponent(color, FROSTED_BACKGROUND_ALPHA)
    }

    fun colors(settings: LyricsDisplaySettings, albumBackground: Int, albumForeground: Int): Pair<Int, Int> {
        val background = if (settings.panelAlbumColors) albumBackground else settings.panelBackgroundColor
        val foreground = if (settings.panelAlbumColors) albumForeground else settings.panelTextColor
        if (settings.panelTransparent) return Color.TRANSPARENT to foreground
        if (ColorUtils.calculateContrast(foreground, background) >= 4.5) return background to foreground
        val contrasting = if (ColorUtils.calculateContrast(Color.WHITE, background) >= 4.5) Color.WHITE else Color.BLACK
        return background to contrasting
    }

    fun text(text: String, typeface: LyricPanelTypeface): CharSequence {
        if (text.isEmpty() || typeface == LyricPanelTypeface.SYSTEM) return text
        // RemoteViews layouts apply @font/air_spotify and @font/air_apple_music natively.
        // Avoid TypefaceSpan("sans-serif") here because a named system family overrides @font/*.
        if (typeface == LyricPanelTypeface.SPOTIFY) {
            return SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }
}
