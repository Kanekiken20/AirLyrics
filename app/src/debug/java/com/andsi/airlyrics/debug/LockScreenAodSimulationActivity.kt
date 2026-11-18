package com.andsi.airlyrics.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.widget.FixedLyricsPanelProvider
import kotlin.math.roundToInt

class LockScreenAodSimulationActivity : Activity() {

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var statusBanner: TextView
        private set
    private lateinit var slot1x1LightHost: FrameLayout
    private lateinit var slot2x1AodHost: FrameLayout
    private lateinit var logOutputView: TextView

    private var widgetId1x1: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var widgetId2x1: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hostView1x1: AppWidgetHostView? = null
    private var hostView2x1: AppWidgetHostView? = null
    private var usingDirectRemoteViewsMirror: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, HOST_ID)

        buildUi()
        bindComplicationWidgets()

        if (intent?.getBooleanExtra(EXTRA_AUTO_RUN, false) == true) {
            mainHandler.postDelayed({ runDeterministicVerificationSuite() }, 200L)
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { appWidgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { appWidgetHost.stopListening() }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val id = data.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                usingDirectRemoteViewsMirror = false
                if (requestCode == REQ_BIND_1X1) {
                    widgetId1x1 = id
                    mountHostView(id, slot1x1LightHost) { hostView1x1 = it }
                } else if (requestCode == REQ_BIND_2X1) {
                    widgetId2x1 = id
                    mountHostView(id, slot2x1AodHost) { hostView2x1 = it }
                }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101014"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
        }

        statusBanner = TextView(this).apply {
            text = "HARNESS_READY"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(Color.parseColor("#263238"))
        }

        val lightWallpaperCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        val lightLabel = TextView(this).apply {
            text = "Simulated Samsung 1x1 Lock Screen (White Wallpaper)"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8f))
        }
        slot1x1LightHost = FrameLayout(this)
        lightWallpaperCard.addView(lightLabel)
        lightWallpaperCard.addView(
            slot1x1LightHost,
            LinearLayout.LayoutParams(dp(70f), dp(70f))
        )

        val aodBlackCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        val aodLabel = TextView(this).apply {
            text = "Simulated Samsung 2x1 AOD Complication (Black OLED)"
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8f))
        }
        slot2x1AodHost = FrameLayout(this)
        aodBlackCard.addView(aodLabel)
        aodBlackCard.addView(
            slot2x1AodHost,
            LinearLayout.LayoutParams(dp(156f), dp(56f))
        )

        val runSuiteBtn = Button(this).apply {
            text = "Run Full Verification Suite"
            setOnClickListener { runDeterministicVerificationSuite() }
        }

        val bindWidgetBtn = Button(this).apply {
            text = "Request Interactive System Widget Bind"
            setOnClickListener { requestInteractiveBind() }
        }

        logOutputView = TextView(this).apply {
            setTextColor(Color.parseColor("#B9F6CA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        }

        root.addView(statusBanner)
        root.addView(lightWallpaperCard, marginParams())
        root.addView(aodBlackCard, marginParams())
        root.addView(runSuiteBtn, marginParams())
        root.addView(bindWidgetBtn, marginParams())
        root.addView(logOutputView)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun bindComplicationWidgets() {
        val provider = ComponentName(this, FixedLyricsPanelProvider::class.java)

        widgetId1x1 = appWidgetHost.allocateAppWidgetId()
        widgetId2x1 = appWidgetHost.allocateAppWidgetId()

        val bound1x1 = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(widgetId1x1, provider)
        }.getOrDefault(false)
        val bound2x1 = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(widgetId2x1, provider)
        }.getOrDefault(false)

        if (bound1x1 && bound2x1) {
            usingDirectRemoteViewsMirror = false
            mountHostView(widgetId1x1, slot1x1LightHost) { hostView1x1 = it }
            mountHostView(widgetId2x1, slot2x1AodHost) { hostView2x1 = it }
        } else {
            usingDirectRemoteViewsMirror = true
            refreshDirectRemoteViewsMirror()
            appendLog("Mounted direct RemoteViews mirror (bindAppWidgetIdIfAllowed=false)")
        }
    }

    private fun requestInteractiveBind() {
        val provider = ComponentName(this, FixedLyricsPanelProvider::class.java)
        startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId2x1)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            },
            REQ_BIND_2X1
        )
    }

    private fun mountHostView(
        appWidgetId: Int,
        container: FrameLayout,
        assign: (AppWidgetHostView) -> Unit
    ) {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
        val hostView = appWidgetHost.createView(applicationContext, appWidgetId, info)
        hostView.setAppWidget(appWidgetId, info)
        container.removeAllViews()
        container.addView(
            hostView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        assign(hostView)
        appendLog("Mounted AppWidgetHostView id=$appWidgetId")
    }

    private fun refreshDirectRemoteViewsMirror() {
        if (!usingDirectRemoteViewsMirror) return
        val latestText = FixedLyricsPanelProvider.LyricsWidgetState.formatDisplayText()
        listOf(slot1x1LightHost, slot2x1AodHost).forEach { slot ->
            val views = RemoteViews(packageName, R.layout.widget_fixed_lyrics_panel).apply {
                setTextViewText(R.id.lyric_panel_text, latestText)
                setContentDescription(R.id.lyric_panel_text, latestText)
            }
            slot.removeAllViews()
            val inflated = views.apply(this, slot)
            slot.addView(
                inflated,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                slot.layoutParams?.width?.takeIf { it > 0 } ?: dp(156f),
                View.MeasureSpec.EXACTLY
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                slot.layoutParams?.height?.takeIf { it > 0 } ?: dp(56f),
                View.MeasureSpec.EXACTLY
            )
            slot.measure(widthSpec, heightSpec)
            slot.layout(0, 0, slot.measuredWidth, slot.measuredHeight)
        }
    }

    fun runDeterministicVerificationSuite() {
        appendLog("--- Starting Verification Suite ---")

        // Step 1: Verify track-title fallback when lyric line is blank
        FixedLyricsPanelProvider.updateLyrics(this, lyricLine = null, trackTitle = "Limbo")
        FixedLyricsPanelProvider.pushLatest(this, force = true)
        refreshDirectRemoteViewsMirror()

        mainHandler.postDelayed({
            val fallbackOk = assertWidgetTextEquals("\u266A Limbo", "Track fallback")

            // Step 2: Push active playback lyric
            FixedLyricsPanelProvider.updateLyrics(
                context = this,
                lyricLine = "You got me in limbo babe",
                trackTitle = "Limbo"
            )
            FixedLyricsPanelProvider.pushLatest(this, force = true)
            refreshDirectRemoteViewsMirror()

            mainHandler.postDelayed({
                val lyricPushOk = assertWidgetTextEquals("You got me in limbo babe", "Lyric push")

                // Step 3: Simulate rapid burst (<800ms) followed by Screen-On wake
                FixedLyricsPanelProvider.updateLyrics(this, "Burst line 1", "Limbo")
                FixedLyricsPanelProvider.updateLyrics(this, "Burst line 2 (final)", "Limbo")
                FixedLyricsPanelProvider().onReceive(this, Intent(Intent.ACTION_SCREEN_ON))

                mainHandler.postDelayed({
                    refreshDirectRemoteViewsMirror()
                    val wakeOk = assertWidgetTextEquals("Burst line 2 (final)", "Screen-On wake sync")
                    val allPassed = fallbackOk && lyricPushOk && wakeOk
                    val verdict = if (allPassed) "VERIFICATION_PASS" else "VERIFICATION_FAIL"
                    statusBanner.text = verdict
                    statusBanner.setBackgroundColor(
                        Color.parseColor(if (allPassed) "#1B5E20" else "#B71C1C")
                    )
                    Log.i(TAG, "FINAL_VERDICT=$verdict")
                }, 1100L)
            }, 600L)
        }, 400L)
    }

    private fun assertWidgetTextEquals(expected: String, stepName: String): Boolean {
        val containers: List<View> = if (usingDirectRemoteViewsMirror) {
            listOf(slot1x1LightHost, slot2x1AodHost)
        } else {
            listOfNotNull(hostView2x1, hostView1x1)
        }

        if (containers.isEmpty()) {
            appendLog("FAIL [$stepName]: No widget containers mounted")
            return false
        }

        var allOk = true
        containers.forEachIndexed { index, container ->
            val tv = findTextViewById(container, R.id.lyric_panel_text)
            val actual = tv?.text?.toString().orEmpty()
            val textColor = tv?.currentTextColor ?: 0
            val visible = tv != null && tv.visibility == View.VISIBLE && tv.width > 0 && tv.height > 0
            val notSuppressed = !actual.contains("unavailable", ignoreCase = true)
            val matches = actual == expected
            val highContrast = textColor == Color.WHITE

            val passed = visible && notSuppressed && matches && highContrast
            if (!passed) allOk = false

            val msg = "${if (passed) "PASS" else "FAIL"} [$stepName][slot#$index] " +
                "actual=\"$actual\" expected=\"$expected\" visible=$visible color=#${Integer.toHexString(textColor)}"
            appendLog(msg)
            Log.i(TAG, msg)
        }
        return allOk
    }

    private fun findTextViewById(root: View, id: Int): TextView? {
        if (root.id == id && root is TextView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTextViewById(root.getChildAt(i), id)
                if (found != null) return found
            }
        }
        return null
    }

    private fun appendLog(line: String) {
        logOutputView.append("$line\n")
    }

    private fun marginParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10f)
        }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()

    companion object {
        private const val TAG = "AirLyricsHarness"
        private const val HOST_ID = 4096
        private const val REQ_BIND_1X1 = 101
        private const val REQ_BIND_2X1 = 102
        const val EXTRA_AUTO_RUN = "extra_auto_run"
    }
}
