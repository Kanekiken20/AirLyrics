package com.andsi.airlyrics.floating

import com.andsi.airlyrics.core.model.LyricPreview

/** Read-only shared timeline output, independent of any View or System UI surface. */
internal data class LyricsLineSnapshot(
    val currentLine: String?,
    val lineIndex: Int?,
    val effectivePositionMs: Long,
    val playing: Boolean,
    val attribution: String? = null,
    val previousLine: String? = null,
    val nextLine: String? = null,
    val preview: LyricPreview? = null
)
