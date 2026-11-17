package com.andsi.airlyrics.core.model

/** Lock-screen disclosure is independent of the floating overlay's visibility. */
enum class LockScreenLyricPrivacy(val key: String) {
    PRIVATE("private"),
    PUBLIC("public"),
    HIDDEN("hidden");

    companion object {
        fun fromKey(key: String?): LockScreenLyricPrivacy =
            entries.firstOrNull { it.key == key } ?: PRIVATE
    }
}

data class LyricsDisplaySettings(
    val enabled: Boolean = false,
    val privacy: LockScreenLyricPrivacy = LockScreenLyricPrivacy.PRIVATE,
    val showMetadata: Boolean = true,
    val compact: Boolean = false,
    val minimalMotion: Boolean = false,
    val panelEnabled: Boolean = false
)
