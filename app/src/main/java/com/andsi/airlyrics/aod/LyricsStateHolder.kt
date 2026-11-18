package com.andsi.airlyrics.aod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveLyricsState(
    val trackTitle: String = "",
    val artist: String = "",
    val currentLine: String = "",
    val nextLine: String = "",
    val isPlaying: Boolean = false
)

object LyricsStateHolder {
    private val _lyricsState = MutableStateFlow(LiveLyricsState())
    val lyricsState: StateFlow<LiveLyricsState> = _lyricsState.asStateFlow()

    @Volatile
    var isAodActivityVisible: Boolean = false

    fun updateLyrics(
        trackTitle: String,
        artist: String,
        currentLine: String,
        nextLine: String = "",
        isPlaying: Boolean
    ) {
        _lyricsState.value = LiveLyricsState(
            trackTitle = trackTitle,
            artist = artist,
            currentLine = currentLine,
            nextLine = nextLine,
            isPlaying = isPlaying
        )
    }

    fun updateLyrics(state: LiveLyricsState) {
        _lyricsState.value = state
    }
}
