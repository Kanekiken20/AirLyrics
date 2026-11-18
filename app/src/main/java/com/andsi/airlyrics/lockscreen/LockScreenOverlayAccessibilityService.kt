package com.andsi.airlyrics.lockscreen

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Lightweight AccessibilityService whose sole responsibility is attaching
 * TYPE_ACCESSIBILITY_OVERLAY directly onto Samsung's Lock Screen when unlocked
 * overlay windows are blocked by Keyguard.
 */
class LockScreenOverlayAccessibilityService : AccessibilityService() {

    private var cardWindow: LockScreenLyricsCardWindow? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_LOCKSCREEN_CARD_STATE) return

            val shouldShow = intent.getBooleanExtra(EXTRA_SHOULD_SHOW, false)
            val currentLine = intent.getStringExtra(EXTRA_CURRENT_LINE).orEmpty()
            val nextLine = intent.getStringExtra(EXTRA_NEXT_LINE).orEmpty()
            val trackTitle = intent.getStringExtra(EXTRA_TRACK_TITLE).orEmpty()
            val artistName = intent.getStringExtra(EXTRA_ARTIST_NAME).orEmpty()

            cardWindow?.update(
                currentLine = currentLine,
                nextLine = nextLine,
                trackTitle = trackTitle,
                artistName = artistName,
                shouldShow = shouldShow
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cardWindow = LockScreenLyricsCardWindow(this)

        val filter = IntentFilter(ACTION_LOCKSCREEN_CARD_STATE)
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Request latest state immediately
        sendBroadcast(
            Intent(ACTION_REQUEST_LOCKSCREEN_SYNC).setPackage(packageName)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — driven directly by screen/keyguard state in FloatingLyricsService
    }

    override fun onInterrupt() {
        cardWindow?.hide()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(updateReceiver) }
        cardWindow?.hide()
        cardWindow = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCKSCREEN_CARD_STATE =
            "com.andsi.airlyrics.lockscreen.ACTION_LOCKSCREEN_CARD_STATE"
        const val ACTION_REQUEST_LOCKSCREEN_SYNC =
            "com.andsi.airlyrics.lockscreen.ACTION_REQUEST_LOCKSCREEN_SYNC"

        const val EXTRA_SHOULD_SHOW = "extra_should_show"
        const val EXTRA_CURRENT_LINE = "extra_current_line"
        const val EXTRA_NEXT_LINE = "extra_next_line"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
    }
}
