package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
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

    fun get(context: Context): LyricsDisplaySettings {
        val preferences = prefs(context, PREFS_NAME)
        return LyricsDisplaySettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            privacy = LockScreenLyricPrivacy.fromKey(preferences.getString(KEY_PRIVACY)),
            showMetadata = preferences.getBoolean(KEY_METADATA, true),
            compact = preferences.getBoolean(KEY_COMPACT, false),
            minimalMotion = isMinimalMotion(context)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context, PREFS_NAME).setBoolean(KEY_ENABLED, enabled)

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
