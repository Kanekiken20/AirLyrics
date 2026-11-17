package com.andsi.airlyrics.ui.pages.floating.sections

import android.view.View
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
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
