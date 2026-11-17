package com.andsi.airlyrics.floating

import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.isInteractiveLyricsScreen(): Boolean =
    screenClass == LyricsScreenClass.UNLOCKED || screenClass == LyricsScreenClass.LOCKED_INTERACTIVE

internal fun FloatingLyricsService.shouldPresentLockScreenLyrics(): Boolean =
    !sourceLost && isInteractiveLyricsScreen() &&
        QuickFloatingStore.isDesiredVisible(this) && LyricsDisplaySettingsStore.get(this).enabled

/** The existing service is the sole scheduler and all events are serialized on its main looper. */
internal fun FloatingLyricsService.refreshLyricsPresentation() {
    if (destroyed || !isWindowControllerReady()) return
    val previousScreen = screenClass
    screenClass = screenMonitor?.screenClass() ?: screenClass
    val settings = LyricsDisplaySettingsStore.get(this)
    val decision = LyricsPresentationPolicy.decide(
        screenClass, QuickFloatingStore.isDesiredVisible(this), windowController.isVisible,
        windowController.isDocked, windowController.isEditing, settings.enabled,
        motionAllowed = !settings.minimalMotion && screenMonitor?.allowsMotion() == true
    )
    windowController.setPresentationVisible(screenClass == LyricsScreenClass.UNLOCKED)
    presentationAnimationAllowed = decision.animate
    renderer.setRenderingState(decision.drawOverlay, decision.animate)

    if (!isInteractiveLyricsScreen()) {
        // A screen transition must not create network work for ambient visuals.
        if (activeLyricsLookupRequestKey != null) {
            pendingLyricsMedia = currentMedia.takeUnless { it.isEmpty }
            activeLyricsLookupRequestKey = null
            lyricsLookupRunner.cancelActive()
        }
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        stopLyricsSync()
        stopSelectedMediaObservation()
    } else {
        if (previousScreen != screenClass) {
            // Reconcile position immediately on wake; elapsed time includes time in suspend.
            refreshSelectedCurrentMediaInfo()
        }
        pendingLyricsMedia?.takeIf { QuickFloatingStore.isDesiredVisible(this) &&
            it.playbackLyricsKey() == currentMedia.playbackLyricsKey() }
            ?.let {
                pendingLyricsMedia = null
                val key = currentMedia.lyricsLookupRequestKey()
                activeLyricsLookupRequestKey = key
                loadLyricsForSong(currentMedia, key)
            }
        if (windowController.isContentVisible) renderer.refresh()
        scheduleSelectedCurrentMediaInfoRefresh()
        scheduleNextLyricsSync()
    }
    publishLockScreenLine()
}

internal fun FloatingLyricsService.publishLockScreenLine() {
    if (!shouldPresentLockScreenLyrics() || currentMedia.isEmpty) {
        lockScreenNotification.clear()
        return
    }
    val snapshot = renderer.displaySnapshot()
    lockScreenNotification.update(snapshot.currentLine?.let {
        LockScreenLyricContent(sourceGeneration, currentMedia.title, currentMedia.artist, it, snapshot.playing)
    }, visible = true)
}
