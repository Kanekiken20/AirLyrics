package com.andsi.airlyrics.floating

import com.andsi.airlyrics.lyrics.LyricsSearchCoordinator
import com.andsi.airlyrics.core.model.SongIdentity
import android.app.Service
import android.app.KeyguardManager
import android.os.PowerManager
import com.andsi.airlyrics.lockscreen.AirLyricsAodWidgetProvider
import com.andsi.airlyrics.lockscreen.AirLyricsDockWidgetProvider
import com.andsi.airlyrics.lockscreen.LockScreenLyricsSnapshot
import com.andsi.airlyrics.lockscreen.LockScreenNotificationCardPublisher
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.widget.FixedLyricsPanelProvider
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.displayscope.DisplayScopeBlockReason
import com.andsi.airlyrics.displayscope.DisplayScopeMonitor
import com.andsi.airlyrics.feedback.AirFeedback
import com.andsi.airlyrics.feedback.ToastAirFeedback
import com.andsi.airlyrics.i18n.LocalizedServiceContext
import com.andsi.airlyrics.i18n.LanguageChangedBroadcast
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.media.CurrentMediaBroadcast
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

open class FloatingLyricsService : Service() {
    internal lateinit var windowController: FloatingLyricsWindow
    private val feedbackDelegate = lazy(LazyThreadSafetyMode.NONE) {
        ToastAirFeedback(
            context = this,
            canShow = { !AppSettingsStore.areStatusPopupsMuted(this) }
        )
    }
    internal val feedback: AirFeedback by feedbackDelegate

    internal val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    internal fun isWindowControllerReady(): Boolean = ::windowController.isInitialized

    internal val renderer = FloatingLyricsRenderer(
        textViewProvider = { lyricsView },
        contentModeProvider = { LyricsSettingsStore.getContentDisplayMode(this) },
        scriptPreferenceProvider = { LyricsSettingsStore.getScriptPreference(this) },
        lineModeProvider = { LyricsSettingsStore.getLineDisplayMode(this) },
        switchAnimationModeProvider = { LyricsSettingsStore.getSwitchAnimationMode(this) },
        wordByWordAnimationModeProvider = { LyricsSettingsStore.getWordByWordAnimationMode(this) },
        wordByWordLyricsEnabledProvider = { LyricsSettingsStore.isWordByWordLyricsEnabled(this) },
        wordByWordHighlightColorProvider = {
            val style = FloatingLyricsStyleStore.getStyle(this)
            AirColorUtils.multiplyAlpha(
                style.wordByWordHighlightColor,
                Color.alpha(style.textColor)
            )
        },
        noTranslationTextProvider = { getString(R.string.ui_no_translation_for_this_lyric) },
        durationMsProvider = { currentMedia.durationMs },
        glowStyleProvider = { FloatingLyricsStyleStore.getGlowStyle(this) }
    )
    internal val syncHandler = Handler(Looper.getMainLooper())
    internal val lyricsLookupRunner: LyricsLookupRunner by lazy(LazyThreadSafetyMode.NONE) {
        createLyricsLookupRunner()
    }

    protected open fun createLyricsLookupRunner(): LyricsLookupRunner {
        return LyricsLookupRunner(threadNamePrefix = "AirLyrics-LyricsRepository")
    }

    internal open fun lookupLyricsForMedia(
        media: CurrentMediaInfo,
        settings: LyricsSettings,
        cancellationToken: LyricsLookupCancellationToken
    ): Result<LyricsProviderResult?> {
        return LyricsRepository.findLyrics(
            context = this,
            settings = settings,
            title = media.title,
            artist = media.artist,
            album = media.album,
            durationMs = media.durationMs,
            spotifyTrackId = media.spotifyTrackId,
            cancellationToken = cancellationToken,
            onInterimResult = { interim ->
                syncHandler.post {
                    if (!cancellationToken.isCancellationRequested && activeLyricsLookupRequestKey?.value == cancellationToken.requestKey) {
                        applyLyricsResult(Result.success(interim), media)
                    }
                }
            }
        )
    }

    private var manualSearchObservation: AutoCloseable? = null
    internal var currentMedia: CurrentMediaInfo = CurrentMediaInfo.Empty
    internal var lastPlaybackLyricsKey: PlaybackLyricsKey? = null
    internal var lastLookupSpotifyTrackId: String? = null
    internal var automaticOnlineLookupSuppressedSong: SongIdentity? = null
    internal var activeLyricsLookupRequestKey: LyricsLookupRequestKey? = null
    internal var selectedSourcePackage: String? = null
    internal val activeAutoHideReasons: MutableSet<AutoHideReason> = mutableSetOf()
    internal var lyricsAvailability = LyricsAvailability.UNKNOWN
    internal var lyricsAvailabilityKey: PlaybackLyricsKey? = null
    internal var autoHiddenForDisplayScope = false
    internal var displayScopeBlockReason: DisplayScopeBlockReason? = null
    internal var displayScopeVisiblePackages: Set<String> = emptySet()
    internal var displayScopeUsageAccessGranted = false
    internal var displayScopeVisibilitySnapshotAvailable = false
    internal var displayScopeObservationActive = false
    internal var displayScopeMonitor: DisplayScopeMonitor? = null
    internal var pauseAutoHideSuppressedByUser = false
    internal var lyricsUnavailableAutoHideSuppressedKey: PlaybackLyricsKey? = null
    internal var pauseAutoHidePending = false
    internal var unavailableLyricsAutoHidePending = false
    internal var mediaRestoreAttempt = 0
    internal val mediaSnapshotGate = MediaSnapshotGate()

    internal val syncRunnable = object : Runnable {
        override fun run() {
            if (!shouldSyncLyrics()) return

            renderer.tick()
            pushLyricsToLockScreenWidget(renderer.lastRenderedText, currentMedia.title)
            scheduleNextLyricsSync()
        }
    }

    internal val mediaRestoreRunnable = Runnable {
        restoreCurrentMediaOrRetry()
    }

    internal val pauseAutoHideRunnable = Runnable {
        applyScheduledPauseAutoHide()
    }

    internal val unavailableLyricsAutoHideRunnable = Runnable {
        applyScheduledUnavailableLyricsAutoHide()
    }

    internal val currentMediaRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSelectedCurrentMediaInfo()
            if (shouldObserveSelectedMedia()) {
                syncHandler.postDelayed(this, CURRENT_MEDIA_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            CurrentMediaBroadcast.readMediaSourceLost(intent)?.let { sourcePackage ->
                handleMediaSourceLost(sourcePackage)
                return
            }

            CurrentMediaBroadcast.readMediaUpdate(intent)?.let(::applyCurrentMediaInfo)
        }
    }

    private val lyricsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LyricsChangedBroadcast.readChange(intent)?.let(::handleLyricsChanged)
        }
    }

    private var localizedContentLanguageTags: String? = null

    private var isScreenOnWidgetReceiverRegistered = false
    private lateinit var lockScreenPublisher: LockScreenNotificationCardPublisher

    internal fun isDisplayInteractive(): Boolean {
        return getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    internal fun shouldObserveExternalSurfaces(): Boolean {
        return AppSettingsStore.isLockScreenCardEnabled(this) ||
            (AppSettingsStore.isAodLyricsEnabled(this) &&
                (AirLyricsAodWidgetProvider.hasActiveWidgets(this) ||
                    AirLyricsDockWidgetProvider.hasActiveWidgets(this)))
    }

    internal fun shouldSyncExternalSurfaces(): Boolean {
        return !currentMedia.isEmpty && currentMedia.isPlaying && shouldObserveExternalSurfaces()
    }

    private val screenOnWidgetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: this@FloatingLyricsService

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    updateAodAndLockScreen(isInteractive = false, isLocked = true, forceImmediate = true)
                    scheduleNextLyricsSync()
                }

                Intent.ACTION_SCREEN_ON -> {
                    val km = ctx.getSystemService(KeyguardManager::class.java)
                    val isLocked = km != null && km.isKeyguardLocked
                    updateAodAndLockScreen(isInteractive = true, isLocked = isLocked, forceImmediate = true)
                    scheduleNextLyricsSync()
                    FixedLyricsPanelProvider.pushLatest(this@FloatingLyricsService, force = true)
                }

                Intent.ACTION_USER_PRESENT -> {
                    updateAodAndLockScreen(isInteractive = true, isLocked = false, forceImmediate = true)
                    scheduleNextLyricsSync()
                    FixedLyricsPanelProvider.pushLatest(this@FloatingLyricsService, force = true)
                }
            }
        }
    }

    private fun registerScreenOnWidgetRefresh() {
        if (isScreenOnWidgetReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenOnWidgetReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenOnWidgetReceiverRegistered = true
    }

    private fun unregisterScreenOnWidgetRefresh() {
        if (!isScreenOnWidgetReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenOnWidgetReceiver)
        }.also {
            isScreenOnWidgetReceiverRegistered = false
        }
    }

    private fun updateAodAndLockScreen(
        isInteractive: Boolean,
        isLocked: Boolean,
        forceImmediate: Boolean = false
    ) {
        val isAodWidgetEnabled = AppSettingsStore.isAodLyricsEnabled(this)
        val isLockCardEnabled = AppSettingsStore.isLockScreenCardEnabled(this)
        val isPlaying = currentMedia.isPlaying && !currentMedia.isEmpty
        val lineContext = renderer.currentLineContext()
        val resolvedCurrent = lineContext.currentLine.ifBlank { renderer.lastRenderedText.orEmpty() }

        val snapshot = LockScreenLyricsSnapshot(
            trackTitle = currentMedia.title,
            artistName = currentMedia.artist,
            previousLine = lineContext.previousLine,
            currentLine = resolvedCurrent,
            nextLine = lineContext.nextLine,
            isPlaying = isPlaying,
            positionMs = renderer.getEstimatedPositionMs(),
            durationMs = currentMedia.durationMs
        )

        // 1. Standalone One UI Lock Screen Notification Card (no native AOD media hijacking)
        val shouldShowLockCard = (isLocked || !isInteractive) &&
            isLockCardEnabled &&
            isPlaying &&
            (snapshot.currentLine.isNotBlank() || snapshot.trackTitle.isNotBlank())
        if (shouldShowLockCard) {
            lockScreenPublisher.publish(snapshot, force = forceImmediate)
        } else {
            lockScreenPublisher.clear()
        }

        // 2. Dedicated LockStar AMOLED AOD Widget (pure #000000 background, battery-conscious updates)
        if (isAodWidgetEnabled && !currentMedia.isEmpty) {
            AirLyricsAodWidgetProvider.updateAllWidgets(
                context = this,
                snapshot = snapshot,
                isInteractive = isInteractive,
                force = forceImmediate
            )
            AirLyricsDockWidgetProvider.updateAllWidgets(
                context = this,
                snapshot = snapshot,
                isInteractive = isInteractive,
                force = forceImmediate
            )
        } else {
            AirLyricsAodWidgetProvider.clearWidgets(this)
            AirLyricsDockWidgetProvider.clearWidgets(this)
        }
    }

    internal fun pushLyricsToLockScreenWidget(lyricLine: String?, trackTitle: String?) {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val isInteractive = pm?.isInteractive == true
        val isLocked = km?.isKeyguardLocked == true

        updateAodAndLockScreen(isInteractive = isInteractive, isLocked = isLocked)

        if (!AppSettingsStore.isAodLyricsEnabled(this)) {
            FixedLyricsPanelProvider.updateLyrics(
                context = this,
                lyricLine = null,
                trackTitle = null
            )
            return
        }
        FixedLyricsPanelProvider.updateLyrics(
            context = this,
            lyricLine = lyricLine,
            trackTitle = trackTitle
        )
    }

    private val languageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLocalizedContent()
        }
    }

    private fun refreshLocalizedContent() {
        val languageTags = resources.configuration.locales.toLanguageTags()
        if (languageTags == localizedContentLanguageTags) return
        localizedContentLanguageTags = languageTags
        if (feedbackDelegate.isInitialized()) feedback.dismiss()
        renderer.refresh()
        refreshQuickControls()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalizedServiceContext.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        localizedContentLanguageTags = resources.configuration.locales.toLanguageTags()

        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        displayScopeMonitor = DisplayScopeMonitor(this, ::applyDisplayScopeSnapshot)
        lockScreenPublisher = LockScreenNotificationCardPublisher(this)
        windowController = FloatingLyricsWindow(this) { visible ->
            if (!visible && !shouldObserveExternalSurfaces()) {
                syncHandler.removeCallbacks(mediaRestoreRunnable)
                mediaRestoreAttempt = 0
                stopSelectedMediaObservation()
                stopLyricsSync()
            }
            broadcastWindowVisibility(visible)
        }

        prepareDisplayScopeStateForServiceStart()
        startForeground(FloatingServiceNotification.NOTIFICATION_ID, FloatingServiceNotification.create(this, currentQuickControlState()))
        registerMediaReceiver()
        registerLyricsChangedReceiver()
        registerScreenOnWidgetRefresh()
        ContextCompat.registerReceiver(
            this,
            languageChangedReceiver,
            LanguageChangedBroadcast.filter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            handleCommand(intent, startId)
        }.onFailure {
            // Keep the overlay's current truth intact for non-window command failures.
            // WindowManager operations already hide/broadcast from FloatingLyricsWindow.
            refreshQuickControls(getString(R.string.ui_overlay_update_failed))
        }

        return START_STICKY
    }

    private fun registerMediaReceiver() {
        ContextCompat.registerReceiver(
            this,
            mediaReceiver,
            CurrentMediaBroadcast.mediaStatusFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerLyricsChangedReceiver() {
        manualSearchObservation = LyricsSearchCoordinator.observe { song ->
            syncHandler.post {
                if (manualSearchObservation != null && !currentMedia.isEmpty &&
                    SongIdentity(currentMedia.title, currentMedia.artist, currentMedia.album, currentMedia.durationMs).isSameSong(song)) {
                    if (LyricsSearchCoordinator.manualMode(song) == null) reloadCurrentLyrics()
                    else showManualLyricsSearch(song)
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            lyricsChangedReceiver,
            LyricsChangedBroadcast.lyricsChangedFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::windowController.isInitialized) {
            windowController.onConfigurationChanged()
            refreshLocalizedContent()
        }
    }

    override fun onDestroy() {
        manualSearchObservation?.close()
        manualSearchObservation = null
        if (feedbackDelegate.isInitialized()) feedback.dismiss()
        stopLyricsSync()
        renderer.clear()
        cancelPendingAutoHide()
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        stopSelectedMediaObservation()
        displayScopeMonitor?.close()
        displayScopeMonitor = null
        activeLyricsLookupRequestKey = null
        lyricsLookupRunner.shutdown()
        runCatching { unregisterReceiver(mediaReceiver) }
        runCatching { unregisterReceiver(lyricsChangedReceiver) }
        runCatching { unregisterReceiver(languageChangedReceiver) }
        unregisterScreenOnWidgetRefresh()
        lockScreenPublisher.clear()
        AirLyricsAodWidgetProvider.clearWidgets(this)
        AirLyricsDockWidgetProvider.clearWidgets(this)
        if (::windowController.isInitialized) {
            windowController.hide(notifyVisibilityChanged = false)
        }
        broadcastWindowVisibility(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CURRENT_MEDIA_REFRESH_INTERVAL_MS = 1_000L
        internal val MEDIA_RESTORE_RETRY_DELAYS_MS = longArrayOf(
            250L,
            750L,
            2_000L,
            5_000L
        )
    }
}
