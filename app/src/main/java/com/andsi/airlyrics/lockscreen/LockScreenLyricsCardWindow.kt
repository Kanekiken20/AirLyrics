package com.andsi.airlyrics.lockscreen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore

/**
 * Small, square, smooth, rounded One UI-styled lyrics card rendered directly on the
 * Samsung Lock Screen via TYPE_ACCESSIBILITY_OVERLAY (no extra Activity, no 3-step flow).
 */
class LockScreenLyricsCardWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var cardRoot: LinearLayout? = null
    private var trackLabel: TextView? = null
    private var currentLyricView: TextView? = null
    private var nextLyricView: TextView? = null
    private var isShowing = false

    fun update(
        currentLine: String,
        nextLine: String,
        trackTitle: String,
        artistName: String,
        shouldShow: Boolean
    ) {
        val hasContent = currentLine.isNotBlank() || trackTitle.isNotBlank()
        if (!shouldShow || !hasContent) {
            hide()
            return
        }

        ensureAttached()
        bindContent(currentLine, nextLine, trackTitle, artistName)
        showAnimated()
    }

    fun hide() {
        val view = cardRoot ?: return
        if (!isShowing) return
        isShowing = false
        view.animate().cancel()
        runCatching { windowManager.removeViewImmediate(view) }
        cardRoot = null
        trackLabel = null
        currentLyricView = null
        nextLyricView = null
    }

    private fun ensureAttached() {
        if (cardRoot != null) return

        val metrics = context.resources.displayMetrics
        val cardWidthPx = dp(196)
        val cardMinHeightPx = dp(172)
        // Positioned just below the Samsung One UI 6 lock screen clock & notification zone
        val topOffsetPx = (metrics.heightPixels * 0.30f).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_lockscreen_lyrics_card)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            elevation = dp(10).toFloat()
            minimumHeight = cardMinHeightPx
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }

        val scale = AppSettingsStore.getWidgetTextScalePercent(context) / 100f
        val accent = if (AppSettingsStore.getWidgetColorMode(context) == AppSettingsStore.WIDGET_COLOR_MODE_MANUAL) {
            AppSettingsStore.getWidgetManualColor(context)
        } else {
            Color.WHITE
        }
        val boldFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = true)
        val regularFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = false)

        val track = TextView(context).apply {
            setTextColor(ColorUtils.setAlphaComponent(accent, 165))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = regularFace
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }

        val primary = TextView(context).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
            typeface = boldFace
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1.05f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }

        val secondary = TextView(context).apply {
            setTextColor(ColorUtils.setAlphaComponent(accent, 185))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            typeface = regularFace
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }

        root.addView(track)
        root.addView(primary)
        root.addView(secondary)

        val params = WindowManager.LayoutParams(
            cardWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffsetPx
        }

        runCatching {
            windowManager.addView(root, params)
            cardRoot = root
            trackLabel = track
            currentLyricView = primary
            nextLyricView = secondary
        }
    }

    private fun bindContent(
        currentLine: String,
        nextLine: String,
        trackTitle: String,
        artistName: String
    ) {
        val scale = AppSettingsStore.getWidgetTextScalePercent(context) / 100f
        val accent = if (AppSettingsStore.getWidgetColorMode(context) == AppSettingsStore.WIDGET_COLOR_MODE_MANUAL) {
            AppSettingsStore.getWidgetManualColor(context)
        } else {
            Color.WHITE
        }
        val boldFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = true)
        val regularFace = FloatingLyricsFontStore.resolveWidgetTypeface(context, isBold = false)

        val header = listOf(trackTitle, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        trackLabel?.apply {
            typeface = regularFace
            setTextColor(ColorUtils.setAlphaComponent(accent, 165))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            text = header.ifBlank { "Now Playing" }
            visibility = View.VISIBLE
        }

        currentLyricView?.apply {
            typeface = boldFace
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
            text = currentLine.ifBlank { trackTitle }
        }

        nextLyricView?.apply {
            typeface = regularFace
            setTextColor(ColorUtils.setAlphaComponent(accent, 185))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            if (nextLine.isNotBlank()) {
                text = nextLine
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun showAnimated() {
        val view = cardRoot ?: return
        if (isShowing) return
        isShowing = true
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
