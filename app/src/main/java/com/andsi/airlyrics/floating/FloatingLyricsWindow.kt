package com.andsi.airlyrics.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
import android.view.VelocityTracker
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.FloatingOverlayStore
import com.andsi.airlyrics.settings.store.FloatingOverlayStore.DockSide
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private var attachedView: View? = null
    private var dockHandle: TextView? = null
    private var overlayGeometry = FloatingOverlayStore.Geometry()
    private var editor: FloatingOverlayEditControls? = null
    private var editorParams: WindowManager.LayoutParams? = null
    private var resizeParams: WindowManager.LayoutParams? = null
    private var editSnapshot: EditSnapshot? = null
    private var editTextSizeSp: Float? = null
    private var velocityTracker: VelocityTracker? = null
    private val holdGesture = StationaryHoldGesture(touchSlop)
    private var transitionGeneration = 0
    private var transitionAnimator: ObjectAnimator? = null
    var onInteractionChanged: (() -> Unit)? = null
    var isPresentationVisible: Boolean = true
        private set
    val isDocked: Boolean get() = overlayGeometry.dockSide != DockSide.NONE
    val isEditing: Boolean get() = editSnapshot != null
    val isContentVisible: Boolean get() = isVisible && isPresentationVisible && !isDocked
    private data class EditSnapshot(
        val geometry: FloatingOverlayStore.Geometry,
        val position: FloatingPosition?,
        val textSizeSp: Float
    )
    private val holdRunnable = Runnable {
        if (holdGesture.consume() && gestureActive && !dragging && !isEditing &&
            isPresentationVisible && !FloatingLyricsStyleStore.isLocked(context) &&
            !FloatingLyricsStyleStore.isClickThrough(context)) {
            gestureActive = false
            lyricsView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            enterEditMode()
        }
    }
    private var gestureActive = false
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
        cancelGesture()
        if (isEditing) finishEditMode(commit = false)
        cancelTransition()
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
            overlayGeometry = FloatingOverlayStore.geometry(context)
            if (!FloatingOverlayStore.isDockingEnabled(context)) overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
            calculateLayout(view, layoutParams, remap = true)
            if (isPresentationVisible) attachCurrentSurface()
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(R.string.ui_adjustment_mode)))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    return if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) enterEditMode()
                    else super.performAccessibilityAction(host, action, args)
                }
            }
            view.onWindowConfigurationChanged = ::onConfigurationChanged
            view.addOnLayoutChangeListener(layoutListener)
            view.setOnApplyWindowInsetsListener { _, insets ->
                scheduleLayout()
                insets
            }
            view.requestApplyInsets()
            onVisibilityChanged(true)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun hide(notifyVisibilityChanged: Boolean = true): Boolean {
        val view = lyricsView
        cancelGesture()
        cancelTransition()
        removeEditor()
        editSnapshot = null
        editTextSizeSp = null
        view?.removeCallbacks(refreshLayout)
        view?.removeOnLayoutChangeListener(layoutListener)
        view?.setOnApplyWindowInsetsListener(null)
        (view as? FloatingLyricsTextView)?.onWindowConfigurationChanged = null
        gestureActive = false
        remapPending = false
        geometry = null
        position = null
        val removed = detachSurface()
        dockHandle = null

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
            cancelGesture()
            if (!isEditing) {
                overlayGeometry = FloatingOverlayStore.geometry(context)
                if (!FloatingOverlayStore.isDockingEnabled(context)) {
                    cancelTransition()
                    overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
                    FloatingOverlayStore.saveGeometry(context, overlayGeometry)
                }
            }
            applyStyle(view)
            if (isPresentationVisible && isVisible && ((attachedView === view) == isDocked)) {
                attachCurrentSurface()
            }
            if (calculateLayout(view, p, remap = true) && attachedView === view) windowManager.updateViewLayout(view, p)
            updateDockLayout()
            updateEditorLayout()
            onInteractionChanged?.invoke()
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun setLocked(locked: Boolean): Boolean {
        cancelGesture()
        if (locked && isEditing) finishEditMode(commit = true)
        val previousLocked = FloatingLyricsStyleStore.isLocked(context)
        FloatingLyricsStyleStore.setLocked(context, locked)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setLocked(context, previousLocked)
        return updated
    }

    fun setClickThrough(clickThrough: Boolean): Boolean {
        cancelGesture()
        if (clickThrough && isEditing) finishEditMode(commit = true)
        val previousClickThrough = FloatingLyricsStyleStore.isClickThrough(context)
        FloatingLyricsStyleStore.setClickThrough(context, clickThrough)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setClickThrough(context, previousClickThrough)
        return updated
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val p = params ?: return false
        val isLocked = FloatingLyricsStyleStore.isLocked(context)
        if (!isPresentationVisible || isDocked) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                gestureActive = true
                dragging = false
                startX = p.x
                startY = p.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                if (!isLocked && !isEditing && FloatingOverlayStore.isHoldToEditEnabled(context)) {
                    holdGesture.start(event.rawX, event.rawY)
                    view.postDelayed(holdRunnable, StationaryHoldGesture.HOLD_DURATION_MS)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!holdGesture.move(event.rawX, event.rawY)) view.removeCallbacks(holdRunnable)
                if (isLocked || !gestureActive) return true
                if (!dragging && isClick(event)) return true
                dragging = true
                val bounds = geometry ?: return true
                val dx = (event.rawX - touchStartX).toInt() *
                    if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                val x = (startX + dx).coerceIn(0, bounds.travelX)
                val y = (startY + (event.rawY - touchStartY).toInt()).coerceIn(0, bounds.travelY)
                if (p.x != x || p.y != y) {
                    p.x = x
                    p.y = y
                    runCatching { windowManager.updateViewLayout(view, p); updateEditorLayout() }
                        .onFailure { hideAfterFailure() }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(holdRunnable)
                holdGesture.cancel()
                if (!gestureActive) { cancelGesture(); return true }
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.xVelocity ?: 0f
                val dockSide = if (!isLocked && !isEditing && dragging &&
                    FloatingOverlayStore.isDockingEnabled(context) && abs(velocity) >= dp(800)) {
                    val physicalDx = event.rawX - touchStartX
                    val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val atLeft = if (rtl) p.x == geometry?.travelX else p.x == 0
                    val atRight = if (rtl) p.x == 0 else p.x == geometry?.travelX
                    when {
                        atLeft && physicalDx < -dp(72) && velocity < 0 -> DockSide.LEFT
                        atRight && physicalDx > dp(72) && velocity > 0 -> DockSide.RIGHT
                        else -> DockSide.NONE
                    }
                } else DockSide.NONE
                if (!dragging && isClick(event)) performLyricClick(view, event, isLocked)
                if (!isLocked && dragging) {
                    if (isEditing) updateDraftPosition(p) else saveDraggedPosition(p)
                }
                cancelGesture()
                if (dockSide != DockSide.NONE) dock(dockSide)
                true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                scheduleLayout(remap = true)
                true
            }
            else -> true
        }
    }

    private fun performLyricClick(view: View, event: MotionEvent, isLocked: Boolean) {
        val textView = view as? TextView
        val layout = textView?.layout
        val text = textView?.text as? android.text.Spanned
        val x = event.x - (textView?.totalPaddingLeft ?: 0) + (textView?.scrollX ?: 0)
        val y = event.y - (textView?.totalPaddingTop ?: 0) + (textView?.scrollY ?: 0)
        val link = if (!isLocked && !isEditing && layout != null && text != null && y >= 0 && y < layout.height) {
            val line = layout.getLineForVertical(y.toInt())
            if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                val offset = layout.getOffsetForHorizontal(line, x)
                text.getSpans(offset, offset, android.text.style.URLSpan::class.java).firstOrNull()
            } else null
        } else null
        if (link != null && link.url.startsWith("https://")) {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(link.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else view.performClick()
    }

    private fun cancelGesture() {
        lyricsView?.removeCallbacks(holdRunnable)
        holdGesture.cancel()
        gestureActive = false
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun isClick(event: MotionEvent): Boolean {
        val threshold = touchSlop.toFloat()
        return abs(event.rawX - touchStartX) <= threshold &&
            abs(event.rawY - touchStartY) <= threshold
    }

    private fun applyStyle(view: TextView) {
        val style = FloatingLyricsStyleStore.getStyle(context)

        view.textSize = editTextSizeSp ?: style.textSizeSp
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

        view.background = if (style.backgroundEnabled || isEditing) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
                setColor(if (style.backgroundEnabled) AirColorUtils.withAlpha(style.backgroundColor, style.backgroundAlpha)
                    else Color.argb(210, 24, 29, 38))
                if (isEditing) setStroke(dp(2).coerceAtLeast(1), Color.WHITE)
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

    private fun updateDraftPosition(p: WindowManager.LayoutParams) {
        val bounds = geometry ?: return
        position = bounds.position(p.x, p.y, position ?: FloatingPosition(0.5f, 0.5f))
    }

    private fun updateLayout(remap: Boolean) {
        val view = lyricsView ?: return
        val p = params ?: return
        runCatching {
            if (remap) applyStyle(view)
            if (calculateLayout(view, p, remap) && attachedView === view) windowManager.updateViewLayout(view, p)
            updateDockLayout()
            updateEditorLayout()
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
            val ime = view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())
            return (metrics.bounds.width() - maxOf(insets.left, ime?.left ?: 0) - maxOf(insets.right, ime?.right ?: 0)).coerceAtLeast(1) to
                (metrics.bounds.height() - insets.top - maxOf(insets.bottom, ime?.bottom ?: 0)).coerceAtLeast(1)
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
        val (availableWidth, fullHeight) = availableSize(view)
        val availableHeight = if (isEditing) (fullHeight - dp(144)).coerceAtLeast(dp(48).coerceAtMost(fullHeight)) else fullHeight
        val width = overlayGeometry.widthFraction?.let {
            FloatingWindowGeometry.dimension(availableWidth, it, dp(144))
        } ?: FloatingWindowGeometry.width(availableWidth, FloatingLyricsStyleStore.getStyle(context).maxWidthPercent)
        val fixedHeight = overlayGeometry.heightFraction?.let {
            FloatingWindowGeometry.dimension(fullHeight, it, dp(48)).coerceAtMost(availableHeight)
        }
        if (view.minWidth != width) view.minWidth = width
        if (view.maxWidth != width) view.maxWidth = width
        if (view.maxHeight != availableHeight) view.maxHeight = availableHeight
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
        )
        val height = fixedHeight ?: view.measuredHeight.coerceAtLeast(1)
        val next = FloatingWindowGeometry(availableWidth, availableHeight, width, height)
        val previous = geometry
        val areaChanged = previous == null || previous.availableWidth != availableWidth ||
            previous.availableHeight != availableHeight
        if (areaChanged) cancelGesture()
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
        val desiredHeight = fixedHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
        val changed = p.width != width || p.height != desiredHeight || p.x != x || p.y != y
        p.width = width
        p.height = desiredHeight
        p.x = x
        p.y = y
        geometry = next
        return changed
    }

    private fun updateWindowBehavior(): Boolean {
        val view = lyricsView ?: return true
        val p = params ?: return true
        return runCatching {
            val flags = windowFlagsForCurrentBehavior()
            if (p.flags != flags) {
                p.flags = flags
                if (attachedView === view) windowManager.updateViewLayout(view, p)
            }
            dockHandle?.let { handle ->
                val hp = handle.layoutParams as? WindowManager.LayoutParams
                if (hp != null && hp.flags != flags) {
                    hp.flags = flags
                    if (attachedView === handle) windowManager.updateViewLayout(handle, hp)
                }
            }
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    /** Suppresses all app windows without changing the user's desired visibility or lyric data. */
    fun setPresentationVisible(visible: Boolean) {
        if (isPresentationVisible == visible) return
        isPresentationVisible = visible
        cancelGesture()
        cancelTransition()
        if (!visible) {
            if (isEditing) finishEditMode(commit = false)
            removeEditor()
            detachSurface()
        } else if (isVisible) {
            runCatching { attachCurrentSurface(); updateLayout(remap = true) }
                .onFailure { hideAfterFailure() }
        }
        onInteractionChanged?.invoke()
    }

    fun enterEditMode(): Boolean {
        if (!isVisible || !isPresentationVisible || FloatingLyricsStyleStore.isLocked(context) ||
            FloatingLyricsStyleStore.isClickThrough(context)) return false
        if (isEditing) return true
        cancelGesture()
        cancelTransition()
        val previousGeometry = overlayGeometry
        if (isDocked) {
            overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
            runCatching { attachCurrentSurface() }.onFailure { hideAfterFailure() }
            if (!isVisible) return false
        }
        editSnapshot = EditSnapshot(previousGeometry, position, FloatingLyricsStyleStore.getStyle(context).textSizeSp)
        editTextSizeSp = editSnapshot!!.textSizeSp
        return runCatching {
            val controls = FloatingOverlayEditControls(windowContext,
                done = { finishEditMode(commit = true) },
                cancel = { finishEditMode(commit = false) },
                reset = {
                    overlayGeometry = FloatingOverlayStore.Geometry()
                    position = FloatingPosition(0.5f, 0.2f)
                    editTextSizeSp = editSnapshot?.textSizeSp
                    updateLayout(remap = true)
                },
                smaller = { changeDraftTextSize(-2f) },
                larger = { changeDraftTextSize(2f) },
                dockLeft = { if (FloatingOverlayStore.isDockingEnabled(context)) { finishEditMode(true); dock(DockSide.LEFT) } },
                dockRight = { if (FloatingOverlayStore.isDockingEnabled(context)) { finishEditMode(true); dock(DockSide.RIGHT) } },
                resize = ::handleResize,
                resizeBy = ::resizeBy)
            editor = controls
            editorParams = auxiliaryParams()
            resizeParams = auxiliaryParams().apply { width = dp(48); height = dp(48) }
            updateLayout(remap = true)
            windowManager.addView(controls.panel, editorParams)
            windowManager.addView(controls.resizeHandle, resizeParams)
            onInteractionChanged?.invoke()
            true
        }.getOrElse {
            finishEditMode(commit = false)
            false
        }
    }

    fun finishEditMode(commit: Boolean) {
        val previous = editSnapshot ?: return
        cancelGesture()
        if (commit) {
            val view = lyricsView
            val p = params
            val bounds = geometry
            if (view != null && p != null && bounds != null) {
                val (width, height) = availableSize(view)
                position = FloatingWindowGeometry(width, height, bounds.width, bounds.height)
                    .position(p.x, p.y, position ?: FloatingPosition(0.5f, 0.5f))
            }
            FloatingOverlayStore.saveGeometry(context, overlayGeometry)
            position?.let { FloatingLyricsStyleStore.saveRelativePosition(context, it) }
            editTextSizeSp?.let { FloatingLyricsStyleStore.setTextSize(context, it) }
        } else {
            overlayGeometry = previous.geometry
            position = previous.position
        }
        editSnapshot = null
        editTextSizeSp = null
        removeEditor()
        if (isPresentationVisible) runCatching { attachCurrentSurface() }.onFailure { hideAfterFailure() }
        updateLayout(remap = true)
        onInteractionChanged?.invoke()
    }

    private fun resizeBy(widthDp: Int, heightDp: Int) {
        val bounds = geometry ?: return
        val view = lyricsView ?: return
        if (!isEditing) return
        val fullHeight = availableSize(view).second
        val width = (bounds.width + dp(widthDp)).coerceIn(dp(144).coerceAtMost(bounds.availableWidth), bounds.availableWidth)
        val height = (bounds.height + dp(heightDp)).coerceIn(dp(48).coerceAtMost(bounds.availableHeight), bounds.availableHeight)
        overlayGeometry = overlayGeometry.copy(widthFraction = width.toFloat() / bounds.availableWidth,
            heightFraction = height.toFloat() / fullHeight)
        updateLayout(remap = false)
    }

    private fun changeDraftTextSize(delta: Float) {
        editTextSizeSp = ((editTextSizeSp ?: return) + delta).coerceIn(14f, 56f)
        updateLayout(remap = true)
    }

    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeTouchX = 0f
    private var resizeTouchY = 0f
    private var resizeActive = false
    private fun handleResize(event: MotionEvent): Boolean {
        val bounds = geometry ?: return false
        if (!isEditing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                resizeActive = true
                resizeStartWidth = bounds.width
                resizeStartHeight = bounds.height
                resizeTouchX = event.rawX
                resizeTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> if (resizeActive) {
                val fullHeight = availableSize(lyricsView ?: return true).second
                val rtl = lyricsView?.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val width = (resizeStartWidth + (event.rawX - resizeTouchX).toInt() * if (rtl) -1 else 1)
                    .coerceIn(dp(144).coerceAtMost(bounds.availableWidth), bounds.availableWidth)
                val height = (resizeStartHeight + (event.rawY - resizeTouchY).toInt())
                    .coerceIn(dp(48).coerceAtMost(bounds.availableHeight), bounds.availableHeight)
                overlayGeometry = overlayGeometry.copy(
                    widthFraction = width.toFloat() / bounds.availableWidth,
                    heightFraction = height.toFloat() / fullHeight)
                updateLayout(remap = false)
            }
            MotionEvent.ACTION_UP -> { resizeActive = false; editor?.resizeHandle?.performClick() }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> resizeActive = false
        }
        return true
    }

    /** Physical left/right docking is intentionally independent of the lyric's text direction. */
    fun dock(side: DockSide) {
        if (side == DockSide.NONE || !isContentVisible || isEditing ||
            !FloatingOverlayStore.isDockingEnabled(context) || FloatingLyricsStyleStore.isClickThrough(context)) return
        cancelGesture()
        cancelTransition()
        val view = lyricsView ?: return
        val target = if (side == DockSide.LEFT) -view.width.toFloat() else view.width.toFloat()
        val generation = transitionGeneration
        fun complete() {
            if (generation != transitionGeneration || !isVisible || !isPresentationVisible) return
            if (!FloatingOverlayStore.isDockingEnabled(context)) {
                view.translationX = 0f
                return
            }
            val (_, areaHeight) = availableSize(view)
            val p = params ?: return
            val height = geometry?.height ?: view.height
            val vertical = if (areaHeight > dp(64))
                ((p.y + height / 2f - dp(32)) / (areaHeight - dp(64))).coerceIn(0f, 1f) else 0.5f
            overlayGeometry = overlayGeometry.copy(dockSide = side, dockVerticalFraction = vertical)
            FloatingOverlayStore.saveGeometry(context, overlayGeometry)
            view.translationX = 0f
            runCatching { attachCurrentSurface() }.onFailure { hideAfterFailure() }
            onInteractionChanged?.invoke()
        }
        if (animationsEnabled() && view.isAttachedToWindow) {
            animateTranslation(view, view.translationX, target) { complete() }
        } else complete()
    }

    fun expand(animate: Boolean = true) {
        if (!isDocked) return
        val side = overlayGeometry.dockSide
        cancelTransition()
        overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
        FloatingOverlayStore.saveGeometry(context, overlayGeometry)
        runCatching {
            if (isPresentationVisible) attachCurrentSurface()
            updateLayout(remap = true)
            val view = lyricsView ?: return
            if (animate && isPresentationVisible && animationsEnabled()) {
                val from = if (side == DockSide.LEFT) -view.width.toFloat() else view.width.toFloat()
                animateTranslation(view, from, 0f)
            }
        }.onFailure { hideAfterFailure() }
        onInteractionChanged?.invoke()
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode &&
        !LyricsDisplaySettingsStore.isMinimalMotion(context)

    private fun animateTranslation(view: View, from: Float, to: Float, complete: () -> Unit = {}) {
        // Own a separate animator: the lyric renderer legitimately cancels View.animate() on line changes.
        val generation = transitionGeneration
        transitionAnimator = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, from, to).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != transitionGeneration) return
                    transitionAnimator = null
                    complete()
                }
            })
            start()
        }
    }

    private fun cancelTransition() {
        transitionGeneration++
        transitionAnimator?.cancel()
        transitionAnimator = null
        lyricsView?.translationX = 0f
    }

    private fun auxiliaryParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setFitInsetsIgnoringVisibility(true)
        }
    }

    private fun createDockHandle(): TextView = TextView(windowContext).apply {
        gravity = Gravity.CENTER
        textSize = 24f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(230, 24, 29, 38))
        }
        contentDescription = context.getString(R.string.ui_overlay_expand)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setOnClickListener { expand() }
        var downX = 0f
        var downY = 0f
        var active = false
        val handleHold = Runnable {
            if (active && FloatingOverlayStore.isHoldToEditEnabled(context)) {
                active = false
                if (!FloatingLyricsStyleStore.isLocked(context)) enterEditMode()
            }
        }
        setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = true
                    downX = event.rawX
                    downY = event.rawY
                    if (!FloatingLyricsStyleStore.isLocked(context))
                        handle.postDelayed(handleHold, StationaryHoldGesture.HOLD_DURATION_MS)
                }
                MotionEvent.ACTION_MOVE -> if (active) {
                    val dx = event.rawX - downX
                    if (abs(dx) > touchSlop || abs(event.rawY - downY) > touchSlop) handle.removeCallbacks(handleHold)
                    val inward = if (overlayGeometry.dockSide == DockSide.LEFT) dx else -dx
                    if (inward > dp(24)) {
                        active = false
                        handle.removeCallbacks(handleHold)
                        expand()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handle.removeCallbacks(handleHold)
                    if (active) { active = false; handle.performClick() }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    active = false
                    handle.removeCallbacks(handleHold)
                }
            }
            true
        }
        // A detached handle must not retain a pending long hold across a lock/unlock handoff.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { active = false; v.removeCallbacks(handleHold) }
        })
    }

    private fun attachCurrentSurface() {
        val view = lyricsView ?: return
        if (!isPresentationVisible) return
        val surface: View
        val surfaceParams: WindowManager.LayoutParams
        if (isDocked) {
            val handle = dockHandle ?: createDockHandle().also { dockHandle = it }
            handle.text = if (overlayGeometry.dockSide == DockSide.LEFT) "›" else "‹"
            surface = handle
            surfaceParams = (handle.layoutParams as? WindowManager.LayoutParams) ?: auxiliaryParams()
            surfaceParams.flags = windowFlagsForCurrentBehavior()
            surfaceParams.width = dp(48)
            surfaceParams.height = dp(64)
            positionDockHandle(surfaceParams)
        } else {
            surface = view
            surfaceParams = params ?: return
            applyStyle(view)
            calculateLayout(view, surfaceParams, remap = true)
        }
        if (attachedView === surface) {
            windowManager.updateViewLayout(surface, surfaceParams)
        } else {
            detachSurface()
            windowManager.addView(surface, surfaceParams)
            attachedView = surface
        }
    }

    private fun detachSurface(): Boolean {
        val previous = attachedView ?: return true
        attachedView = null
        return runCatching { windowManager.removeView(previous) }.isSuccess
    }

    private fun positionDockHandle(p: WindowManager.LayoutParams) {
        val view = lyricsView ?: return
        val (width, height) = availableSize(view)
        p.x = if (overlayGeometry.dockSide == DockSide.LEFT) 0 else (width - p.width).coerceAtLeast(0)
        p.y = ((height - p.height).coerceAtLeast(0) * overlayGeometry.dockVerticalFraction).roundToInt()
    }

    private fun updateDockLayout() {
        val handle = dockHandle ?: return
        if (attachedView !== handle) return
        val p = handle.layoutParams as? WindowManager.LayoutParams ?: return
        val previousX = p.x
        val previousY = p.y
        positionDockHandle(p)
        if (previousX != p.x || previousY != p.y) windowManager.updateViewLayout(handle, p)
    }

    private fun updateEditorLayout() {
        val controls = editor ?: return
        val view = lyricsView ?: return
        val p = params ?: return
        val (width, height) = availableSize(view)
        editorParams?.let { ep ->
            val previousWidth = ep.width
            val previousHeight = ep.height
            val previousX = ep.x
            val previousY = ep.y
            ep.width = width.coerceAtMost(dp(360))
            ep.height = dp(144).coerceAtMost(height)
            ep.x = (width - ep.width) / 2
            ep.y = (height - ep.height).coerceAtLeast(0)
            if (controls.panel.isAttachedToWindow && (previousWidth != ep.width || previousHeight != ep.height ||
                previousX != ep.x || previousY != ep.y)) windowManager.updateViewLayout(controls.panel, ep)
        }
        resizeParams?.let { rp ->
            val previousX = rp.x
            val previousY = rp.y
            // Params.x is relative to START; auxiliary windows use physical LEFT.
            val physicalX = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) width - p.x - p.width else p.x
            rp.x = (if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) physicalX else physicalX + p.width - dp(48))
                .coerceIn(0, (width - dp(48)).coerceAtLeast(0))
            rp.y = (p.y + (geometry?.height ?: view.height) - dp(48))
                .coerceIn(0, (height - dp(48)).coerceAtLeast(0))
            if (controls.resizeHandle.isAttachedToWindow && (previousX != rp.x || previousY != rp.y))
                windowManager.updateViewLayout(controls.resizeHandle, rp)
        }
    }

    private fun removeEditor() {
        resizeActive = false
        editor?.let {
            runCatching { windowManager.removeView(it.panel) }
            runCatching { windowManager.removeView(it.resizeHandle) }
        }
        editor = null
        editorParams = null
        resizeParams = null
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
