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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private lateinit var tvTrackInfo: TextView
    private lateinit var tvCurrentLyric: TextView
    private lateinit var tvNextLyric: TextView
    private lateinit var tvDismissHint: TextView

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pauseTimeoutRunnable = Runnable { finishSafely() }

    private val pixelShiftRunnable = object : Runnable {
        override fun run() {
            val dx = Random.nextInt(-18, 19).toFloat()
            val dy = Random.nextInt(-24, 25).toFloat()
            contentContainer.animate().translationX(dx).translationY(dy).setDuration(600).start()
            handler.postDelayed(this, 45_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAodMode = intent.getBooleanExtra(EXTRA_AOD_MODE, true)

        applyLockScreenAndWakeFlags()
        setContentView(R.layout.activity_aod_lyrics)
        hideSystemBars()

        contentContainer = findViewById(R.id.aodContentContainer)
        tvTrackInfo = findViewById(R.id.aodTrackInfo)
        tvCurrentLyric = findViewById(R.id.aodCurrentLyric)
        tvNextLyric = findViewById(R.id.aodNextLyric)
        tvDismissHint = findViewById(R.id.aodDismissHint)

        configureBrightnessAndMode()
        setupDoubleTapToDismiss()
        setupProximitySensor()
        observeLyrics()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isAodMode = intent.getBooleanExtra(EXTRA_AOD_MODE, true)
        configureBrightnessAndMode()
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
        val lp = window.attributes
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window.attributes = lp
    }

    private fun configureBrightnessAndMode() {
        val lp = window.attributes
        lp.screenBrightness = if (isAodMode) 0.04f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
                tvTrackInfo.text = listOf(state.trackTitle, state.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                tvCurrentLyric.text = state.currentLine.ifBlank {
                    state.trackTitle.ifBlank { "Listening for lyrics…" }
                }
                tvNextLyric.text = state.nextLine

                if (state.isPlaying) {
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    handler.postDelayed(pauseTimeoutRunnable, 10_000L)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
                // Phone in pocket or face down -> turn off screen immediately
                finishSafely()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finishSafely() {
        val lp = window.attributes
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        window.attributes = lp
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
