package com.andsi.airlyrics.ui.pages.floating.sections

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.LyricPanelAppearance
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.settingSwitch
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle

/** Native controls use the UI host boundary; stores and service commands stay in the app layer. */
internal fun FloatingPageScope.addNativeDisplaySection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_native_display_title)))
    val controls = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    val expandButton = actionButton(host, getString(R.string.ui_native_display_controls)) {
        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    list.addView(expandButton)
    list.addView(controls)
    with(controls) {
        addView(settingSwitch(host, getString(R.string.ui_hold_to_edit), holdToEditEnabled()) { enabled ->
            setHoldToEditEnabled(enabled)
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_edge_docking), edgeDockingEnabled()) { enabled ->
            setEdgeDockingEnabled(enabled)
            notifyFloatingStyleChanged()
        })
        addView(smallHint(host, getString(R.string.ui_hold_to_edit_hint)))
        addView(settingSwitch(host, getString(R.string.ui_panel_enable), lyricsDisplaySettings().panelEnabled) { enabled ->
            setFixedLyricsPanelEnabled(enabled)
            notifyFloatingStyleChanged()
        })
        addView(smallHint(host, getString(R.string.ui_panel_hint)))
        val preview = TextView(host).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
            maxWidth = dp(260)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        fun updatePreview() {
            val settings = lyricsDisplaySettings()
            val (albumBackground, albumForeground) = panelPreviewAlbumColors()
            val (background, foreground) = LyricPanelAppearance.colors(settings, albumBackground, albumForeground)
            val diagnostic = settings.panelDiagnostic
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(if (diagnostic) Color.BLACK else background)
            }
            preview.setTextColor(if (diagnostic) Color.WHITE else foreground)
            preview.textSize = if (diagnostic) 12f else settings.panelTextSizeSp.toFloat()
            preview.typeface = when (settings.panelTypeface) {
                LyricPanelTypeface.SYSTEM -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                LyricPanelTypeface.SPOTIFY -> android.graphics.Typeface.create(
                    runCatching { androidx.core.content.res.ResourcesCompat.getFont(host, R.font.air_spotify) }
                        .getOrNull() ?: android.graphics.Typeface.DEFAULT_BOLD,
                    android.graphics.Typeface.BOLD
                )
                LyricPanelTypeface.APPLE_MUSIC ->
                    runCatching { androidx.core.content.res.ResourcesCompat.getFont(host, R.font.air_apple_music) }
                        .getOrNull() ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            preview.text = if (diagnostic) getString(R.string.ui_panel_diagnostic_lines) else
                LyricPanelAppearance.text(getString(R.string.ui_panel_preview_lines), settings.panelTypeface)
        }
        addView(preview)
        updatePreview()
        addView(sliderRow(getString(R.string.ui_panel_text_size), lyricsDisplaySettings().panelTextSizeSp,
            LyricsDisplaySettings.MIN_PANEL_TEXT_SIZE_SP, LyricsDisplaySettings.MAX_PANEL_TEXT_SIZE_SP, " sp") { size ->
            setPanelTextSize(size)
            updatePreview()
            notifyFloatingStyleChanged()
        })
        addView(liveOptionGrid(LyricPanelTypeface.entries.map { typeface ->
            KeyedOptionItem(
                key = typeface.name,
                title = getString(when (typeface) {
                    LyricPanelTypeface.SYSTEM -> R.string.ui_panel_type_system
                    LyricPanelTypeface.SPOTIFY -> R.string.ui_panel_type_spotify
                    LyricPanelTypeface.APPLE_MUSIC -> R.string.ui_panel_type_apple
                }),
                selected = lyricsDisplaySettings().panelTypeface == typeface,
                action = {
                    setPanelTypeface(typeface)
                    updatePreview()
                    notifyFloatingStyleChanged()
                }
            )
        }))
        if (showPanelTestControls()) {
            addView(settingSwitch(host, getString(R.string.ui_panel_diagnostic), lyricsDisplaySettings().panelDiagnostic) {
                setPanelDiagnostic(it)
                updatePreview()
                notifyFloatingStyleChanged()
            })
        }
        addView(settingSwitch(host, getString(R.string.ui_panel_aod), lyricsDisplaySettings().panelAod) {
            setPanelAod(it)
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_panel_aod_screen), lyricsDisplaySettings().panelAodScreen) {
            setPanelAodScreen(it)
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_panel_album_colors), lyricsDisplaySettings().panelAlbumColors) { enabled ->
            setPanelAlbumColors(enabled)
            updatePreview()
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_panel_transparent), lyricsDisplaySettings().panelTransparent) { enabled ->
            setPanelTransparent(enabled)
            updatePreview()
            notifyFloatingStyleChanged()
        })
        addView(smallHint(host, getString(R.string.ui_panel_color_hint)))
        addView(colorControl(getString(R.string.ui_text_color), lyricsDisplaySettings().panelTextColor, includeOpacity = false) { color ->
            setPanelColor(true, color)
            updatePreview()
            notifyFloatingStyleChanged()
        })
        addView(colorControl(getString(R.string.ui_background), lyricsDisplaySettings().panelBackgroundColor, includeOpacity = false) { color ->
            setPanelColor(false, color)
            updatePreview()
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_lock_lyrics_enable), lyricsDisplaySettings().enabled) { enabled ->
            setLyricsDisplayEnabled(enabled)
            notifyFloatingStyleChanged()
        })
        addView(smallHint(host, getString(R.string.ui_lock_lyrics_permission_hint)))
        addView(actionButton(host, getString(R.string.ui_lock_lyrics_permissions)) {
            uiActions.requestNotificationPermission()
        })
        addView(normalText(host, getString(R.string.ui_lock_lyrics_privacy)))
        addView(liveOptionGrid(LockScreenLyricPrivacy.entries.map { privacy ->
            KeyedOptionItem(
                key = privacy.key,
                title = getString(when (privacy) {
                    LockScreenLyricPrivacy.PRIVATE -> R.string.ui_lock_lyrics_private
                    LockScreenLyricPrivacy.PUBLIC -> R.string.ui_lock_lyrics_public
                    LockScreenLyricPrivacy.HIDDEN -> R.string.ui_lock_lyrics_hidden
                }),
                selected = lyricsDisplaySettings().privacy == privacy,
                action = {
                    setLyricsDisplayPrivacy(privacy)
                    notifyFloatingStyleChanged()
                }
            )
        }))
        addView(smallHint(host, getString(R.string.ui_lock_lyrics_privacy_hint)))
        addView(settingSwitch(host, getString(R.string.ui_lock_lyrics_metadata), lyricsDisplaySettings().showMetadata) { enabled ->
            setLyricsDisplayShowMetadata(enabled)
            notifyFloatingStyleChanged()
        })
        addView(settingSwitch(host, getString(R.string.ui_lock_lyrics_compact), lyricsDisplaySettings().compact) { compact ->
            setLyricsDisplayCompact(compact)
            notifyFloatingStyleChanged()
        })
        addView(normalText(host, getString(R.string.ui_aod_capability)))
        addView(smallHint(host, getString(R.string.ui_aod_unverified_hint)))
        addView(settingSwitch(host, getString(R.string.ui_minimal_motion), lyricsDisplaySettings().minimalMotion) { enabled ->
            setLyricsDisplayMinimalMotion(enabled)
            notifyFloatingStyleChanged()
        })
        addView(smallHint(host, getString(R.string.ui_minimal_motion_hint)))
    }
}
