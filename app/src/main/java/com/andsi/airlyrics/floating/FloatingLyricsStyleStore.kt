package com.andsi.airlyrics.floating

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FloatingSizePreset(
    val key: String,
    val label: String,
    val textSizeSp: Float,
    val maxWidthFraction: Float,
    val maxLines: Int
) {
    COMPACT(
        key = "compact",
        label = "Compact",
        textSizeSp = 15f,
        maxWidthFraction = 0.70f,
        maxLines = 2
    ),
    NORMAL(
        key = "normal",
        label = "Normal",
        textSizeSp = 18f,
        maxWidthFraction = 0.85f,
        maxLines = 3
    ),
    LARGE(
        key = "large",
        label = "Large",
        textSizeSp = 22f,
        maxWidthFraction = 0.95f,
        maxLines = 4
    ),
    EXTRA_LARGE(
        key = "extra_large",
        label = "Extra Large",
        textSizeSp = 26f,
        maxWidthFraction = 1.00f,
        maxLines = 6
    );

    fun next(): FloatingSizePreset {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromKey(key: String?): FloatingSizePreset =
            entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

data class SystemSettingsState(
    val lockScreenCardEnabled: Boolean = true,
    val floatingOverlayEnabled: Boolean = true,
    val sizePreset: FloatingSizePreset = FloatingSizePreset.NORMAL
)

class FloatingLyricsStyleStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "airlyrics_style_prefs_v4"
        private const val KEY_SIZE_PRESET = "floating_size_preset"
        private const val KEY_LOCKSCREEN_CARD = "lockscreen_card_enabled"
        private const val KEY_FLOATING_OVERLAY = "floating_overlay_enabled"
        private const val KEY_POS_X = "floating_pos_x"
        private const val KEY_POS_Y = "floating_pos_y"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SystemSettingsState> = _state.asStateFlow()

    private fun readState(): SystemSettingsState = SystemSettingsState(
        lockScreenCardEnabled = prefs.getBoolean(KEY_LOCKSCREEN_CARD, true),
        floatingOverlayEnabled = prefs.getBoolean(KEY_FLOATING_OVERLAY, true),
        sizePreset = FloatingSizePreset.fromKey(prefs.getString(KEY_SIZE_PRESET, null))
    )

    fun getSizePreset(): FloatingSizePreset = _state.value.sizePreset

    fun setSizePreset(preset: FloatingSizePreset) {
        prefs.edit().putString(KEY_SIZE_PRESET, preset.key).apply()
        _state.value = _state.value.copy(sizePreset = preset)
    }

    fun cycleSizePreset(): FloatingSizePreset {
        val next = getSizePreset().next()
        setSizePreset(next)
        return next
    }

    fun setLockScreenCardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKSCREEN_CARD, enabled).apply()
        _state.value = _state.value.copy(lockScreenCardEnabled = enabled)
    }

    fun setFloatingOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_OVERLAY, enabled).apply()
        _state.value = _state.value.copy(floatingOverlayEnabled = enabled)
    }

    fun saveWindowPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }

    fun loadWindowPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val x = prefs.getInt(KEY_POS_X, defaultX)
        val y = prefs.getInt(KEY_POS_Y, defaultY)
        return x to y
    }
}