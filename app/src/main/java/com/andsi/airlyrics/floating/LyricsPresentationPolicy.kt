package com.andsi.airlyrics.floating

/** Public display states are deliberately distinct: SCREEN_OFF does not establish AOD support. */
internal enum class LyricsScreenClass { UNLOCKED, LOCKED_INTERACTIVE, DOZE, DOZE_SUSPEND, OFF, UNKNOWN }

internal enum class LyricsPresentationMode { EXPANDED, EDIT, DOCKED, LOCKSCREEN, AMBIENT_INDICATOR, HIDDEN }

internal data class LyricsPresentationDecision(
    val mode: LyricsPresentationMode,
    val drawOverlay: Boolean = false,
    val updateLockScreen: Boolean = false,
    val animate: Boolean = false
)

/** Pure, serialized by the service main looper. No ambient mode owns an application frame clock. */
internal object LyricsPresentationPolicy {
    fun decide(
        screen: LyricsScreenClass,
        desiredVisible: Boolean,
        overlayVisible: Boolean,
        docked: Boolean,
        editing: Boolean,
        lockScreenEnabled: Boolean,
        motionAllowed: Boolean
    ): LyricsPresentationDecision {
        if (!desiredVisible) return LyricsPresentationDecision(LyricsPresentationMode.HIDDEN)
        return when (screen) {
            LyricsScreenClass.UNLOCKED -> when {
                !overlayVisible -> LyricsPresentationDecision(LyricsPresentationMode.HIDDEN)
                docked -> LyricsPresentationDecision(LyricsPresentationMode.DOCKED)
                else -> LyricsPresentationDecision(
                    if (editing) LyricsPresentationMode.EDIT else LyricsPresentationMode.EXPANDED,
                    drawOverlay = true, animate = motionAllowed
                )
            }
            LyricsScreenClass.LOCKED_INTERACTIVE -> LyricsPresentationDecision(
                if (lockScreenEnabled) LyricsPresentationMode.LOCKSCREEN else LyricsPresentationMode.HIDDEN,
                updateLockScreen = lockScreenEnabled
            )
            LyricsScreenClass.DOZE, LyricsScreenClass.DOZE_SUSPEND, LyricsScreenClass.OFF ->
                LyricsPresentationDecision(LyricsPresentationMode.AMBIENT_INDICATOR)
            LyricsScreenClass.UNKNOWN -> LyricsPresentationDecision(LyricsPresentationMode.HIDDEN)
        }
    }
}
