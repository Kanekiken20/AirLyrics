package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import com.andsi.airlyrics.core.prefs.prefs

/** New opt-in presentation preferences; existing floating/style keys are untouched. */
object LyricsDisplaySettingsStore {
    private const val PREFS_NAME = "lyrics_display"
    private const val KEY_ENABLED = "lock_screen_enabled"
    private const val KEY_PRIVACY = "lock_screen_privacy"
    private const val KEY_METADATA = "show_metadata"
    private const val KEY_COMPACT = "compact"
    private const val KEY_MINIMAL_MOTION = "minimal_motion"
    private const val KEY_PANEL_ENABLED = "fixed_panel_enabled"

    fun get(context: Context): LyricsDisplaySettings {
        val preferences = prefs(context, PREFS_NAME)
        return LyricsDisplaySettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            privacy = LockScreenLyricPrivacy.fromKey(preferences.getString(KEY_PRIVACY)),
            showMetadata = preferences.getBoolean(KEY_METADATA, true),
            compact = preferences.getBoolean(KEY_COMPACT, false),
            minimalMotion = isMinimalMotion(context),
            panelEnabled = preferences.getBoolean(KEY_PANEL_ENABLED, false),
            panelAlbumColors = preferences.getBoolean("panel_album_colors", true),
            panelTransparent = preferences.getBoolean("panel_transparent", false),
            panelTextColor = preferences.getInt("panel_text_color", 0xFFF4F5F8.toInt()) or 0xFF000000.toInt(),
            panelBackgroundColor = preferences.getInt("panel_background_color", 0xFF202127.toInt()) or 0xFF000000.toInt(),
            panelTextSizeSp = preferences.getInt(
                "panel_text_size", LyricsDisplaySettings.DEFAULT_PANEL_TEXT_SIZE_SP
            ).coerceIn(LyricsDisplaySettings.MIN_PANEL_TEXT_SIZE_SP, LyricsDisplaySettings.MAX_PANEL_TEXT_SIZE_SP),
            panelTypeface = LyricPanelTypeface.entries.firstOrNull {
                it.name == preferences.getString("panel_typeface")
            } ?: LyricPanelTypeface.SYSTEM,
            panelDiagnostic = BuildConfig.DEBUG && preferences.getBoolean("panel_diagnostic", false),
            panelAod = preferences.getBoolean("panel_aod", false),
            panelAodScreen = preferences.getBoolean("panel_aod_screen", false)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_ENABLED, enabled)

    fun setPanelEnabled(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_PANEL_ENABLED, enabled)

    fun enablePanelOnFirstPlacement(context: Context) {
        // Adding a widget expresses intent to enable it. Preserve an explicit off choice,
        // and never change the separate public/private disclosure preference.
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains(KEY_PANEL_ENABLED)) {
            setPanelEnabled(context, true)
            if (!preferences.contains("panel_aod")) {
                setPanelAod(context, true)
            }
        }
    }

    fun setPanelAlbumColors(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean("panel_album_colors", enabled)
    fun setPanelTransparent(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean("panel_transparent", enabled)
    fun setPanelColor(context: Context, text: Boolean, color: Int) =
        prefs(context, PREFS_NAME).setInt(if (text) "panel_text_color" else "panel_background_color",
            color or 0xFF000000.toInt())
    fun setPanelTextSize(context: Context, sizeSp: Int) =
        prefs(context, PREFS_NAME).setInt("panel_text_size", sizeSp.coerceIn(
            LyricsDisplaySettings.MIN_PANEL_TEXT_SIZE_SP, LyricsDisplaySettings.MAX_PANEL_TEXT_SIZE_SP
        ))
    fun setPanelTypeface(context: Context, typeface: LyricPanelTypeface) =
        prefs(context, PREFS_NAME).setString("panel_typeface", typeface.name)
    fun setPanelDiagnostic(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean("panel_diagnostic", enabled)
    fun setPanelAod(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean("panel_aod", enabled)

    fun setPanelAodScreen(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean("panel_aod_screen", enabled)

    fun setPrivacy(context: Context, privacy: LockScreenLyricPrivacy) =
        prefs(context, PREFS_NAME).setString(KEY_PRIVACY, privacy.key)

    fun setShowMetadata(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_METADATA, enabled)

    fun setCompact(context: Context, compact: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_COMPACT, compact)

    fun setMinimalMotion(context: Context, minimalMotion: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_MINIMAL_MOTION, minimalMotion)

    fun isMinimalMotion(context: Context): Boolean =
        prefs(context, PREFS_NAME).getBoolean(KEY_MINIMAL_MOTION, false)
}
