package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

/** Stores app-wide preferences that do not belong to a single feature area. */
object AppSettingsStore {
    private const val PREFS_NAME = "app_settings"
    // Keep the original key so upgrades preserve the user's existing preference.
    private const val KEY_STATUS_POPUPS_MUTED = "toaster_muted"
    private const val KEY_AOD_LYRICS_ENABLED = "aod_lyrics_enabled"
    private const val KEY_AOD_SCROLL_ENABLED = "aod_horizontal_scroll"
    private const val KEY_LOCK_SCREEN_CARD_ENABLED = "lock_screen_card_enabled"
    private const val KEY_SUPPRESS_LOCK_NOTIFS = "suppress_lock_screen_notifications"
    private const val KEY_FLOATING_COLLAPSED_SIDE = "floating_collapsed_side"
    private const val KEY_FLOATING_Y_FRACTION = "floating_y_fraction"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun areStatusPopupsMuted(context: Context): Boolean {
        return store(context).getBoolean(KEY_STATUS_POPUPS_MUTED, false)
    }

    fun setStatusPopupsMuted(context: Context, muted: Boolean) {
        store(context).setBoolean(KEY_STATUS_POPUPS_MUTED, muted)
    }

    fun isAodLyricsEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_AOD_LYRICS_ENABLED, true)
    }

    fun setAodLyricsEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_AOD_LYRICS_ENABLED, enabled)
    }

    fun isAodHorizontalScrollEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_AOD_SCROLL_ENABLED, true)
    }

    fun setAodHorizontalScrollEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_AOD_SCROLL_ENABLED, enabled)
    }

    fun isLockScreenCardEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_LOCK_SCREEN_CARD_ENABLED, true)
    }

    fun setLockScreenCardEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_LOCK_SCREEN_CARD_ENABLED, enabled)
    }

    fun isSuppressLockScreenNotifications(context: Context): Boolean {
        return store(context).getBoolean(KEY_SUPPRESS_LOCK_NOTIFS, true)
    }

    fun setSuppressLockScreenNotifications(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_SUPPRESS_LOCK_NOTIFS, enabled)
    }

    fun getFloatingCollapsedSide(context: Context): String {
        return store(context).getString(KEY_FLOATING_COLLAPSED_SIDE, "NONE") ?: "NONE"
    }

    fun setFloatingCollapsedSide(context: Context, side: String) {
        store(context).setString(KEY_FLOATING_COLLAPSED_SIDE, side)
    }

    fun getFloatingYFraction(context: Context): Float {
        return store(context).getFloat(KEY_FLOATING_Y_FRACTION, 0.22f)
    }

    fun setFloatingYFraction(context: Context, fraction: Float) {
        val clamped = fraction.coerceIn(0.05f, 0.85f)
        store(context).setFloat(KEY_FLOATING_Y_FRACTION, clamped)
    }
}
