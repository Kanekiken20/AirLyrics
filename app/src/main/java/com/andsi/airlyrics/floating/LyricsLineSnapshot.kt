package com.andsi.airlyrics.floating

/** Read-only shared timeline output, independent of any View or System UI surface. */
internal data class LyricsLineSnapshot(
    val currentLine: String?,
    val lineIndex: Int?,
    val effectivePositionMs: Long,
    val playing: Boolean,
    val attribution: String? = null
)
