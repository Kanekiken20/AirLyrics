package com.andsi.airlyrics.ui.pages.settings

import com.andsi.airlyrics.ui.state.rememberAuxiliaryDialog
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog

import com.andsi.airlyrics.R

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.applyAirThemeTint
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lockscreen.AirLyricsAodWidgetProvider
import com.andsi.airlyrics.lockscreen.AirLyricsDockWidgetProvider
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.widget.FixedLyricsPanelProvider
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal fun createSystemSettingsPage(activity: MainUiHost, animateChildren: Boolean): View = with(activity) createSystemSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_system)))

    container.addView(languageChoiceCard(activity))
    container.addView(statusPopupsMuteCard(activity))
    container.addView(aodLyricsCard(activity))
    container.addView(nowBarLiveUpdatesCard(activity))
    container.addView(standaloneAodScreenCard(activity))
    container.addView(lockScreenWidgetStyleCard(activity))

    container.addView(systemPermissionCard())

    return scroll(activity, container, animateChildren = animateChildren)
}

private fun aodLyricsCard(activity: MainUiHost): View = with(activity) aodLyricsCard@ {
    val aodLyricsSwitch = SwitchCompat(activity).apply {
        isChecked = AppSettingsStore.isAodLyricsEnabled(activity)
        applyAirThemeTint(activity)
        contentDescription = "LockStar AOD Lyrics Widget"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            addView(bigText(activity, "LockStar Lock Screen & AOD Widgets"))
            addView(smallHint(activity, "Feeds AirLyrics widgets via Good Lock LockStar while leaving Samsung's stock Lock Screen & AOD media player untouched."))
        })

        addView(aodLyricsSwitch)

        aodLyricsSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettingsStore.setAodLyricsEnabled(activity, checked)
            if (checked) {
                val pm = activity.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                        )
                    }
                }
                AirLyricsAodWidgetProvider.pushLatest(activity, force = true)
                AirLyricsDockWidgetProvider.pushLatest(activity, force = true)
                FixedLyricsPanelProvider.pushLatest(activity, force = true)
            } else {
                AirLyricsAodWidgetProvider.clearWidgets(activity)
                AirLyricsDockWidgetProvider.clearWidgets(activity)
                FixedLyricsPanelProvider.updateLyrics(activity, null, null)
                FixedLyricsPanelProvider.pushLatest(activity, force = true)
            }
            activity.sendBroadcast(
                Intent(AppSettingsStore.ACTION_STYLE_PREF_CHANGED)
                    .setPackage(activity.packageName)
            )
        }
        setOnClickListener {
            aodLyricsSwitch.isChecked = !aodLyricsSwitch.isChecked
        }
    }
}

private fun nowBarLiveUpdatesCard(activity: MainUiHost): View = with(activity) nowBarLiveUpdatesCard@ {
    val liveUpdatesSwitch = SwitchCompat(activity).apply {
        isChecked = AppSettingsStore.isNowBarLiveUpdatesEnabled(activity)
        applyAirThemeTint(activity)
        contentDescription = "Now Bar Live Updates"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val statusHint = if (android.os.Build.VERSION.SDK_INT >= 36) {
        val nm = activity.getSystemService(android.app.NotificationManager::class.java)
        val promoted = runCatching { nm?.canPostPromotedNotifications() == true }.getOrDefault(false)
        if (promoted) {
            "Promotes live lyrics into Samsung One UI 8.5 Now Bar on Lock Screen & native AOD without replacing your media player (Live Updates active)."
        } else {
            "Promotes live lyrics into Samsung One UI 8.5 Now Bar on Lock Screen & native AOD. Tap settings below if Live Notifications permission is disabled."
        }
    } else {
        "Android 16+ Live Updates (Now Bar) integration for Samsung One UI 8.5 Lock Screen & native AOD."
    }

    return card(activity) {
        orientation = LinearLayout.VERTICAL

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
                }
                addView(bigText(activity, "One UI 8.5 Now Bar Live Updates (AOD)"))
                addView(smallHint(activity, statusHint))
            })

            addView(liveUpdatesSwitch)

            liveUpdatesSwitch.setOnCheckedChangeListener { _, checked ->
                AppSettingsStore.setNowBarLiveUpdatesEnabled(activity, checked)
                activity.sendBroadcast(
                    Intent(AppSettingsStore.ACTION_STYLE_PREF_CHANGED)
                        .setPackage(activity.packageName)
                )
            }
            setOnClickListener {
                liveUpdatesSwitch.isChecked = !liveUpdatesSwitch.isChecked
            }
        })

        if (android.os.Build.VERSION.SDK_INT >= 36) {
            val nm = activity.getSystemService(android.app.NotificationManager::class.java)
            val promoted = runCatching { nm?.canPostPromotedNotifications() == true }.getOrDefault(false)
            if (!promoted) {
                addView(TextView(activity).apply {
                    text = "Allow Live Notifications in System Settings"
                    textSize = AirUiTokens.TextSize.Caption
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccentText)
                    setPadding(0, dp(AirUiTokens.Space.Lg), 0, 0)
                    setOnClickListener {
                        runCatching {
                            activity.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                }
                            )
                        }.onFailure {
                            runCatching {
                                activity.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                    }
                                )
                            }
                        }
                    }
                })
            }
        }
    }
}

private fun standaloneAodScreenCard(activity: MainUiHost): View = with(activity) standaloneAodScreenCard@ {
    val standaloneSwitch = SwitchCompat(activity).apply {
        isChecked = AppSettingsStore.isStandaloneAodScreenEnabled(activity)
        applyAirThemeTint(activity)
        contentDescription = "Standalone Lyrics AOD Screen"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            addView(bigText(activity, "Standalone Lyrics AOD Screen"))
            addView(smallHint(activity, "Optional full-screen black AMOLED AOD with One UI Clock & centered lyrics (OFF by default so native Lock Screen & Samsung AOD remain untouched)."))
        })

        addView(standaloneSwitch)

        standaloneSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettingsStore.setStandaloneAodScreenEnabled(activity, checked)
            activity.sendBroadcast(
                Intent(AppSettingsStore.ACTION_STYLE_PREF_CHANGED)
                    .setPackage(activity.packageName)
            )
        }
        setOnClickListener {
            standaloneSwitch.isChecked = !standaloneSwitch.isChecked
        }
    }
}

private fun lockScreenWidgetStyleCard(activity: MainUiHost): View = with(activity) lockScreenWidgetStyleCard@ {
    fun notifyStyleUpdated() {
        AirLyricsAodWidgetProvider.pushLatest(activity, force = true)
        AirLyricsDockWidgetProvider.pushLatest(activity, force = true)
        activity.sendBroadcast(
            Intent(AppSettingsStore.ACTION_STYLE_PREF_CHANGED)
                .setPackage(activity.packageName)
        )
    }

    fun styleChoiceChip(view: TextView, selected: Boolean) {
        val tone = if (selected) AirTonalButtonTone.SELECTED else AirTonalButtonTone.DEFAULT
        val style = activity.airTonalButtonStyle(tone)
        view.setTextColor(style.textColor)
        view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        activity.styleAirTonalButton(view, tone)
    }

    return card(activity) {
        orientation = LinearLayout.VERTICAL

        addView(bigText(activity, "Lock Screen & AOD Widget Style"))
        addView(smallHint(
            activity,
            "Applies to Lock Screen widgets and Floating lyrics. AOD automatically switches to pure high-contrast white native text."
        ))

        // 1. Font Style Selector (Default / Spotify / Apple Music)
        addView(TextView(activity).apply {
            text = getString(R.string.ui_font)
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            setPadding(0, dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Sm))
        })

        val fontPresets = listOf(
            AppSettingsStore.FONT_PRESET_DEFAULT to getString(R.string.ui_font_preset_default),
            AppSettingsStore.FONT_PRESET_SPOTIFY to getString(R.string.ui_font_preset_spotify),
            AppSettingsStore.FONT_PRESET_APPLE_MUSIC to getString(R.string.ui_font_preset_apple_music)
        )
        val fontRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val fontButtons = mutableListOf<Pair<String, TextView>>()
        val currentFontPreset = AppSettingsStore.getLyricsFontPreset(activity)
        fontPresets.forEachIndexed { idx, (presetKey, label) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = AirUiTokens.TextSize.Body
                gravity = Gravity.CENTER
                setPadding(dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = dp(AirUiTokens.Space.Sm)
                }
                enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
                styleChoiceChip(this, presetKey == currentFontPreset)
            }
            fontButtons += presetKey to btn
            fontRow.addView(btn)
        }
        fontButtons.forEach { (presetKey, btn) ->
            btn.setOnClickListener {
                AppSettingsStore.setLyricsFontPreset(activity, presetKey)
                FloatingLyricsFontStore.evictCache()
                fontButtons.forEach { (key, view) -> styleChoiceChip(view, key == presetKey) }
                notifyStyleUpdated()
            }
        }
        addView(fontRow)

        // 2. Live Widget Text-Size Scaling (75% - 145%)
        val initialScale = AppSettingsStore.getWidgetTextScalePercent(activity)
        val scaleLabel = TextView(activity).apply {
            text = "Widget text size: $initialScale%"
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            setPadding(0, dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Xxs))
        }
        addView(scaleLabel)

        val scaleStep = 5
        val scaleSeekBar = SeekBar(activity).apply {
            max = (AppSettingsStore.WIDGET_TEXT_SCALE_MAX - AppSettingsStore.WIDGET_TEXT_SCALE_MIN) / scaleStep
            progress = ((initialScale - AppSettingsStore.WIDGET_TEXT_SCALE_MIN) / scaleStep).coerceIn(0, max)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = AppSettingsStore.WIDGET_TEXT_SCALE_MIN + progress * scaleStep
                    scaleLabel.text = "Widget text size: $percent%"
                    if (fromUser) {
                        AppSettingsStore.setWidgetTextScalePercent(activity, percent)
                        notifyStyleUpdated()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        addView(scaleSeekBar)

        // 3. Automatic (Album Art Palette) / Manual Widget Color Mode
        addView(TextView(activity).apply {
            text = "Lock Screen widget color"
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            setPadding(0, dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Sm))
        })

        val swatchColors = intArrayOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#96B8FF"),
            Color.parseColor("#7CF2A5"),
            Color.parseColor("#FFD67A"),
            Color.parseColor("#FF8DA1"),
            Color.parseColor("#C9A6FF"),
            Color.parseColor("#78DCFF")
        )
        val swatchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(AirUiTokens.Space.Lg), 0, dp(AirUiTokens.Space.Xxs))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val currentColorMode = AppSettingsStore.getWidgetColorMode(activity)
        val autoBtn = TextView(activity).apply {
            text = "Auto (Album Art)"
            textSize = AirUiTokens.TextSize.Body
            gravity = Gravity.CENTER
            setPadding(dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
            styleChoiceChip(this, currentColorMode == AppSettingsStore.WIDGET_COLOR_MODE_AUTO)
        }
        val manualBtn = TextView(activity).apply {
            text = "Manual"
            textSize = AirUiTokens.TextSize.Body
            gravity = Gravity.CENTER
            setPadding(dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.ControlV))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(AirUiTokens.Space.Sm)
            }
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
            styleChoiceChip(this, currentColorMode == AppSettingsStore.WIDGET_COLOR_MODE_MANUAL)
        }

        val colorModeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(autoBtn)
            addView(manualBtn)
        }
        addView(colorModeRow)

        val swatchViews = mutableListOf<Pair<Int, View>>()
        fun refreshSwatches(selectedColor: Int) {
            swatchViews.forEach { (color, swatch) ->
                val isSelected = color == selectedColor
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        dp(if (isSelected) 3 else 1),
                        if (isSelected) activity.colorAccentText else Color.parseColor("#55FFFFFF")
                    )
                }
            }
        }

        val initialManualColor = AppSettingsStore.getWidgetManualColor(activity)
        swatchColors.forEachIndexed { idx, color ->
            val swatch = View(activity).apply {
                val size = dp(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (idx > 0) marginStart = dp(AirUiTokens.Space.Lg)
                }
                isClickable = true
                isFocusable = true
                enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
                setOnClickListener {
                    AppSettingsStore.setWidgetColorMode(activity, AppSettingsStore.WIDGET_COLOR_MODE_MANUAL)
                    AppSettingsStore.setWidgetManualColor(activity, color)
                    styleChoiceChip(autoBtn, false)
                    styleChoiceChip(manualBtn, true)
                    refreshSwatches(color)
                    notifyStyleUpdated()
                }
            }
            swatchViews += color to swatch
            swatchRow.addView(swatch)
        }
        refreshSwatches(initialManualColor)
        swatchRow.visibility = if (currentColorMode == AppSettingsStore.WIDGET_COLOR_MODE_MANUAL) {
            View.VISIBLE
        } else {
            View.GONE
        }
        addView(swatchRow)

        autoBtn.setOnClickListener {
            AppSettingsStore.setWidgetColorMode(activity, AppSettingsStore.WIDGET_COLOR_MODE_AUTO)
            styleChoiceChip(autoBtn, true)
            styleChoiceChip(manualBtn, false)
            swatchRow.visibility = View.GONE
            notifyStyleUpdated()
        }
        manualBtn.setOnClickListener {
            AppSettingsStore.setWidgetColorMode(activity, AppSettingsStore.WIDGET_COLOR_MODE_MANUAL)
            styleChoiceChip(autoBtn, false)
            styleChoiceChip(manualBtn, true)
            swatchRow.visibility = View.VISIBLE
            refreshSwatches(AppSettingsStore.getWidgetManualColor(activity))
            notifyStyleUpdated()
        }
    }
}

private fun statusPopupsMuteCard(activity: MainUiHost): View = with(activity) statusPopupsMuteCard@ {
    val statusPopupsSwitch = SwitchCompat(activity).apply {
        isChecked = areStatusPopupsMuted()
        applyAirThemeTint(activity)
        contentDescription = getString(R.string.ui_status_popups_mute)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            addView(bigText(activity, getString(R.string.ui_status_popups_mute)))
        })

        addView(statusPopupsSwitch)

        statusPopupsSwitch.setOnCheckedChangeListener { _, checked ->
            setStatusPopupsMuted(checked)
        }
        setOnClickListener {
            statusPopupsSwitch.isChecked = !statusPopupsSwitch.isChecked
        }
    }
}

private fun languageChoiceCard(activity: MainUiHost): View = with(activity) languageChoiceCard@ {
    return card(activity) {
        setPadding(dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardV), dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardV))
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener { showLanguageDialog(activity) }

        val labelView = bigText(activity, getString(R.string.ui_language))

        val valueView = TextView(activity).apply {
            text = languageSettingsState().displayName
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorText)
            gravity = Gravity.START
        }

        val chevron = airIconView(R.drawable.ic_air_chevron_right, colorAccentText).apply {
            layoutParams = ViewGroup.LayoutParams(
                dp(AirUiTokens.Layout.IconSize),
                dp(AirUiTokens.Layout.IconSize)
            )
        }

        addView(AdaptiveLabelValueLayout(
            context = activity,
            labelView = labelView,
            valueView = valueView,
            trailingView = chevron,
            horizontalGapPx = dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            verticalGapPx = dp(AirUiTokens.Space.Sm),
            trailingGapPx = dp(AirUiTokens.Space.Lg)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
}

internal fun showLanguageDialog(activity: MainUiHost) = with(activity) showLanguageDialog@ {
    if (isAuxiliaryDialogShowing("language")) return@showLanguageDialog
    val languageState = languageSettingsState()
    rememberAuxiliaryDialog("language")
    var selectedMode: String? = null
    lateinit var dialog: android.app.Dialog
    dialog = showAirDialog(
        title = getString(R.string.ui_language),
        message = null,
        positiveText = null,
        onUserDismiss = { forgetAuxiliaryDialog("language") },
        scrollStateKey = "aux.language.scroll",
        afterUserDismiss = {
            selectedMode?.let { mode ->
                setLanguageMode(mode)
                activity.reloadFloatingLyricsAfterLanguageChanged()
            }
        },
        body = {
            val selectMode: (String) -> Unit = { mode ->
                if (mode == languageState.currentMode) {
                    dialog.dismiss()
                } else {
                    // Applying an app locale recreates the Activity. Let the dialog
                    // finish leaving the old window before triggering recreation.
                    selectedMode = mode
                    dialog.dismiss()
                }
            }
            languageState.options.forEach { option ->
                addLanguageOption(activity, option.title, option.mode, languageState.currentMode, selectMode)
            }
        }
    )
    auxiliaryDialogs["language"] = dialog
}

private fun LinearLayout.addLanguageOption(
    activity: MainUiHost,
    title: String,
    mode: String,
    currentMode: String,
    onSelect: (String) -> Unit
) {
    val selected = mode == currentMode
    addView(TextView(activity).apply {
        text = title
        textSize = AirUiTokens.TextSize.Button
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val tone = if (selected) AirTonalButtonTone.SELECTED else AirTonalButtonTone.DEFAULT
        val style = activity.airTonalButtonStyle(tone)
        setTextColor(style.textColor)
        setAirStartIcon(
            host = activity,
            iconRes = R.drawable.ic_air_check.takeIf { selected },
            tint = style.textColor
        )
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), activity.dp(AirUiTokens.Space.ControlV), activity.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), activity.dp(AirUiTokens.Space.ControlV))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, activity.dp(AirUiTokens.Space.Xxl), 0, 0)
        layoutParams = params
        activity.styleAirTonalButton(this, tone)
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener {
            onSelect(mode)
        }
    })
}
