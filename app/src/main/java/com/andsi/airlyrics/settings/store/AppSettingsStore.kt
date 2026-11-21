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
    private const val KEY_LYRICS_FONT_PRESET = "lyrics_font_preset"
    private const val KEY_WIDGET_TEXT_SCALE_PERCENT = "widget_text_scale_percent"
    private const val KEY_WIDGET_COLOR_MODE = "widget_color_mode"
    private const val KEY_WIDGET_MANUAL_COLOR = "widget_manual_color"

    const val ACTION_STYLE_PREF_CHANGED = "com.andsi.airlyrics.action.STYLE_PREF_CHANGED"

    const val FONT_PRESET_DEFAULT = "default"
    const val FONT_PRESET_SPOTIFY = "spotify"
    const val FONT_PRESET_APPLE_MUSIC = "apple_music"

    const val WIDGET_COLOR_MODE_AUTO = "auto"
    const val WIDGET_COLOR_MODE_MANUAL = "manual"

    const val WIDGET_TEXT_SCALE_MIN = 75
    const val WIDGET_TEXT_SCALE_MAX = 145
    const val WIDGET_TEXT_SCALE_DEFAULT = 100
    const val WIDGET_MANUAL_COLOR_DEFAULT = 0xFFFFFFFF.toInt()

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
        return store(context).getBoolean(KEY_LOCK_SCREEN_CARD_ENABLED, false)
    }

    fun setLockScreenCardEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_LOCK_SCREEN_CARD_ENABLED, enabled)
    }

    fun isSuppressLockScreenNotifications(context: Context): Boolean {
        return store(context).getBoolean(KEY_SUPPRESS_LOCK_NOTIFS, false)
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

    fun getLyricsFontPreset(context: Context): String {
        return when (store(context).getString(KEY_LYRICS_FONT_PRESET, FONT_PRESET_DEFAULT)) {
            FONT_PRESET_SPOTIFY -> FONT_PRESET_SPOTIFY
            FONT_PRESET_APPLE_MUSIC -> FONT_PRESET_APPLE_MUSIC
            else -> FONT_PRESET_DEFAULT
        }
    }

    fun setLyricsFontPreset(context: Context, preset: String) {
        val normalized = when (preset) {
            FONT_PRESET_SPOTIFY -> FONT_PRESET_SPOTIFY
            FONT_PRESET_APPLE_MUSIC -> FONT_PRESET_APPLE_MUSIC
            else -> FONT_PRESET_DEFAULT
        }
        store(context).setString(KEY_LYRICS_FONT_PRESET, normalized)
    }

    fun getWidgetTextScalePercent(context: Context): Int {
        return store(context).getInt(KEY_WIDGET_TEXT_SCALE_PERCENT, WIDGET_TEXT_SCALE_DEFAULT)
            .coerceIn(WIDGET_TEXT_SCALE_MIN, WIDGET_TEXT_SCALE_MAX)
    }

    fun setWidgetTextScalePercent(context: Context, percent: Int) {
        store(context).setInt(
            KEY_WIDGET_TEXT_SCALE_PERCENT,
            percent.coerceIn(WIDGET_TEXT_SCALE_MIN, WIDGET_TEXT_SCALE_MAX)
        )
    }

    fun getWidgetColorMode(context: Context): String {
        return when (store(context).getString(KEY_WIDGET_COLOR_MODE, WIDGET_COLOR_MODE_AUTO)) {
            WIDGET_COLOR_MODE_MANUAL -> WIDGET_COLOR_MODE_MANUAL
            else -> WIDGET_COLOR_MODE_AUTO
        }
    }

    fun setWidgetColorMode(context: Context, mode: String) {
        val normalized = if (mode == WIDGET_COLOR_MODE_MANUAL) {
            WIDGET_COLOR_MODE_MANUAL
        } else {
            WIDGET_COLOR_MODE_AUTO
        }
        store(context).setString(KEY_WIDGET_COLOR_MODE, normalized)
    }

    fun getWidgetManualColor(context: Context): Int {
        return store(context).getInt(KEY_WIDGET_MANUAL_COLOR, WIDGET_MANUAL_COLOR_DEFAULT)
    }

    fun setWidgetManualColor(context: Context, color: Int) {
        store(context).setInt(KEY_WIDGET_MANUAL_COLOR, color or (0xFF shl 24))
    }
}
