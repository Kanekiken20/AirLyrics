package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.core.model.FloatingLyricsPreset
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricPreview
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode

internal data class FloatingFocusBubbleHandle(
    val view: LinearLayout,
    val rebuildContent: () -> Unit,
    val updateResetAction: (Boolean, Boolean) -> Unit
)

internal data class FloatingPreviewHighlightProgress(
    val completedEnd: Int,
    val activeStart: Int,
    val activeEnd: Int,
    val activeFraction: Float,
    val hasActiveCharacter: Boolean
)

internal interface FloatingUiHost {
    fun settingGrid(vararg items: FloatingSettingTile): LinearLayout
    fun floatingTile(item: FloatingSettingTile): LinearLayout
    fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onReset: (() -> Unit)?,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): FloatingFocusBubbleHandle

    fun floatingStyle(): FloatingLyricsStyle
    fun floatingStyleDefaults(preset: String): FloatingLyricsStyle
    fun floatingPresets(): List<FloatingLyricsPreset>
    fun floatingCustomFontName(): String?
    fun hasFloatingCustomFont(): Boolean
    fun selectFloatingFontFile()
    fun isFloatingPreviewExpanded(): Boolean
    fun liveFloatingLyric(): LyricPreview? = null
    fun setFloatingPreviewExpanded(expanded: Boolean)
    fun floatingDisplaySummary(): String
    fun floatingLockButtonText(): String
    fun floatingClickThroughButtonText(): String
    fun autoHideWhenPausedEnabled(): Boolean
    fun autoHideWhenLyricsUnavailableEnabled(): Boolean
    fun displayScopeSupported(): Boolean
    fun displayScopeEnabled(): Boolean
    fun displayScopeSelectedCount(): Int
    fun displayScopeSummary(): String
    fun hasUsageStatsAccess(): Boolean
    fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView
    fun updateFloatingPreviewText(view: TextView, text: CharSequence)
    fun syncFloatingPreviewVisibility(view: TextView, visible: Boolean)
    fun hasAnimatedFloatingPreviewHighlight(view: TextView): Boolean
    fun animateFloatingPreviewText(view: TextView, durationMs: Long, offsetY: Float, scale: Float)
    fun classicPreviewProgressForLine(lineText: String): (Long) -> FloatingPreviewHighlightProgress
    fun applyAnimatedFloatingPreviewHighlight(
        text: android.text.SpannableStringBuilder,
        currentLine: String,
        start: Int,
        color: Int,
        classic: Boolean = false
    )
    fun applyAnimatedFloatingPreviewLine(
        text: android.text.SpannableStringBuilder,
        start: Int, end: Int, lineOffset: Int, partIndex: Int, color: Int, animated: Boolean = true
    )
    fun updateFloatingPreviewPlayback(view: TextView, positionMs: Long)
    fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle)
    fun applyFloatingPreset(preset: String)
    fun applyFloatingStyle(style: FloatingLyricsStyle)
    fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true)
    fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingTextAlpha(alpha: Int, refreshPage: Boolean = true)
    fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingBackgroundEnabled(enabled: Boolean)
    fun applyFloatingBackgroundAlpha(alpha: Int)
    fun applyFloatingGravity(gravity: Int)
    fun applyFloatingShadowRadius(radius: Float)
    fun applyFloatingShadowColor(color: Int)
    fun applyFloatingMaxWidthPercent(percent: Int)
    fun applyFloatingPaddingHorizontal(paddingDp: Int)
    fun applyFloatingPaddingVertical(paddingDp: Int)
    fun applyFloatingCornerRadius(radiusDp: Int)
    fun applyFloatingWordByWordHighlightColor(color: Int)
    fun lyricsContentDisplayMode(): LyricsContentDisplayMode
    fun lyricsScriptPreference(): LyricsScriptPreference
    fun lyricsLineDisplayMode(): LyricsLineDisplayMode
    fun lyricsSwitchAnimationMode(): LyricsSwitchAnimationMode
    fun wordByWordAnimationMode(): WordByWordAnimationMode
    fun setWordByWordAnimationMode(mode: WordByWordAnimationMode)
    fun wordByWordLyricsEnabled(): Boolean
    fun setLyricsContentDisplayMode(mode: LyricsContentDisplayMode)
    fun setLyricsScriptPreference(preference: LyricsScriptPreference)
    fun setLyricsLineDisplayMode(mode: LyricsLineDisplayMode)
    fun setLyricsSwitchAnimationMode(mode: LyricsSwitchAnimationMode)
    fun setWordByWordLyricsEnabled(enabled: Boolean)
    // Defaults keep preview/test hosts independent of persistence. The application host owns stores.
    fun lyricsDisplaySettings() = com.andsi.airlyrics.core.model.LyricsDisplaySettings()
    fun setLyricsDisplayEnabled(enabled: Boolean) = Unit
    fun setFixedLyricsPanelEnabled(enabled: Boolean) = Unit
    fun setPanelAlbumColors(enabled: Boolean) = Unit
    fun setPanelTransparent(enabled: Boolean) = Unit
    fun setPanelColor(text: Boolean, color: Int) = Unit
    fun setPanelTextSize(sizeSp: Int) = Unit
    fun setPanelTypeface(typeface: com.andsi.airlyrics.core.model.LyricPanelTypeface) = Unit
    fun panelPreviewAlbumColors(): Pair<Int, Int> = 0xFF202127.toInt() to 0xFFF4F5F8.toInt()
    fun showPanelTestControls(): Boolean = false
    fun setPanelDiagnostic(enabled: Boolean) = Unit
    fun setPanelAod(enabled: Boolean) = Unit
    fun setPanelAodScreen(enabled: Boolean) = Unit
    fun setLyricsDisplayPrivacy(privacy: com.andsi.airlyrics.core.model.LockScreenLyricPrivacy) = Unit
    fun setLyricsDisplayShowMetadata(enabled: Boolean) = Unit
    fun setLyricsDisplayCompact(compact: Boolean) = Unit
    fun setLyricsDisplayMinimalMotion(minimalMotion: Boolean) = Unit
    fun holdToEditEnabled(): Boolean = true
    fun setHoldToEditEnabled(enabled: Boolean) = Unit
    fun edgeDockingEnabled(): Boolean = true
    fun setEdgeDockingEnabled(enabled: Boolean) = Unit
    fun notifyFloatingStyleChanged()
    fun notifyFloatingTextChanged()
}
