package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke

/** Setting tiles keep their labels readable at narrow widths and larger font scales. */
@SuppressLint("ViewConstructor")
internal class SettingTileLayout(private val host: MainUiHost, item: FloatingSettingTile) :
    LinearLayout(host) {
    private val titleView = TextView(host).apply {
        text = item.title
        textSize = AirUiTokens.TextSize.Button + 1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(host.colorTextStrong)
        applyTextPolicy(TextDisplayPolicy.Full)
    }
    private val summaryView = TextView(host).apply {
        text = item.subtitle
        textSize = AirUiTokens.TextSize.BodySmall
        setTextColor(host.colorTextMuted)
        applyTextPolicy(TextDisplayPolicy.Full)
        setPadding(0, host.dp(AirUiTokens.Space.Lg), 0, 0)
        // Keep the view even for an initially empty value: it may be updated later.
        item.onSubtitleViewCreated?.invoke(this)
    }
    private val labels = LinearLayout(host).apply {
        orientation = VERTICAL
        addView(titleView)
        addView(summaryView)
    }
    private val icon = FrameLayout(host).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(AirColorUtils.withAlpha(host.colorAccent, 40))
        }
        addView(host.airIconView(item.iconRes, host.colorAccentText), FrameLayout.LayoutParams(
            host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize), Gravity.CENTER
        ))
    }

    init {
        item.onViewCreated?.invoke(this)
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Card).toFloat()
            setColor(host.colorCard)
            setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
        }
        addView(icon)
        addView(labels)
        alpha = if (item.enabled) 1f else 0.48f
        if (item.enabled) {
            enableSoftPressFeedback(AirUiTokens.Motion.FloatingTilePressScale)
            setOnClickListener {
                item.onClick(this)
            }
        }
        orientation = VERTICAL
        gravity = Gravity.TOP
        minimumHeight = host.dp(AirUiTokens.Layout.FloatingTileMinHeight)
        val inset = host.dp(AirUiTokens.Space.ButtonH)
        val gap = host.dp(AirUiTokens.Layout.SettingGap)
        setPadding(inset, gap, inset, gap)
        icon.layoutParams = LayoutParams(host.dp(AirUiTokens.Layout.FloatingTileIconSize), host.dp(AirUiTokens.Layout.FloatingTileIconSize)).apply {
            bottomMargin = gap
        }
        labels.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
}
