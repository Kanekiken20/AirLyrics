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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
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
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt
class FloatingLyricsWindow(
    private val context: Context,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    companion object {
        @Volatile
        private var activeAttachedView: WeakReference<View>? = null

        @Volatile
        private var activeAttachedDockView: WeakReference<View>? = null
    }

    private val windowContext = if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        context.createDisplayContext(manager.defaultDisplay)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val holdGesture = StationaryHoldGesture(
        holdDurationMs = { 3000L },
        driftThresholdPx = 22f * context.resources.displayMetrics.density
    ) {
        val view = lyricsView ?: return@StationaryHoldGesture
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val nextPreset = com.andsi.airlyrics.floating.FloatingLyricsStyleStore(context).cycleSizePreset()
        FloatingLyricsStyleStore.setTextSize(context, nextPreset.textSizeSp)
        FloatingLyricsStyleStore.setMaxWidthPercent(context, (nextPreset.maxWidthFraction * 100f).toInt())
        applyStyle()
    }

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
    private var activeScaleXSpring: SpringAnimation? = null
    private var activeScaleYSpring: SpringAnimation? = null
    private var activeDockScaleXSpring: SpringAnimation? = null
    private var activeDockScaleYSpring: SpringAnimation? = null
    private var insetLeft = 0
    private var insetTop = 0
    private var animatingSpring = false
    private var edgeHapticFired = false
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
        if (currentDockSide != DockSide.NONE && dockTabView != null) {
            val savedY = (displayHeight() * AppSettingsStore.getFloatingYFraction(context)).roundToInt()
            showDockTab(currentDockSide, savedY)
        }
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
            if (it.isAttachedToWindow) return true
            val refreshed = applyStyle()
            if (refreshed) onVisibilityChanged(true)
            return refreshed
        }

        val view = FloatingLyricsTextView(windowContext).apply {
            text = this@FloatingLyricsWindow.context.getString(R.string.ui_waiting_for_media_message)
            includeFontPadding = false
        }

        @Suppress("RtlHardcoded")
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlagsForCurrentBehavior(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
            }
        }

        view.setOnTouchListener(::handleTouch)

        return runCatching {
            lyricsView = view
            params = layoutParams
            position = FloatingLyricsStyleStore.getRelativePosition(context)
            applyStyle(view)
            calculateLayout(view, layoutParams, remap = true)

            activeAttachedView?.get()?.let { oldView ->
                if (oldView !== view && oldView.isAttachedToWindow) {
                    runCatching { windowManager.removeViewImmediate(oldView) }
                }
            }
            windowManager.addView(view, layoutParams)
            activeAttachedView = WeakReference(view)

            view.onWindowConfigurationChanged = ::onConfigurationChanged
            view.addOnLayoutChangeListener(layoutListener)
            view.setOnApplyWindowInsetsListener { _, insets ->
                scheduleLayout()
                insets
            }
            view.requestApplyInsets()
            if (currentDockSide != DockSide.NONE) {
                view.visibility = View.GONE
                val savedTabY = (displayHeight() * AppSettingsStore.getFloatingYFraction(context)).roundToInt()
                showDockTab(currentDockSide, savedTabY)
            } else {
                playPuffyPopIn(view)
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
        cancelActiveSprings()
        velocityTracker?.recycle()
        velocityTracker = null
        holdGesture.cancel()
        val view = lyricsView
        if (activeAttachedView?.get() === view) {
            activeAttachedView = null
        }
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
                cancelActiveSprings()
                gestureActive = true
                dragging = false
                edgeHapticFired = false
                startX = p.x - insetLeft
                startY = p.y - insetTop
                touchStartX = event.rawX
                touchStartY = event.rawY
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                holdGesture.onTouchDown(event, enabled = !isLocked)
                if (!isLocked) {
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80L).start()
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                holdGesture.onTouchMove(event)
                if (isLocked || !gestureActive) {
                    true
                } else {
                    if (holdGesture.wasTriggered()) return true
                    if (!dragging && isClick(event)) return true
                    dragging = true
                    val bounds = geometry ?: return true
                    val dx = (event.rawX - touchStartX).toInt()
                    val rawLocalX = startX + dx
                    val maxOverhang = (p.width * 0.58f).roundToInt().coerceAtLeast(dp(48))

                    // Rubber-band resistance when pushing past screen edges
                    val candidateLocalX = when {
                        rawLocalX < 0 -> {
                            val resisted = (rawLocalX * 0.72f).roundToInt()
                            resisted.coerceAtLeast(-maxOverhang)
                        }
                        rawLocalX > bounds.travelX -> {
                            val over = rawLocalX - bounds.travelX
                            val resisted = bounds.travelX + (over * 0.72f).roundToInt()
                            resisted.coerceAtMost(bounds.travelX + maxOverhang)
                        }
                        else -> rawLocalX
                    }
                    val candidateLocalY = (startY + (event.rawY - touchStartY).toInt())
                        .coerceIn(0, bounds.travelY)

                    p.x = insetLeft + candidateLocalX
                    p.y = insetTop + candidateLocalY

                    // Playful squash-and-stretch feedback against left/right boundaries
                    val overhangRatio = when {
                        candidateLocalX < 0 -> (-candidateLocalX.toFloat() / maxOverhang.coerceAtLeast(1)).coerceIn(0f, 1f)
                        candidateLocalX > bounds.travelX -> ((candidateLocalX - bounds.travelX).toFloat() / maxOverhang.coerceAtLeast(1)).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (overhangRatio > 0f) {
                        view.animate().cancel()
                        view.scaleX = 1f - 0.14f * overhangRatio
                        view.scaleY = 1f + 0.09f * overhangRatio
                        view.alpha = 1f - 0.28f * overhangRatio
                        if (!edgeHapticFired && overhangRatio >= 0.42f) {
                            edgeHapticFired = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    } else {
                        edgeHapticFired = false
                        view.animate().cancel()
                        view.scaleX = 1.02f
                        view.scaleY = 0.98f
                        view.alpha = 1f
                    }

                    runCatching { windowManager.updateViewLayout(view, p) }
                        .onFailure { hideAfterFailure() }
                        .isSuccess
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return true
                val holdTriggered = holdGesture.wasTriggered()
                holdGesture.cancel()
                if (holdTriggered) {
                    holdGesture.resetTriggerFlag()
                    velocityTracker?.recycle()
                    velocityTracker = null
                    gestureActive = false
                    playPuffyScaleRestore(view)
                    return true
                }
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (!dragging && isClick(event)) {
                    playPuffyScaleRestore(view)
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
                    val bounds = geometry
                    val screenW = bounds?.availableWidth ?: displayWidth()
                    val currentLocalX = p.x - insetLeft
                    val collapse = FloatingGesturePhysics.shouldCollapseOnRelease(
                        velocityX = vx,
                        cardX = currentLocalX,
                        cardWidth = p.width,
                        screenWidth = screenW,
                        edgeThresholdPx = dp(FloatingGesturePhysics.EDGE_PROXIMITY_DP)
                    )
                    when (collapse) {
                        FloatingGesturePhysics.CollapseEdge.LEFT -> collapseToSide(DockSide.LEFT, vx)
                        FloatingGesturePhysics.CollapseEdge.RIGHT -> collapseToSide(DockSide.RIGHT, vx)
                        null -> {
                            playPuffyScaleRestore(view)
                            if (bounds != null) {
                                val targetLocalX = currentLocalX.coerceIn(0, bounds.travelX)
                                p.y = insetTop + (p.y - insetTop).coerceIn(0, bounds.travelY)
                                val targetWindowX = insetLeft + targetLocalX
                                if (p.x != targetWindowX) {
                                    springSettleX(view, p, targetWindowX.toFloat(), vx * 0.35f)
                                } else {
                                    saveDraggedPosition(p)
                                }
                            }
                        }
                    }
                }
                gestureActive = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                holdGesture.cancel()
                velocityTracker?.recycle()
                velocityTracker = null
                playPuffyScaleRestore(view)
                if (gestureActive) scheduleLayout(remap = true)
                gestureActive = false
                true
            }
            else -> true
        }
    }

    private fun cancelActiveSprings() {
        animatingSpring = false
        activeSpringX?.cancel()
        activeSpringX = null
        activeScaleXSpring?.cancel()
        activeScaleXSpring = null
        activeScaleYSpring?.cancel()
        activeScaleYSpring = null
        activeDockScaleXSpring?.cancel()
        activeDockScaleXSpring = null
        activeDockScaleYSpring?.cancel()
        activeDockScaleYSpring = null
    }

    private fun playPuffyPopIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.84f
        view.scaleY = 0.84f
        view.animate()
            .alpha(1f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        activeScaleXSpring?.cancel()
        activeScaleYSpring?.cancel()
        activeScaleXSpring = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
            start()
        }
        activeScaleYSpring = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
            start()
        }
    }

    private fun playPuffyScaleRestore(view: View) {
        view.animate().alpha(1f).setDuration(140L).start()
        activeScaleXSpring?.cancel()
        activeScaleYSpring?.cancel()
        activeScaleXSpring = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_SETTLE_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_SETTLE_DAMPING
            start()
        }
        activeScaleYSpring = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_SETTLE_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_SETTLE_DAMPING
            start()
        }
    }

    private fun springSettleX(view: View, p: WindowManager.LayoutParams, targetX: Float, startVelocity: Float) {
        activeSpringX?.cancel()
        animatingSpring = true
        activeSpringX = SpringAnimation(FloatValueHolder(p.x.toFloat())).apply {
            setStartVelocity(startVelocity)
            spring = SpringForce(targetX).apply {
                stiffness = FloatingGesturePhysics.PUFFY_SETTLE_STIFFNESS
                dampingRatio = FloatingGesturePhysics.PUFFY_SETTLE_DAMPING
            }
            addUpdateListener { _, value, _ ->
                if (!view.isAttachedToWindow) return@addUpdateListener
                p.x = value.roundToInt()
                runCatching { windowManager.updateViewLayout(view, p) }
            }
            addEndListener { _, canceled, _, _ ->
                animatingSpring = false
                if (!canceled && lyricsView != null) {
                    saveDraggedPosition(p)
                }
            }
            start()
        }
    }

    private fun collapseToSide(side: DockSide, initialVelocityX: Float) {
        val view = lyricsView ?: return
        val p = params ?: return
        val fullW = displayWidth()
        val startX = p.x.toFloat()
        val targetX = if (side == DockSide.LEFT) -p.width.toFloat() else fullW.toFloat()

        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        view.animate()
            .alpha(0f)
            .scaleX(0.76f)
            .scaleY(1.06f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        activeSpringX?.cancel()
        animatingSpring = true
        var completed = false
        val flingBoost = if (side == DockSide.LEFT) {
            initialVelocityX.coerceAtMost(-700f) * 0.75f
        } else {
            initialVelocityX.coerceAtLeast(700f) * 0.75f
        }
        activeSpringX = SpringAnimation(FloatValueHolder(startX)).apply {
            setStartVelocity(flingBoost)
            spring = SpringForce(targetX).apply {
                dampingRatio = FloatingGesturePhysics.PUFFY_DOCK_DAMPING
                stiffness = FloatingGesturePhysics.PUFFY_DOCK_STIFFNESS
            }
            addUpdateListener { anim, value, _ ->
                if (!view.isAttachedToWindow) return@addUpdateListener
                p.x = value.roundToInt()
                runCatching { windowManager.updateViewLayout(view, p) }
                val clearedEdge = if (side == DockSide.LEFT) {
                    value <= -p.width * 0.82f
                } else {
                    value >= fullW - p.width * 0.18f
                }
                if (!completed && clearedEdge) {
                    completed = true
                    anim.cancel()
                    finishCollapse(side, p.y)
                }
            }
            addEndListener { _, canceled, _, _ ->
                animatingSpring = false
                if (!completed && !canceled && lyricsView != null) {
                    completed = true
                    finishCollapse(side, p.y)
                }
            }
            start()
        }
    }

    private fun finishCollapse(side: DockSide, yPx: Int) {
        animatingSpring = false
        val view = lyricsView ?: return
        view.visibility = View.GONE
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        currentDockSide = side
        val screenH = displayHeight().coerceAtLeast(1)
        AppSettingsStore.setFloatingYFraction(context, yPx.toFloat() / screenH.toFloat())
        AppSettingsStore.setFloatingCollapsedSide(context, side.name)
        showDockTab(side, yPx)
    }

    @Suppress("RtlHardcoded")
    private fun showDockTab(side: DockSide, yPx: Int) {
        removeDockTab()
        val screenW = displayWidth()
        val screenH = displayHeight()
        val pillWidth = dp(40)
        val pillHeight = dp(78)
        val outerR = dp(26).toFloat()
        val edgeR = dp(8).toFloat()

        val pillBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#EB181D2B"))
            setStroke(dp(1) + 1, Color.parseColor("#6696B8FF"))
            cornerRadii = if (side == DockSide.LEFT) {
                floatArrayOf(edgeR, edgeR, outerR, outerR, outerR, outerR, edgeR, edgeR)
            } else {
                floatArrayOf(outerR, outerR, edgeR, edgeR, edgeR, edgeR, outerR, outerR)
            }
        }

        val tab = FrameLayout(windowContext).apply {
            background = pillBackground
            elevation = dp(12).toFloat()
            scaleX = 0.55f
            scaleY = 0.55f
            alpha = 0f
        }

        val label = TextView(windowContext).apply {
            text = if (side == DockSide.LEFT) "\u266A\n\u203A" else "\u266A\n\u2039"
            setTextColor(Color.parseColor("#F5E2ECFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 0.85f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        tab.addView(label)

        val insetEdge = dp(2)
        val targetX = if (side == DockSide.LEFT) insetEdge else (screenW - pillWidth - insetEdge).coerceAtLeast(0)
        val minY = (insetTop + dp(24)).coerceAtLeast(dp(32))
        val maxTargetY = (screenH - pillHeight - dp(40)).coerceAtLeast(minY)
        val clampedY = yPx.coerceIn(minY, maxTargetY)

        val p = WindowManager.LayoutParams(
            pillWidth,
            pillHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
            }
            x = targetX
            y = clampedY
        }

        var downX = 0f
        var downY = 0f
        var startTabY = 0
        var tabDragging = false

        tab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTabY = p.y
                    tabDragging = false
                    v.animate().scaleX(0.88f).scaleY(1.08f).setDuration(85L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val pulledInward = if (side == DockSide.LEFT) dx > dp(18) else dx < -dp(18)
                    if (pulledInward) {
                        expandFromSide(side)
                        return@setOnTouchListener true
                    }
                    if (!tabDragging && abs(dy) > touchSlop) {
                        tabDragging = true
                    }
                    if (tabDragging) {
                        p.y = (startTabY + dy).roundToInt().coerceIn(minY, maxTargetY)
                        runCatching { windowManager.updateViewLayout(tab, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tabDragging) {
                        expandFromSide(side)
                    } else {
                        AppSettingsStore.setFloatingYFraction(context, p.y.toFloat() / screenH.coerceAtLeast(1).toFloat())
                        activeDockScaleXSpring?.cancel()
                        activeDockScaleYSpring?.cancel()
                        activeDockScaleXSpring = SpringAnimation(v, DynamicAnimation.SCALE_X, 1f).apply {
                            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
                            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
                            start()
                        }
                        activeDockScaleYSpring = SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f).apply {
                            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
                            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
                            start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    true
                }
                else -> false
            }
        }

        runCatching {
            activeAttachedDockView?.get()?.let { stale ->
                if (stale !== tab && stale.isAttachedToWindow) {
                    runCatching { windowManager.removeViewImmediate(stale) }
                }
            }
            windowManager.addView(tab, p)
            dockTabView = tab
            dockTabParams = p
            activeAttachedDockView = WeakReference(tab)

            tab.animate().alpha(1f).setDuration(160L).start()
            activeDockScaleXSpring?.cancel()
            activeDockScaleYSpring?.cancel()
            activeDockScaleXSpring = SpringAnimation(tab, DynamicAnimation.SCALE_X, 1f).apply {
                spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
                spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
                start()
            }
            activeDockScaleYSpring = SpringAnimation(tab, DynamicAnimation.SCALE_Y, 1f).apply {
                spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
                spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
                start()
            }
        }
    }

    private fun removeDockTab() {
        activeDockScaleXSpring?.cancel()
        activeDockScaleXSpring = null
        activeDockScaleYSpring?.cancel()
        activeDockScaleYSpring = null
        dockTabView?.let { tab ->
            runCatching { windowManager.removeView(tab) }
            if (activeAttachedDockView?.get() === tab) {
                activeAttachedDockView = null
            }
        }
        dockTabView = null
        dockTabParams = null
    }

    private fun expandFromSide(side: DockSide) {
        val view = lyricsView ?: return
        val p = params ?: return
        val bounds = geometry
        val availW = bounds?.availableWidth ?: displayWidth()
        val tabY = dockTabParams?.y ?: p.y

        removeDockTab()
        currentDockSide = DockSide.NONE
        AppSettingsStore.setFloatingCollapsedSide(context, DockSide.NONE.name)

        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        view.visibility = View.VISIBLE

        val marginPx = dp(12)
        val maxLocalTargetX = (availW - p.width - marginPx).coerceAtLeast(0)
        val targetLocalX = if (side == DockSide.LEFT) marginPx.coerceAtMost(maxLocalTargetX) else maxLocalTargetX
        val targetWindowX = (insetLeft + targetLocalX).toFloat()
        val startWindowX = if (side == DockSide.LEFT) {
            insetLeft - p.width * 0.65f
        } else {
            insetLeft + availW - p.width * 0.35f
        }

        val targetLocalY = bounds?.let { (tabY - insetTop).coerceIn(0, it.travelY) } ?: (tabY - insetTop).coerceAtLeast(0)
        p.x = startWindowX.roundToInt()
        p.y = insetTop + targetLocalY
        runCatching { windowManager.updateViewLayout(view, p) }

        cancelActiveSprings()
        animatingSpring = true
        view.alpha = 0f
        view.scaleX = 0.72f
        view.scaleY = 0.78f
        view.animate()
            .alpha(1f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        activeScaleXSpring = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
            start()
        }
        activeScaleYSpring = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
            spring.dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
            start()
        }
        activeSpringX = SpringAnimation(FloatValueHolder(startWindowX)).apply {
            spring = SpringForce(targetWindowX).apply {
                dampingRatio = FloatingGesturePhysics.PUFFY_POP_DAMPING
                stiffness = FloatingGesturePhysics.PUFFY_POP_STIFFNESS
            }
            addUpdateListener { _, value, _ ->
                if (!view.isAttachedToWindow) return@addUpdateListener
                p.x = value.roundToInt()
                runCatching { windowManager.updateViewLayout(view, p) }
            }
            addEndListener { _, canceled, _, _ ->
                animatingSpring = false
                if (!canceled && lyricsView != null) {
                    saveDraggedPosition(p)
                }
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
        val localX = (p.x - insetLeft).coerceIn(0, bounds.travelX)
        val localY = (p.y - insetTop).coerceIn(0, bounds.travelY)
        position = bounds.position(localX, localY, previous).also {
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

    @Suppress("DEPRECATION")
    private fun displayWidth(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.width().coerceAtLeast(1)
        } else {
            windowContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayHeight(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.height().coerceAtLeast(1)
        } else {
            windowContext.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        }
    }

    /** Returns the safe inset-fitted frame in physical display coordinates. */
    @Suppress("DEPRECATION")
    private fun availableFrame(view: View): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val width = (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1)
            val height = (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
            return Rect(insets.left, insets.top, insets.left + width, insets.top + height)
        }
        if (view.isAttachedToWindow) {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            if (!frame.isEmpty) return frame
        }
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return Rect(0, 0, size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
    }

    private fun calculateLayout(view: TextView, p: WindowManager.LayoutParams, remap: Boolean): Boolean {
        val frame = availableFrame(view)
        insetLeft = frame.left
        insetTop = frame.top
        val availableWidth = frame.width().coerceAtLeast(1)
        val availableHeight = frame.height().coerceAtLeast(1)
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
            if (Build.VERSION.SDK_INT >= 30 || view.isAttachedToWindow) {
                position = initial
                FloatingLyricsStyleStore.saveRelativePosition(context, initial)
            }
            initial
        }
        geometry = next

        // Never overwrite coordinates mid-drag, mid-spring, or while collapsed into the edge tab.
        if (gestureActive || animatingSpring || currentDockSide != DockSide.NONE) {
            val widthChanged = p.width != width
            p.width = width
            return widthChanged
        }

        val (localX, localY) = if (remap || areaChanged || previous?.width != width) {
            next.coordinates(relative)
        } else {
            (p.x - insetLeft).coerceIn(0, next.travelX) to (p.y - insetTop).coerceIn(0, next.travelY)
        }
        val windowX = insetLeft + localX
        val windowY = insetTop + localY
        val changed = p.width != width || p.x != windowX || p.y != windowY
        p.width = width
        p.x = windowX
        p.y = windowY
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
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return if (FloatingLyricsStyleStore.isClickThrough(context)) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base
        }
    }

    private fun dp(value: Int): Int {
        return (value * windowContext.resources.displayMetrics.density).toInt()
    }

}
