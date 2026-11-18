package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import kotlin.math.abs

/**
 * Owns the floating lyrics window itself: creation, removal, dragging,
 * style application, lock state and click-through behavior.
 *
 * FloatingLyricsService keeps the media / lyrics state, while this class keeps
 * the Android WindowManager details in one small box.
 */
class FloatingLyricsWindow(
    private val context: Context,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private val windowContext = if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        context.createDisplayContext(manager.defaultDisplay)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lyricsView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var position: FloatingPosition? = null
    private var geometry: FloatingWindowGeometry? = null
    private var gestureActive = false
    private var dockTabView: View? = null
    private var dockTabParams: WindowManager.LayoutParams? = null
    private var currentDockSide: DockSide = runCatching {
        DockSide.valueOf(AppSettingsStore.getFloatingCollapsedSide(context))
    }.getOrDefault(DockSide.NONE)
    private var velocityTracker: VelocityTracker? = null
    private var activeSpringX: SpringAnimation? = null
    private var dragging = false
    private var remapPending = false
    private val refreshLayout = Runnable {
        val remap = remapPending
        remapPending = false
        updateLayout(remap)
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        scheduleLayout()
    }

    fun onConfigurationChanged() {
        gestureActive = false
        scheduleLayout(remap = true)
    }

    private fun scheduleLayout(remap: Boolean = false) {
        val view = lyricsView ?: return
        remapPending = remapPending || remap
        view.removeCallbacks(refreshLayout)
        view.post(refreshLayout)
    }

    private var startX = 0
    private var startY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    val textView: TextView?
        get() = lyricsView

    val isVisible: Boolean
        get() = lyricsView != null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            hideAfterFailure()
            return false
        }

        lyricsView?.let {
            val refreshed = applyStyle()
            if (refreshed) onVisibilityChanged(true)
            return refreshed
        }

        val view = FloatingLyricsTextView(windowContext).apply {
            text = this@FloatingLyricsWindow.context.getString(R.string.ui_waiting_for_media_message)
            includeFontPadding = false
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlagsForCurrentBehavior(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                setFitInsetsIgnoringVisibility(true)
            }
        }

        view.setOnTouchListener(::handleTouch)

        return runCatching {
            lyricsView = view
            params = layoutParams
            position = FloatingLyricsStyleStore.getRelativePosition(context)
            applyStyle(view)
            calculateLayout(view, layoutParams, remap = true)
            windowManager.addView(view, layoutParams)
            view.onWindowConfigurationChanged = ::onConfigurationChanged
            view.addOnLayoutChangeListener(layoutListener)
            view.setOnApplyWindowInsetsListener { _, insets ->
                scheduleLayout()
                insets
            }
            view.requestApplyInsets()
            if (currentDockSide != DockSide.NONE) {
                view.visibility = View.GONE
                showDockTab(currentDockSide, layoutParams.y)
            }
            onVisibilityChanged(true)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun hide(notifyVisibilityChanged: Boolean = true): Boolean {
        removeDockTab()
        activeSpringX?.cancel()
        activeSpringX = null
        val view = lyricsView
        view?.removeCallbacks(refreshLayout)
        view?.removeOnLayoutChangeListener(layoutListener)
        view?.setOnApplyWindowInsetsListener(null)
        (view as? FloatingLyricsTextView)?.onWindowConfigurationChanged = null
        gestureActive = false
        remapPending = false
        geometry = null
        position = null
        val removed = if (view != null) {
            runCatching { windowManager.removeView(view) }.isSuccess
        } else {
            true
        }

        lyricsView = null
        params = null
        if (notifyVisibilityChanged) {
            onVisibilityChanged(false)
        }
        return removed
    }

    fun applyStyle(): Boolean {
        val view = lyricsView ?: return true
        val p = params ?: return true
        return runCatching {
            applyStyle(view)
            if (calculateLayout(view, p, remap = true)) windowManager.updateViewLayout(view, p)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun setLocked(locked: Boolean): Boolean {
        val previousLocked = FloatingLyricsStyleStore.isLocked(context)
        FloatingLyricsStyleStore.setLocked(context, locked)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setLocked(context, previousLocked)
        return updated
    }

    fun setClickThrough(clickThrough: Boolean): Boolean {
        val previousClickThrough = FloatingLyricsStyleStore.isClickThrough(context)
        FloatingLyricsStyleStore.setClickThrough(context, clickThrough)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setClickThrough(context, previousClickThrough)
        return updated
    }

    fun expandTab(): Boolean {
        if (currentDockSide != DockSide.NONE) {
            expandFromSide(currentDockSide)
            return true
        }
        return false
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val p = params ?: return false
        val isLocked = FloatingLyricsStyleStore.isLocked(context)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                dragging = false
                startX = p.x
                startY = p.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (isLocked || !gestureActive) {
                    true
                } else {
                    if (!dragging && isClick(event)) return true
                    dragging = true
                    val bounds = geometry ?: return true
                    val dx = (event.rawX - touchStartX).toInt() *
                        if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                    p.x = startX + dx
                    p.y = (startY + (event.rawY - touchStartY).toInt()).coerceIn(0, bounds.travelY)
                    runCatching { windowManager.updateViewLayout(view, p) }
                        .onFailure { hideAfterFailure() }
                        .isSuccess
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return true
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (!dragging && isClick(event)) {
                    val textView = view as? TextView
                    val layout = textView?.layout
                    val text = textView?.text as? android.text.Spanned
                    val x = event.x - (textView?.totalPaddingLeft ?: 0) + (textView?.scrollX ?: 0)
                    val y = event.y - (textView?.totalPaddingTop ?: 0) + (textView?.scrollY ?: 0)
                    val link = if (!isLocked && layout != null && text != null && y >= 0 && y < layout.height) {
                        val line = layout.getLineForVertical(y.toInt())
                        if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                            val offset = layout.getOffsetForHorizontal(line, x)
                            text.getSpans(offset, offset, android.text.style.URLSpan::class.java).firstOrNull()
                        } else null
                    } else null
                    if (link != null && link.url.startsWith("https://")) {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else view.performClick()
                }

                if (!isLocked && dragging) {
                    val screenW = windowContext.resources.displayMetrics.widthPixels
                    val collapseLeft = vx < -850f || (p.x <= dp(16))
                    val collapseRight = vx > 850f || (p.x >= (screenW - p.width - dp(16)))

                    when {
                        collapseLeft -> collapseToSide(DockSide.LEFT, vx)
                        collapseRight -> collapseToSide(DockSide.RIGHT, vx)
                        else -> {
                            val bounds = geometry
                            if (bounds != null) {
                                p.x = p.x.coerceIn(0, bounds.travelX)
                                runCatching { windowManager.updateViewLayout(view, p) }
                            }
                            saveDraggedPosition(p)
                        }
                    }
                }
                gestureActive = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                if (gestureActive) scheduleLayout(remap = true)
                gestureActive = false
                true
            }

            else -> true
        }
    }

    private fun collapseToSide(side: DockSide, initialVelocityX: Float) {
        val view = lyricsView ?: return
        val p = params ?: return
        val screenW = windowContext.resources.displayMetrics.widthPixels
        val startX = p.x.toFloat()
        val targetX = if (side == DockSide.LEFT) -p.width.toFloat() else screenW.toFloat()

        activeSpringX?.cancel()
        activeSpringX = SpringAnimation(FloatValueHolder(startX)).apply {
            setStartVelocity(initialVelocityX)
            spring = SpringForce(targetX).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = 520f
            }
            addUpdateListener { _, value, _ ->
                p.x = value.toInt()
                runCatching { windowManager.updateViewLayout(view, p) }
            }
            addEndListener { _, canceled, _, _ ->
                if (!canceled && lyricsView != null) {
                    finishCollapse(side, p.y)
                }
            }
            start()
        }
    }

    private fun finishCollapse(side: DockSide, yPx: Int) {
        val view = lyricsView ?: return
        view.visibility = View.GONE
        currentDockSide = side
        AppSettingsStore.setFloatingCollapsedSide(context, side.name)
        showDockTab(side, yPx)
    }

    private fun showDockTab(side: DockSide, yPx: Int) {
        removeDockTab()
        val screenW = windowContext.resources.displayMetrics.widthPixels
        val tabTouchWidth = dp(28)
        val tabHeight = dp(66)

        val tab = FrameLayout(windowContext).apply {
            background = ContextCompat.getDrawable(
                windowContext,
                if (side == DockSide.LEFT) R.drawable.bg_edge_tab_left else R.drawable.bg_edge_tab_right
            )
            elevation = dp(6).toFloat()
        }

        val chevron = TextView(windowContext).apply {
            text = if (side == DockSide.LEFT) "›" else "‹"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        tab.addView(chevron)

        val targetX = if (side == DockSide.LEFT) 0 else screenW - tabTouchWidth
        val maxTargetY = (windowContext.resources.displayMetrics.heightPixels - tabHeight - dp(32)).coerceAtLeast(dp(32))
        val clampedY = yPx.coerceIn(dp(32), maxTargetY)

        val p = WindowManager.LayoutParams(
            tabTouchWidth,
            tabHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = targetX
            y = clampedY
        }

        var downX = 0f
        var downY = 0f
        var startTabY = 0
        var tabDragging = false

        tab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTabY = p.y
                    tabDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val pulledInward = if (side == DockSide.LEFT) dx > dp(24) else dx < -dp(24)
                    if (pulledInward) {
                        expandFromSide(side)
                        return@setOnTouchListener true
                    }
                    if (!tabDragging && abs(dy) > touchSlop) {
                        tabDragging = true
                    }
                    if (tabDragging) {
                        val maxDragY = (windowContext.resources.displayMetrics.heightPixels - tabHeight - dp(32)).coerceAtLeast(dp(32))
                        p.y = (startTabY + dy).toInt().coerceIn(dp(32), maxDragY)
                        runCatching { windowManager.updateViewLayout(tab, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tabDragging) {
                        expandFromSide(side)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager.addView(tab, p)
            dockTabView = tab
            dockTabParams = p
        }
    }

    private fun removeDockTab() {
        dockTabView?.let { tab ->
            runCatching { windowManager.removeView(tab) }
        }
        dockTabView = null
        dockTabParams = null
    }

    private fun expandFromSide(side: DockSide) {
        val view = lyricsView ?: return
        val p = params ?: return
        val screenW = windowContext.resources.displayMetrics.widthPixels
        val tabY = dockTabParams?.y ?: p.y

        removeDockTab()
        currentDockSide = DockSide.NONE
        AppSettingsStore.setFloatingCollapsedSide(context, DockSide.NONE.name)

        view.visibility = View.VISIBLE
        val startX = if (side == DockSide.LEFT) -p.width.toFloat() else screenW.toFloat()
        val targetX = if (side == DockSide.LEFT) dp(12).toFloat() else (screenW - p.width - dp(12)).toFloat()

        p.x = startX.toInt()
        p.y = tabY
        runCatching { windowManager.updateViewLayout(view, p) }

        activeSpringX?.cancel()
        activeSpringX = SpringAnimation(FloatValueHolder(startX)).apply {
            spring = SpringForce(targetX).apply {
                dampingRatio = 0.74f
                stiffness = 380f
            }
            addUpdateListener { _, value, _ ->
                p.x = value.toInt()
                runCatching { windowManager.updateViewLayout(view, p) }
            }
            addEndListener { _, _, _, _ ->
                saveDraggedPosition(p)
            }
            start()
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        val threshold = touchSlop.toFloat()
        return abs(event.rawX - touchStartX) <= threshold &&
            abs(event.rawY - touchStartY) <= threshold
    }

    private fun applyStyle(view: TextView) {
        val style = FloatingLyricsStyleStore.getStyle(context)

        view.textSize = style.textSizeSp
        FloatingLyricsFontStore.applyTypeface(
            view,
            style.fontFamily,
            style.fontWeight
        )
        view.setTextColor(style.textColor)
        view.gravity = style.gravity
        view.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        view.setPadding(
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp),
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp)
        )

        if (style.shadowRadius > 0f) {
            view.setShadowLayer(
                style.shadowRadius,
                0f,
                0f,
                AirColorUtils.multiplyAlpha(style.shadowColor, Color.alpha(style.textColor))
            )
        } else {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        view.background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
                setColor(AirColorUtils.withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    private fun saveDraggedPosition(p: WindowManager.LayoutParams) {
        val bounds = geometry ?: return
        val previous = position ?: return
        position = bounds.position(p.x, p.y, previous).also {
            FloatingLyricsStyleStore.saveRelativePosition(context, it)
        }
    }

    private fun updateLayout(remap: Boolean) {
        val view = lyricsView ?: return
        val p = params ?: return
        runCatching {
            if (remap) applyStyle(view)
            if (calculateLayout(view, p, remap)) windowManager.updateViewLayout(view, p)
        }.onFailure {
            Log.w("FloatingLyricsWindow", "Unable to update overlay geometry", it)
            hideAfterFailure()
        }
    }

    /** Coordinates are relative to WindowManager's inset-fitted frame, not the physical display. */
    @Suppress("DEPRECATION")
    private fun availableSize(view: View): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        // Before WindowMetrics, the attached view exposes the display frame fitted by WM.
        // Subtracting rootWindowInsets again would double-count already fitted system bars.
        if (view.isAttachedToWindow) {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            if (!frame.isEmpty) return frame.width() to frame.height()
        }
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return size.x.coerceAtLeast(1) to size.y.coerceAtLeast(1)
    }

    private fun calculateLayout(view: TextView, p: WindowManager.LayoutParams, remap: Boolean): Boolean {
        val (availableWidth, availableHeight) = availableSize(view)
        val width = FloatingWindowGeometry.width(
            availableWidth, FloatingLyricsStyleStore.getStyle(context).maxWidthPercent
        )
        if (view.minWidth != width) view.minWidth = width
        if (view.maxWidth != width) view.maxWidth = width
        if (view.maxHeight != availableHeight) view.maxHeight = availableHeight
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
        )
        val next = FloatingWindowGeometry(availableWidth, availableHeight, width, view.measuredHeight)
        val previous = geometry
        val areaChanged = previous == null || previous.availableWidth != availableWidth ||
            previous.availableHeight != availableHeight
        if (areaChanged) gestureActive = false
        val relative = position ?: run {
            val (x, y) = FloatingLyricsStyleStore.getPosition(context)
            val initial = if (FloatingLyricsStyleStore.hasLegacyPosition(context)) {
                next.migrate(x, y, touchSlop)
            } else next.position(0, y, FloatingPosition(0.5f, 0f)).copy(horizontal = 0.5f)
            // Pre-30 must wait for the fitted frame before migrating pixels permanently.
            if (Build.VERSION.SDK_INT >= 30 || view.isAttachedToWindow) {
                position = initial
                FloatingLyricsStyleStore.saveRelativePosition(context, initial)
            }
            initial
        }
        val (x, y) = if (remap || areaChanged || previous.width != width) {
            next.coordinates(relative)
        } else p.x.coerceIn(0, next.travelX) to p.y.coerceIn(0, next.travelY)
        val changed = p.width != width || p.x != x || p.y != y
        p.width = width
        p.x = x
        p.y = y
        geometry = next
        return changed
    }

    private fun updateWindowBehavior(): Boolean {
        val view = lyricsView ?: return true
        val p = params ?: return true
        return runCatching {
            p.flags = windowFlagsForCurrentBehavior()
            windowManager.updateViewLayout(view, p)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    private fun hideAfterFailure() {
        hide(notifyVisibilityChanged = true)
    }

    private fun windowFlagsForCurrentBehavior(): Int {
        return if (FloatingLyricsStyleStore.isClickThrough(context)) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun dp(value: Int): Int {
        return (value * windowContext.resources.displayMetrics.density).toInt()
    }

}
