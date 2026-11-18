package com.andsi.airlyrics.floating

import android.content.Context
import com.andsi.airlyrics.widget.FixedLyricsPanelProvider

object FixedLyricsPanel {

    fun dispatchLyrics(
        context: Context,
        lyricLine: String?,
        trackTitle: String?
    ) {
        FixedLyricsPanelProvider.updateLyrics(
            context = context.applicationContext,
            lyricLine = lyricLine?.trim()?.takeIf { it.isNotEmpty() },
            trackTitle = trackTitle?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun forceSync(context: Context) {
        FixedLyricsPanelProvider.pushLatest(
            context = context.applicationContext,
            force = true
        )
    }
}