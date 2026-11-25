package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.softLayoutTransition
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke

internal data class FloatingPreviewCardHandle(
    val cardView: View,
    val lyricTextView: TextView,
    val bodyView: View,
    val updateText: (CharSequence, CharSequence) -> Unit,
    val updateFold: (Boolean) -> Unit
)

internal fun MainUiHost.createFloatingPreviewCard(
    isExpanded: () -> Boolean,
    setExpanded: (Boolean) -> Unit,
    style: () -> FloatingLyricsStyle,
    isWordByWordLyricsEnabled: () -> Boolean,
    plainPreviewText: () -> CharSequence,
    wordByWordPreviewText: () -> CharSequence,
    compactPreviewText: () -> CharSequence,
    onVisibilityChanged: () -> Unit = {}
): FloatingPreviewCardHandle {
    lateinit var handle: FloatingPreviewCardHandle
    lateinit var lyricView: TextView
    lateinit var toggleView: TextView
    var compactExpanded = interactions.read("floating.preview.compact")?.getBoolean("expanded") ?: false
    var fullText = if (isWordByWordLyricsEnabled()) wordByWordPreviewText() else plainPreviewText()
    var compactText = compactPreviewText()
    var displayedCompact: Boolean? = null

    fun renderText(compact: Boolean) {
        val end = compactText.indexOf('\n').takeIf { it >= 0 } ?: compactText.length
        updateFloatingPreviewText(lyricView, if (compact) compactText.subSequence(0, end) else fullText)
        displayedCompact = compact
    }

    fun togglePreview() {
        if (windowLayout.compact) {
            compactExpanded = !compactExpanded
            interactions.write("floating.preview.compact") { putBoolean("expanded", compactExpanded) }
            handle.cardView.requestLayout()
            return
        }
        val next = !isExpanded()
        setExpanded(next)
        playTinyPulse(toggleView)
        handle.updateFold(next)
    }

    val card = object : LinearLayout(this) {
        private val scrollListener = ViewTreeObserver.OnScrollChangedListener(onVisibilityChanged)
        private var scrollObserver: ViewTreeObserver? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            scrollObserver = viewTreeObserver.also { it.addOnScrollChangedListener(scrollListener) }
            onVisibilityChanged()
        }

        override fun onDetachedFromWindow() {
            scrollObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
            scrollObserver = null
            super.onDetachedFromWindow()
            onVisibilityChanged()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            onVisibilityChanged()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed) onVisibilityChanged()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val compact = windowLayout.compact
            val abbreviated = compact && !compactExpanded
            if (displayedCompact != abbreviated) renderText(abbreviated)
            val lines = if (abbreviated) 1 else Int.MAX_VALUE
            if (lyricView.maxLines != lines) lyricView.maxLines = lines
            lyricView.visibility = if (compact || isExpanded()) VISIBLE else GONE
            val expanded = if (compact) compactExpanded else isExpanded()
            toggleView.text = if (expanded) "⌃" else "⌄"
            toggleView.contentDescription = getString(if (expanded) R.string.ui_collapse_preview else R.string.ui_expand_preview)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Card).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        setPadding(dp(12), 0, dp(12), dp(12))
        layoutTransition = softLayoutTransition()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(FloatingPageTokens.PREVIEW_CARD_MARGIN_BOTTOM_DP))
        }
        lyricView = floatingPreviewText(
            fullText,
            style()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxLines = Int.MAX_VALUE
            includeFontPadding = false
        }
        toggleView = TextView(this@createFloatingPreviewCard).apply {
            textSize = FloatingPageTokens.PREVIEW_TOGGLE_TEXT_SP
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setTextColor(colorTextMuted)
            background = null
            layoutParams = LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
            enableSoftPressFeedback(0.92f)
            setOnClickListener { togglePreview() }
        }
        addView(LinearLayout(this@createFloatingPreviewCard).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@createFloatingPreviewCard).apply {
                setText(R.string.ui_floating_preview)
                setTextColor(colorTextMuted)
                textSize = AirUiTokens.TextSize.BodySmall
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggleView)
        })
        addView(lyricView)
    }

    handle = FloatingPreviewCardHandle(
        cardView = card,
        lyricTextView = lyricView,
        bodyView = lyricView,
        updateText = { text, current ->
            fullText = text
            compactText = current
            renderText(windowLayout.compact && !compactExpanded)
        },
        updateFold = { expanded ->
            lyricView.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleView.text = if (expanded) "⌃" else "⌄"
            toggleView.contentDescription = getString(
                if (expanded) R.string.ui_collapse_preview else R.string.ui_expand_preview
            )
            card.requestLayout()
            onVisibilityChanged()
        }
    )
    handle.updateFold(isExpanded())
    return handle
}
