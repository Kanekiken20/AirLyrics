package com.andsi.airlyrics.floating

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display

enum class DisplayPowerMode {
    INTERACTIVE_ON,
    DOZE,
    DOZE_SUSPEND,
    OFF
}

data class PlaybackSyncSnapshot(
    val trackTitle: String = "",
    val artist: String = "",
    val currentLyricLine: String = "",
    val nextLyricLine: String = "",
    val wordProgress: Float = 1f,
    val isPlaying: Boolean = false
)

class FloatingServicePresentation(
    context: Context,
    private val onDisplayModeChanged: (DisplayPowerMode) -> Unit
) {

    private val displayManager =
        context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var displayMode: DisplayPowerMode = resolveDisplayMode()
        private set

    @Volatile
    var snapshot: PlaybackSyncSnapshot = PlaybackSyncSnapshot()
        private set

    private var isListening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val nextMode = resolveDisplayMode()
            if (nextMode != displayMode) {
                displayMode = nextMode
                onDisplayModeChanged(nextMode)
            }
        }
    }

    fun start() {
        if (isListening) return
        displayMode = resolveDisplayMode()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        isListening = true
    }

    fun stop() {
        if (!isListening) return
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        isListening = false
    }

    fun updatePlaybackSnapshot(
        lyricLine: String?,
        nextLine: String?,
        trackTitle: String?,
        artist: String?,
        wordProgress: Float,
        isPlaying: Boolean?
    ): Boolean {
        val prev = snapshot
        val updated = prev.copy(
            currentLyricLine = lyricLine?.trim() ?: prev.currentLyricLine,
            nextLyricLine = nextLine?.trim() ?: prev.nextLyricLine,
            trackTitle = trackTitle?.trim() ?: prev.trackTitle,
            artist = artist?.trim() ?: prev.artist,
            wordProgress = wordProgress.coerceIn(0f, 1f),
            isPlaying = isPlaying ?: prev.isPlaying
        )
        snapshot = updated
        return updated.currentLyricLine != prev.currentLyricLine
    }

    fun isDisplayAwake(): Boolean =
        displayMode == DisplayPowerMode.INTERACTIVE_ON && powerManager.isInteractive

    fun resolveDisplayMode(): DisplayPowerMode {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val state = display?.state ?: if (powerManager.isInteractive) Display.STATE_ON else Display.STATE_OFF
        return when (state) {
            Display.STATE_DOZE -> DisplayPowerMode.DOZE
            Display.STATE_DOZE_SUSPEND -> DisplayPowerMode.DOZE_SUSPEND
            Display.STATE_OFF -> DisplayPowerMode.OFF
            else -> if (powerManager.isInteractive) {
                DisplayPowerMode.INTERACTIVE_ON
            } else {
                DisplayPowerMode.DOZE
            }
        }
    }
}