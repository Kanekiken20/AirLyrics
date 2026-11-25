package com.andsi.airlyrics.core.model

internal data class LyricPreviewLine(val original: String, val translation: String)

internal data class LyricPreview(
    val current: LyricPreviewLine,
    val previous: LyricPreviewLine?,
    val next: LyricPreviewLine?
)
