package com.andsi.airlyrics.ui.pages.settings

import com.andsi.airlyrics.ui.state.rememberAuxiliaryDialog
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog

import com.andsi.airlyrics.R

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.applyAirThemeTint
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lockscreen.AirLyricsAodWidgetProvider
import com.andsi.airlyrics.settings.store.AppSettingsStore
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
    container.addView(lockScreenLyricsCard(activity))

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
            addView(bigText(activity, "LockStar AOD Lyrics Widget"))
            addView(smallHint(activity, "Pure-black AMOLED widget placed below your AOD clock via Good Lock LockStar without altering Samsung's native media player."))
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
                FixedLyricsPanelProvider.pushLatest(activity, force = true)
            } else {
                AirLyricsAodWidgetProvider.clearWidgets(activity)
                FixedLyricsPanelProvider.updateLyrics(activity, null, null)
                FixedLyricsPanelProvider.pushLatest(activity, force = true)
            }
        }
        setOnClickListener {
            aodLyricsSwitch.isChecked = !aodLyricsSwitch.isChecked
        }
    }
}

private fun lockScreenLyricsCard(activity: MainUiHost): View = with(activity) lockScreenLyricsCard@ {
    val lockScreenSwitch = SwitchCompat(activity).apply {
        isChecked = AppSettingsStore.isLockScreenCardEnabled(activity)
        applyAirThemeTint(activity)
        contentDescription = "Lock Screen Notification Card"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val notificationsGranted = hasNotificationPermission()

    return card(activity) {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
                }
                addView(bigText(activity, "Lock Screen Notification Card"))
                addView(smallHint(activity, "Displays a rounded One UI lyrics card synchronously on your Lock Screen without Accessibility services."))
            })
            addView(lockScreenSwitch)
        }
        addView(topRow)

        val hintText = if (notificationsGranted) {
            smallHint(activity, "Keep Samsung Lock screen notifications set to Cards with Show content enabled.")
        } else {
            smallHint(activity, "Tap to allow Notifications for the Lock Screen Lyrics Card").apply {
                setTextColor(activity.colorAccentText)
            }
        }
        addView(hintText.apply {
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        })

        lockScreenSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettingsStore.setLockScreenCardEnabled(activity, checked)
            if (checked && !hasNotificationPermission()) {
                uiActions.requestNotificationPermission()
            }
        }

        setOnClickListener {
            if (!hasNotificationPermission()) {
                uiActions.requestNotificationPermission()
            } else {
                lockScreenSwitch.isChecked = !lockScreenSwitch.isChecked
            }
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
