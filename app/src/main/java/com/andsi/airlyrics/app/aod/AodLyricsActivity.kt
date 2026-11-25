package com.andsi.airlyrics.app.aod

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.LyricPanelAppearance
import com.andsi.airlyrics.core.model.LyricPanelTypeface
import com.andsi.airlyrics.floating.AodLyricsHost
import com.andsi.airlyrics.floating.AodLyricsState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Centered pure-OLED (#000000) 3-line squircle AOD screen fallback for Samsung devices
 * where LockStar suppresses hostId=2184 widgets during DOZE_SUSPEND (e.g. Galaxy A25).
 * Never touches MediaSession or Now Bar, releases wake lock on pause, and dismisses cleanly on unlock.
 */
class AodLyricsActivity : AppCompatActivity() {

    private lateinit var card: LinearLayout
    private lateinit var prevView: TextView
    private lateinit var heroView: TextView
    private lateinit var nextView: TextView
    private lateinit var attributionView: TextView
    private lateinit var hintView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var shiftIndex = 0
    private var lastHeroText: String? = null
    private var unlockReceiverRegistered = false

    private val burnInShiftOffsetsDp = arrayOf(
        0 to 0,
        4 to 0,
        6 to 4,
        0 to 6,
        -6 to 4,
        -4 to 0,
        -6 to -4,
        0 to -6,
        6 to -4
    )

    private val burnInShiftRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            shiftIndex = (shiftIndex + 1) % burnInShiftOffsetsDp.size
            val (dxDp, dyDp) = burnInShiftOffsetsDp[shiftIndex]
            val density = resources.displayMetrics.density
            card.animate()
                .translationX(dxDp * density)
                .translationY(dyDp * density)
                .setDuration(600L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            handler.postDelayed(this, BURN_IN_SHIFT_INTERVAL_MS)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                AodLyricsHost.resetLockCycle()
                finishSilently()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenWindow()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val maxCardWidth = (resources.displayMetrics.widthPixels - dp(44))
            .coerceIn(dp(220), dp(336))

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@AodLyricsActivity, R.drawable.bg_aod_squircle_card)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            minimumHeight = dp(164)
            layoutParams = FrameLayout.LayoutParams(
                maxCardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val mutedColor = ColorUtils.setAlphaComponent(Color.WHITE, LyricPanelAppearance.NEIGHBOR_LINE_ALPHA)

        prevView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(mutedColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        heroView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1.12f)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        nextView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(mutedColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        attributionView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 110))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        card.addView(prevView)
        card.addView(heroView)
        card.addView(nextView)
        card.addView(attributionView)

        hintView = TextView(this).apply {
            text = getString(R.string.ui_aod_screen_dismiss_hint)
            gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 72))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(32) }
        }

        root.addView(card)
        root.addView(hintView)
        setContentView(root)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                AodLyricsHost.dismissByUser()
                finishSilently()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val startY = e1?.y ?: return false
                if (startY - e2.y > dp(72) && velocityY < -400f) {
                    AodLyricsHost.dismissByUser()
                    finishSilently()
                    return true
                }
                return false
            }
        })
        root.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AodLyricsHost.dismissByUser()
                finishSilently()
            }
        })

        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        unlockReceiverRegistered = true

        AodLyricsHost.activityVisible = true
        handler.postDelayed(burnInShiftRunnable, BURN_IN_SHIFT_INTERVAL_MS)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AodLyricsHost.state.collect { state ->
                    if (!state.active) {
                        finishSilently()
                    } else {
                        bindState(state)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AodLyricsHost.activityVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        AodLyricsHost.activityVisible = false
        handler.removeCallbacks(burnInShiftRunnable)
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
        window.attributes = window.attributes.apply {
            screenBrightness = 0.05f
        }
    }

    private fun bindState(state: AodLyricsState) {
        val sizeSp = state.textSizeSp.toFloat()
        val neighborSp = (sizeSp * 0.72f).coerceIn(9f, (sizeSp - 2f).coerceAtLeast(9f))
        val typeface = resolveTypeface(state.typeface, isBold = false)
        val boldTypeface = resolveTypeface(state.typeface, isBold = true)

        val hero = state.currentLine.orEmpty()
        if (hero != lastHeroText) {
            lastHeroText = hero
            if (state.minimalMotion) {
                heroView.animate().cancel()
                heroView.alpha = 1f
                heroView.text = hero
            } else {
                heroView.animate().cancel()
                heroView.animate().alpha(0f).setDuration(120L).withEndAction {
                    heroView.text = hero
                    heroView.animate().alpha(1f).setDuration(180L).start()
                }.start()
            }
        }
        heroView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        heroView.typeface = boldTypeface

        fun bindNeighbor(view: TextView, text: String?) {
            view.text = text.orEmpty()
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, neighborSp)
            view.typeface = typeface
            view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        bindNeighbor(prevView, state.previousLine)
        bindNeighbor(nextView, state.nextLine)

        val attrText = state.attribution.orEmpty()
        attributionView.text = attrText
        attributionView.visibility = if (attrText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun resolveTypeface(type: LyricPanelTypeface, isBold: Boolean): Typeface {
        val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
        return when (type) {
            LyricPanelTypeface.SPOTIFY -> ResourcesCompat.getFont(this, R.font.air_spotify)
                ?.let { Typeface.create(it, style) }
                ?: Typeface.create("sans-serif-medium", style)
            LyricPanelTypeface.APPLE_MUSIC -> ResourcesCompat.getFont(this, R.font.air_apple_music)
                ?.let { Typeface.create(it, style) }
                ?: Typeface.create("sans-serif", style)
            else -> Typeface.create("sans-serif-medium", style)
        }
    }

    private fun finishSilently() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }

    private fun dp(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val BURN_IN_SHIFT_INTERVAL_MS = 45_000L
    }
}
