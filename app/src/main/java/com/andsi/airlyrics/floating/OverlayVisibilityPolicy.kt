package com.andsi.airlyrics.floating

/**
 * Pure state machine deciding floating overlay vs lock-screen card visibility.
 */
object OverlayVisibilityPolicy {
    fun shouldShowFloatingOverlay(
        overlayEnabled: Boolean,
        canDrawOverlays: Boolean,
        isScreenInteractive: Boolean,
        isKeyguardLocked: Boolean,
        hasActivePlayback: Boolean
    ): Boolean = overlayEnabled && canDrawOverlays && isScreenInteractive && !isKeyguardLocked && hasActivePlayback

    fun shouldShowLockScreenCard(
        lockScreenCardEnabled: Boolean,
        isPlaying: Boolean,
        currentLine: String,
        trackTitle: String
    ): Boolean {
        if (!lockScreenCardEnabled) return false
        if (!isPlaying) return false
        return currentLine.isNotBlank() || trackTitle.isNotBlank()
    }
}
