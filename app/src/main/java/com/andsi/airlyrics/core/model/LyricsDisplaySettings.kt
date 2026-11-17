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
    val panelEnabled: Boolean = false,
    val panelAlbumColors: Boolean = true,
    val panelTransparent: Boolean = true,
    val panelTextColor: Int = 0xFFF4F5F8.toInt(),
    val panelBackgroundColor: Int = 0xFF202127.toInt()
)
