package com.andsi.airlyrics.aod

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Optional standalone AMOLED AOD screen (strictly OFF by default).
 * Features a top One UI-styled live clock (time + date) above the centered 3-line squircle card,
 * periodic OLED pixel shifting, proximity pocket guard, and double-tap dismiss.
 */
class LockScreenAodLyricsActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_AOD_MODE = "extra_aod_mode"

        fun createIntent(context: Context, aodMode: Boolean): Intent {
            return Intent(context, LockScreenAodLyricsActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_AOD_MODE, aodMode)
            }
        }
    }

    private var isAodMode: Boolean = true
    private lateinit var contentContainer: LinearLayout
    private lateinit var aodClock: TextClock
    private lateinit var aodDate: TextClock
    private lateinit var tvTrackInfo: TextView
    private lateinit var tvPreviousLyric: TextView
    private lateinit var tvCurrentLyric: TextView
    private lateinit var tvNextLyric: TextView
    private lateinit var tvDismissHint: TextView

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pauseTimeoutRunnable = Runnable { finishSafely() }

    private val pixelShiftRunnable = object : Runnable {
        override fun run() {
            val dx = Random.nextInt(-16, 17).toFloat()
            val dy = Random.nextInt(-22, 23).toFloat()
            contentContainer.animate().translationX(dx).translationY(dy).setDuration(600).start()
            handler.postDelayed(this, 45_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppSettingsStore.isStandaloneAodScreenEnabled(this)) {
            finishSafely()
            return
        }

        isAodMode = intent.getBooleanExtra(EXTRA_AOD_MODE, true)

        applyLockScreenAndWakeFlags()
        setContentView(R.layout.activity_aod_lyrics)
        hideSystemBars()

        contentContainer = findViewById(R.id.aodContentContainer)
        aodClock = findViewById(R.id.aodClock)
        aodDate = findViewById(R.id.aodDate)
        tvTrackInfo = findViewById(R.id.aodTrackInfo)
        tvPreviousLyric = findViewById(R.id.aodPreviousLyric)
        tvCurrentLyric = findViewById(R.id.aodCurrentLyric)
        tvNextLyric = findViewById(R.id.aodNextLyric)
        tvDismissHint = findViewById(R.id.aodDismissHint)

        applyTypographyStyle()
        configureBrightnessAndMode()
        setupDoubleTapToDismiss()
        setupProximitySensor()
        observeLyrics()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!AppSettingsStore.isStandaloneAodScreenEnabled(this)) {
            finishSafely()
            return
        }
        isAodMode = intent.getBooleanExtra(EXTRA_AOD_MODE, true)
        applyTypographyStyle()
        configureBrightnessAndMode()
    }

    private fun applyTypographyStyle() {
        val scale = AppSettingsStore.getWidgetTextScalePercent(this) / 100f
        val regularFace = FloatingLyricsFontStore.resolveWidgetTypeface(this, isBold = false)
        val boldFace = FloatingLyricsFontStore.resolveWidgetTypeface(this, isBold = true)

        tvTrackInfo.typeface = regularFace
        tvPreviousLyric.typeface = regularFace
        tvPreviousLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        tvCurrentLyric.typeface = boldFace
        tvCurrentLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f * scale)
        tvNextLyric.typeface = regularFace
        tvNextLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
    }

    private fun applyLockScreenAndWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(isAodMode)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    if (isAodMode) WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON else 0
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun configureBrightnessAndMode() {
        val lp = window.attributes
        lp.screenBrightness = if (isAodMode) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        tvDismissHint.text = if (isAodMode) {
            "Double-tap to dismiss"
        } else {
            "Double-tap to return to Lock Screen"
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    private fun setupDoubleTapToDismiss() {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    finishSafely()
                    return true
                }
            }
        )
        findViewById<View>(R.id.aodRoot).setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun setupProximitySensor() {
        sensorManager = getSystemService(SensorManager::class.java)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    private fun observeLyrics() {
        lifecycleScope.launch {
            LyricsStateHolder.lyricsState.collectLatest { state ->
                val meta = listOf(state.trackTitle.trim(), state.artist.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                tvTrackInfo.text = if (meta.isNotEmpty()) "\u266A $meta" else "\u266A AirLyrics"

                if (state.previousLine.isNotBlank()) {
                    tvPreviousLyric.text = state.previousLine
                    tvPreviousLyric.visibility = View.VISIBLE
                } else {
                    tvPreviousLyric.visibility = View.GONE
                }

                tvCurrentLyric.text = state.currentLine.ifBlank {
                    state.trackTitle.ifBlank { "Listening for lyrics…" }
                }

                if (state.nextLine.isNotBlank()) {
                    tvNextLyric.text = state.nextLine
                    tvNextLyric.visibility = View.VISIBLE
                } else {
                    tvNextLyric.visibility = View.GONE
                }

                if (state.isPlaying) {
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    handler.postDelayed(pauseTimeoutRunnable, 12_000L)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AppSettingsStore.isStandaloneAodScreenEnabled(this)) {
            finishSafely()
            return
        }
        LyricsStateHolder.isAodActivityVisible = true
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.removeCallbacks(pixelShiftRunnable)
        handler.postDelayed(pixelShiftRunnable, 45_000L)
    }

    override fun onPause() {
        super.onPause()
        LyricsStateHolder.isAodActivityVisible = false
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(pixelShiftRunnable)
    }

    override fun onDestroy() {
        LyricsStateHolder.isAodActivityVisible = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = proximitySensor ?: return
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values.firstOrNull() ?: return
            if (isAodMode && distance < sensor.maximumRange) {
                finishSafely()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finishSafely() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
