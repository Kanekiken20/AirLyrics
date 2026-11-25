package com.andsi.airlyrics.floating

import android.os.SystemClock
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.isInteractiveLyricsScreen(): Boolean =
    screenClass == LyricsScreenClass.UNLOCKED || screenClass == LyricsScreenClass.LOCKED_INTERACTIVE

private fun FloatingLyricsService.isAmbientLyricsScreen(): Boolean =
    screenClass == LyricsScreenClass.DOZE || screenClass == LyricsScreenClass.DOZE_SUSPEND

internal fun FloatingLyricsService.shouldPresentLockScreenLyrics(): Boolean =
    !sourceLost && isInteractiveLyricsScreen() &&
        QuickFloatingStore.isDesiredVisible(this) && LyricsDisplaySettingsStore.get(this).enabled

internal fun FloatingLyricsService.shouldPresentInAppPreview(): Boolean =
    !sourceLost && screenClass == LyricsScreenClass.UNLOCKED && FloatingInAppPreview.appVisible &&
        QuickFloatingStore.isDesiredVisible(this)

internal fun FloatingLyricsService.shouldPresentFixedLyricsPanel(): Boolean =
    !sourceLost && fixedLyricsPanel.hasInstances && QuickFloatingStore.isDesiredVisible(this) &&
        LyricsDisplaySettingsStore.get(this).let {
            it.panelEnabled && !it.panelDiagnostic && it.privacy == LockScreenLyricPrivacy.PUBLIC &&
                (isInteractiveLyricsScreen() || it.panelAod && isAmbientLyricsScreen())
        }

internal fun FloatingLyricsService.shouldPresentAodScreen(): Boolean =
    !sourceLost && QuickFloatingStore.isDesiredVisible(this) && !AodLyricsHost.dismissedForLockCycle &&
        LyricsDisplaySettingsStore.get(this).let {
            it.panelEnabled && it.panelAod && it.panelAodScreen &&
                it.privacy == LockScreenLyricPrivacy.PUBLIC
        } && (AodLyricsHost.activityVisible || AodLyricsHost.launchedForLockCycle || !isInteractiveLyricsScreen())

internal fun FloatingLyricsService.shouldUsePanelArtwork(): Boolean =
    isInteractiveLyricsScreen() && shouldPresentFixedLyricsPanel() &&
        LyricsDisplaySettingsStore.get(this).panelAlbumColors

internal fun FloatingLyricsService.clearLyricSurfaces() {
    lockScreenNotification.clear()
    fixedLyricsPanel.clear()
    panelArtwork.clear()
    AodLyricsHost.dismiss()
    FloatingInAppPreview.publish(null)
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
    val transitionToSleep =
        (previousScreen == LyricsScreenClass.UNLOCKED || previousScreen == LyricsScreenClass.LOCKED_INTERACTIVE) &&
            !isInteractiveLyricsScreen()
    if (screenClass == LyricsScreenClass.UNLOCKED) {
        AodLyricsHost.resetLockCycle()
    } else if (!isInteractiveLyricsScreen()) {
        if (AodLyricsHost.activityVisible && transitionToSleep) {
            // User explicitly pressed Power while the AOD screen was open; allow normal sleep.
            AodLyricsHost.dismissedForLockCycle = true
            AodLyricsHost.dismiss()
        } else if (shouldPresentAodScreen() && !currentMedia.isEmpty && currentMedia.isPlaying) {
            AodLyricsHost.launchIfAllowed(this)
        }
    }

    val showWindow = screenClass == LyricsScreenClass.UNLOCKED && !FloatingInAppPreview.appVisible
    windowController.setPresentationVisible(showWindow)
    presentationAnimationAllowed = decision.animate && showWindow
    renderer.setRenderingState(decision.drawOverlay && showWindow, presentationAnimationAllowed)

    if (!isInteractiveLyricsScreen()) {
        // A screen transition must not create network work for ambient visuals.
        if (activeLyricsLookupRequestKey != null) {
            pendingLyricsMedia = currentMedia.takeUnless { it.isEmpty }
            activeLyricsLookupRequestKey = null
            lyricsLookupRunner.cancelActive()
        }
        syncHandler.removeCallbacks(mediaRestoreRunnable)
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
    }
    // Ambient uses the same line deadlines while the process is awake. A Handler never requests a wakeup.
    scheduleNextLyricsSync()
    if (shouldUsePanelArtwork() && !currentMedia.isEmpty) panelArtwork.update(currentMedia)
    publishLockScreenLine()
}

internal fun FloatingLyricsService.publishLockScreenLine() {
    val interactive = isInteractiveLyricsScreen()
    val showPanel = shouldPresentFixedLyricsPanel() && !currentMedia.isEmpty
    if (!showPanel || !shouldUsePanelArtwork()) panelArtwork.clear()
    val showNotification = shouldPresentLockScreenLyrics() && !currentMedia.isEmpty
    val showPreview = shouldPresentInAppPreview() && !currentMedia.isEmpty
    val showAodScreen = shouldPresentAodScreen() && !currentMedia.isEmpty && currentMedia.isPlaying
    val snapshot = if (showPanel || showNotification || showPreview || showAodScreen) renderer.displaySnapshot() else null
    FloatingInAppPreview.publish(snapshot?.preview.takeIf { showPreview })
    val content = snapshot?.let {
        it.currentLine?.let { line ->
            LockScreenLyricContent(sourceGeneration, currentMedia.title, currentMedia.artist, line, it.playing,
                it.attribution, it.previousLine, it.nextLine)
        }
    }
    if (showAodScreen && (AodLyricsHost.activityVisible || AodLyricsHost.launchedForLockCycle)) {
        val settings = LyricsDisplaySettingsStore.get(this)
        val attribution = content?.attribution.orEmpty()
        val hero = content?.line?.let {
            if (attribution.isEmpty()) it else it.removeSuffix("\n$attribution")
        }
        AodLyricsHost.publish(
            AodLyricsState(
                active = true,
                previousLine = content?.previousLine,
                currentLine = hero,
                nextLine = content?.nextLine,
                title = currentMedia.title,
                artist = currentMedia.artist,
                playing = currentMedia.isPlaying,
                attribution = attribution.takeIf { it.isNotBlank() },
                textSizeSp = settings.panelTextSizeSp,
                typeface = settings.panelTypeface,
                minimalMotion = settings.minimalMotion
            )
        )
    } else {
        AodLyricsHost.dismiss()
    }
    val ambient = isAmbientLyricsScreen()
    val posted = fixedLyricsPanel.update(content.takeIf { showPanel }, interactive, panelArtwork.colors, ambient)
    if (BuildConfig.DEBUG && posted && ambient) {
        Log.i("AirLyricsPanel", "Ambient update state=$screenClass generation=$sourceGeneration " +
            "lineIndex=${snapshot?.lineIndex} elapsed=${SystemClock.elapsedRealtime()}")
    }
    if (!showNotification) {
        lockScreenNotification.clear()
        return
    }
    lockScreenNotification.update(content, visible = true)
}
