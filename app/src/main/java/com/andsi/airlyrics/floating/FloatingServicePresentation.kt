package com.andsi.airlyrics.floating

import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.isInteractiveLyricsScreen(): Boolean =
    screenClass == LyricsScreenClass.UNLOCKED || screenClass == LyricsScreenClass.LOCKED_INTERACTIVE

internal fun FloatingLyricsService.shouldPresentLockScreenLyrics(): Boolean =
    !sourceLost && isInteractiveLyricsScreen() &&
        QuickFloatingStore.isDesiredVisible(this) && LyricsDisplaySettingsStore.get(this).enabled

internal fun FloatingLyricsService.shouldPresentFixedLyricsPanel(): Boolean =
    !sourceLost && isInteractiveLyricsScreen() && QuickFloatingStore.isDesiredVisible(this) &&
        LyricsDisplaySettingsStore.get(this).let {
            it.panelEnabled && it.privacy == com.andsi.airlyrics.core.model.LockScreenLyricPrivacy.PUBLIC
        } && fixedLyricsPanel.hasInstances

internal fun FloatingLyricsService.shouldUsePanelArtwork(): Boolean =
    shouldPresentFixedLyricsPanel() && LyricsDisplaySettingsStore.get(this).panelAlbumColors

internal fun FloatingLyricsService.clearLyricSurfaces() {
    lockScreenNotification.clear()
    fixedLyricsPanel.clear()
    panelArtwork.clear()
}

/** The existing service is the sole scheduler and all events are serialized on its main looper. */
internal fun FloatingLyricsService.refreshLyricsPresentation() {
    if (destroyed || !isWindowControllerReady()) return
    val previousScreen = screenClass
    screenClass = screenMonitor?.screenClass() ?: screenClass
    val settings = LyricsDisplaySettingsStore.get(this)
    fixedLyricsPanel.refreshInstances()
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
    if (shouldUsePanelArtwork() && !currentMedia.isEmpty) panelArtwork.update(currentMedia)
    publishLockScreenLine()
}

internal fun FloatingLyricsService.publishLockScreenLine() {
    val interactive = isInteractiveLyricsScreen()
    val showPanel = shouldPresentFixedLyricsPanel() && !currentMedia.isEmpty
    if (!showPanel || !shouldUsePanelArtwork()) panelArtwork.clear()
    val showNotification = shouldPresentLockScreenLyrics() && !currentMedia.isEmpty
    val content = if (showPanel || showNotification) renderer.displaySnapshot().let { snapshot ->
        snapshot.currentLine?.let {
            LockScreenLyricContent(sourceGeneration, currentMedia.title, currentMedia.artist, it, snapshot.playing,
                snapshot.attribution)
        }
    } else null
    fixedLyricsPanel.update(content.takeIf { showPanel }, interactive, panelArtwork.colors)
    if (!showNotification) {
        lockScreenNotification.clear()
        return
    }
    lockScreenNotification.update(content, visible = true)
}
