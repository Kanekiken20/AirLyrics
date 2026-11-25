package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AodLyricsState(
    val active: Boolean = false,
    val previousLine: String? = null,
    val currentLine: String? = null,
    val nextLine: String? = null,
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val attribution: String? = null,
    val textSizeSp: Int = LyricsDisplaySettings.DEFAULT_PANEL_TEXT_SIZE_SP,
    val typeface: LyricPanelTypeface = LyricPanelTypeface.SYSTEM,
    val minimalMotion: Boolean = false
)

/**
 * Process-local handoff between FloatingLyricsService and the app-layer OLED AOD Activity.
 * Keeps `floating` decoupled from `app` to satisfy module architecture boundaries.
 */
internal object AodLyricsHost {
    const val ACTION_SHOW_AOD = "com.andsi.airlyrics.ACTION_SHOW_AOD"

    private val current = MutableStateFlow(AodLyricsState())
    val state = current.asStateFlow()

    @Volatile
    var activityVisible: Boolean = false

    @Volatile
    var launchedForLockCycle: Boolean = false

    @Volatile
    var dismissedForLockCycle: Boolean = false

    var onDismissedByUser: (() -> Unit)? = null

    fun publish(next: AodLyricsState) {
        if (current.value != next) {
            current.value = next
        }
    }

    fun dismiss() {
        if (current.value.active) {
            current.value = AodLyricsState(active = false)
        }
    }

    fun dismissByUser() {
        dismissedForLockCycle = true
        dismiss()
        onDismissedByUser?.invoke()
    }

    fun resetLockCycle() {
        launchedForLockCycle = false
        dismissedForLockCycle = false
        dismiss()
    }

    fun launchIfAllowed(context: Context): Boolean {
        if (activityVisible || dismissedForLockCycle || launchedForLockCycle) return false
        launchedForLockCycle = true
        val intent = Intent(ACTION_SHOW_AOD)
            .setPackage(context.packageName)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) launchedForLockCycle = false
        return launched
    }
}
