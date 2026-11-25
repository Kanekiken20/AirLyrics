package com.andsi.airlyrics.floating

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
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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

private val defaultFloatingPosition = FloatingPosition(0.5f, 0.56f)

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
    private var surface: FrameLayout? = null
    private var surfaceParams: WindowManager.LayoutParams? = null
    private var surfaceAttached = false
    private val shell = GradientDrawable()
    private var cardFill = Color.TRANSPARENT
    private var cardRadius = 0f
    private var textWatcher: TextWatcher? = null
    private var lyricNeedsMeasure = false
    private var dockHandle: TextView? = null
    private var overlayGeometry = FloatingOverlayStore.Geometry()
    private var editor: FloatingOverlayEditControls? = null
    private var editorParams: WindowManager.LayoutParams? = null
    private var resizeParams: WindowManager.LayoutParams? = null
    private var editSnapshot: EditSnapshot? = null
    private var editTextSizeSp: Float? = null
    private val holdGesture = StationaryHoldGesture(touchSlop)
    private var transition: SpringAnimation? = null
    private var drawnFrame: SurfaceFrame? = null
    private var caughtFrame: SurfaceFrame? = null
    private var pendingFrame: SurfaceFrame? = null
    private var dragVelocity: VelocityTracker? = null
    private val drawDragFrame = Runnable { flushDragFrame() }
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
    private data class SurfaceFrame(val bounds: Rect, val dockAmount: Float)
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
        if (!dragging && caughtFrame == null && transition == null) scheduleLayout()
    }

    fun onConfigurationChanged() {
        cancelGesture()
        if (isEditing) finishEditMode(commit = false)
        cancelTransition()
        caughtFrame = null
        scheduleLayout(remap = true)
    }

    private fun scheduleLayout(remap: Boolean = false) {
        val view = surface ?: return
        remapPending = remapPending || remap
        view.removeCallbacks(refreshLayout)
        if (resizeActive) view.postOnAnimation(refreshLayout) else view.post(refreshLayout)
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
            dockHandle = createDockHandle()
            surface = createSurface(view, requireNotNull(dockHandle))
            surfaceParams = auxiliaryParams().apply { flags = windowFlagsForCurrentBehavior() }
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
            textWatcher = view.doAfterTextChanged {
                lyricNeedsMeasure = true
                if (!dragging && transition == null && caughtFrame == null) scheduleLayout()
            }
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
        surface?.removeCallbacks(refreshLayout)
        textWatcher?.let { view?.removeTextChangedListener(it) }
        textWatcher = null
        lyricNeedsMeasure = false
        view?.removeOnLayoutChangeListener(layoutListener)
        view?.setOnApplyWindowInsetsListener(null)
        (view as? FloatingLyricsTextView)?.onWindowConfigurationChanged = null
        gestureActive = false
        remapPending = false
        geometry = null
        position = null
        val removed = detachSurface()
        dockHandle = null
        surface = null
        surfaceParams = null
        drawnFrame = null
        caughtFrame = null

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
            caughtFrame = null
            if (!isEditing) {
                overlayGeometry = FloatingOverlayStore.geometry(context)
                if (!FloatingOverlayStore.isDockingEnabled(context)) {
                    cancelTransition()
                    overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
                    FloatingOverlayStore.saveGeometry(context, overlayGeometry)
                }
            }
            applyStyle(view)
            calculateLayout(view, p, remap = true)
            if (isPresentationVisible && transition == null && caughtFrame == null) attachCurrentSurface()
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
                cancelTransition()
                dragVelocity = VelocityTracker.obtain().also { it.addMovement(event) }
                gestureActive = true
                dragging = false
                startX = p.x
                startY = p.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                if (!isLocked && !isEditing && FloatingOverlayStore.isHoldToEditEnabled(context)) {
                    holdGesture.start(event.rawX, event.rawY)
                    view.postDelayed(holdRunnable, StationaryHoldGesture.HOLD_DURATION_MS)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!holdGesture.move(event.rawX, event.rawY)) view.removeCallbacks(holdRunnable)
                if (isLocked || !gestureActive) return true
                dragVelocity?.addMovement(event)
                if (!dragging && isClick(event)) return true
                if (!dragging) surface?.removeCallbacks(refreshLayout)
                dragging = true
                val bounds = geometry ?: return true
                val dx = (event.rawX - touchStartX).toInt() *
                    if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                val x = (startX + dx).coerceIn(0, bounds.travelX)
                val y = (startY + (event.rawY - touchStartY).toInt()).coerceIn(0, bounds.travelY)
                if (p.x != x || p.y != y) {
                    p.x = x
                    p.y = y
                    expandedFrame()?.let(::queueDragFrame)
                }
                // Push beyond the physical edge to dock immediately, even during a slow drag.
                // Overshoot distinguishes docking from simply positioning the window near an edge.
                if (!isEditing && FloatingOverlayStore.isDockingEnabled(context)) {
                    val physicalDx = event.rawX - touchStartX
                    val physicalX = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        bounds.travelX - (startX + dx) else startX + dx
                    val side = when {
                        physicalDx <= -dp(48) && physicalX <= -dp(24) -> DockSide.LEFT
                        physicalDx >= dp(48) && physicalX >= bounds.travelX + dp(24) -> DockSide.RIGHT
                        else -> DockSide.NONE
                    }
                    if (side != DockSide.NONE) {
                        flushDragFrame()
                        saveDraggedPosition(p)
                        val speed = dragVelocity?.let {
                            it.computeCurrentVelocity(1_000)
                            abs(it.xVelocity)
                        } ?: 0f
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        cancelGesture()
                        dock(side, speed)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(holdRunnable)
                holdGesture.cancel()
                if (!gestureActive) { cancelGesture(); return true }
                if (!dragging && isClick(event)) performLyricClick(view, event, isLocked)
                if (!isLocked && dragging) {
                    flushDragFrame()
                    if (isEditing) updateDraftPosition(p) else saveDraggedPosition(p)
                    scheduleLayout()
                }
                cancelGesture()
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
        surface?.removeCallbacks(drawDragFrame)
        pendingFrame = null
        dragVelocity?.recycle()
        dragVelocity = null
        holdGesture.cancel()
        gestureActive = false
        dragging = false
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
        view.setPaddingRelative(
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp),
            dp(style.paddingHorizontalDp + if (isEditing) 18 else 0),
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

        cardRadius = dp(style.cornerRadiusDp).toFloat()
        cardFill = when {
            style.backgroundEnabled -> AirColorUtils.withAlpha(style.backgroundColor, style.backgroundAlpha)
            isEditing -> Color.argb(224, 24, 29, 38)
            else -> Color.TRANSPARENT
        }
        dockHandle?.setTextColor(style.textColor or Color.BLACK)
        shell.setStroke(if (isEditing) dp(1).coerceAtLeast(1) else 0, Color.argb(130, 255, 255, 255))
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
            calculateLayout(view, p, remap)
            if (surfaceAttached && transition == null && caughtFrame == null) paintRestingSurface()
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
        val editorRoom = editor?.let { measureToolbar(it, availableWidth) + dp(8) } ?: 0
        val availableHeight = (fullHeight - editorRoom).coerceAtLeast(dp(48).coerceAtMost(fullHeight))
        val width = overlayGeometry.widthFraction?.let {
            FloatingWindowGeometry.dimension(availableWidth, it, dp(144))
        } ?: FloatingWindowGeometry.width(availableWidth, FloatingLyricsStyleStore.getStyle(context).maxWidthPercent)
        val fixedHeight = overlayGeometry.heightFraction?.let {
            FloatingWindowGeometry.dimension(fullHeight, it, dp(48)).coerceAtMost(availableHeight)
        }
        if (view.minWidth != width) view.minWidth = width
        if (view.maxWidth != width) view.maxWidth = width
        if (view.maxHeight != availableHeight) view.maxHeight = availableHeight
        if (lyricNeedsMeasure) {
            view.forceLayout()
            lyricNeedsMeasure = false
        }
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
            val initial = if (FloatingLyricsStyleStore.hasLegacyPosition(context)) {
                val (x, y) = FloatingLyricsStyleStore.getPosition(context)
                next.migrate(x, y, touchSlop)
            } else defaultFloatingPosition
            // Pre-30 must wait for the fitted frame before migrating pixels permanently.
            if (Build.VERSION.SDK_INT >= 30 || view.isAttachedToWindow) {
                position = initial
                FloatingLyricsStyleStore.saveRelativePosition(context, initial)
            }
            initial
        }
        val (x, y) = if (remap || areaChanged || !isEditing && previous.width != width) {
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
        val view = surface ?: return true
        val p = surfaceParams ?: return true
        return runCatching {
            val flags = windowFlagsForCurrentBehavior()
            if (p.flags != flags) {
                p.flags = flags
                if (surfaceAttached) windowManager.updateViewLayout(view, p)
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
        caughtFrame = null
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
                    position = defaultFloatingPosition
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
        resizeDraft(bounds, bounds.width + dp(widthDp), bounds.height + dp(heightDp), fullHeight)
        updateLayout(remap = false)
    }

    private fun changeDraftTextSize(delta: Float) {
        editTextSizeSp = ((editTextSizeSp ?: return) + delta).coerceIn(14f, 56f)
        lyricsView?.let(::applyStyle)
        updateLayout(remap = false)
        params?.let(::updateDraftPosition)
    }

    private fun resizeDraft(bounds: FloatingWindowGeometry, width: Int, height: Int, fullHeight: Int) {
        val layout = params ?: return
        val maxWidth = (bounds.availableWidth - layout.x).coerceAtLeast(1)
        val maxHeight = (bounds.availableHeight - layout.y).coerceAtLeast(1)
        val fittedWidth = width.coerceIn(dp(144).coerceIn(1, maxWidth), maxWidth)
        val fittedHeight = height.coerceIn(dp(48).coerceIn(1, maxHeight), maxHeight)
        overlayGeometry = overlayGeometry.copy(widthFraction = fittedWidth.toFloat() / bounds.availableWidth,
            heightFraction = fittedHeight.toFloat() / fullHeight)
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
                val height = (resizeStartHeight + (event.rawY - resizeTouchY).toInt())
                resizeDraft(bounds, width, height, fullHeight)
                scheduleLayout()
            }
            MotionEvent.ACTION_UP -> {
                resizeActive = false
                surface?.removeCallbacks(refreshLayout)
                updateLayout(remap = false)
                editor?.resizeHandle?.performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> resizeActive = false
        }
        return true
    }

    /** Physical left/right docking is intentionally independent of the lyric's text direction. */
    fun dock(side: DockSide, velocityPxPerSecond: Float = 0f) {
        if (side == DockSide.NONE || !isContentVisible || isEditing ||
            !FloatingOverlayStore.isDockingEnabled(context) || FloatingLyricsStyleStore.isClickThrough(context)) return
        val from = drawnFrame ?: expandedFrame() ?: return
        val bounds = geometry ?: return
        val travel = (bounds.availableHeight - dp(64)).coerceAtLeast(0)
        val vertical = if (travel > 0) {
            ((from.bounds.exactCenterY() - dp(32)) / travel).coerceIn(0f, 1f)
        } else 0.5f
        cancelGesture()
        overlayGeometry = overlayGeometry.copy(dockSide = side, dockVerticalFraction = vertical)
        FloatingOverlayStore.saveGeometry(context, overlayGeometry)
        runCatching {
            setDockLabel(side)
            restingFrame()?.let { animateSurface(from, it, velocityPxPerSecond) }
        }.onFailure { hideAfterFailure() }
        onInteractionChanged?.invoke()
    }

    fun expand(animate: Boolean = true) {
        if (isDocked) finishExpansion(animate)
    }

    private fun finishExpansion(animate: Boolean) {
        val from = drawnFrame ?: restingFrame() ?: return
        val lyrics = lyricsView ?: return
        val layout = params ?: return
        cancelGesture()
        cancelTransition()
        overlayGeometry = overlayGeometry.copy(dockSide = DockSide.NONE)
        FloatingOverlayStore.saveGeometry(context, overlayGeometry)
        runCatching {
            calculateLayout(lyrics, layout, remap = true)
            expandedFrame()?.let { target ->
                if (animate) animateSurface(from, target) else paintSurface(target)
            }
        }.onFailure { hideAfterFailure() }
        onInteractionChanged?.invoke()
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode &&
        !LyricsDisplaySettingsStore.isMinimalMotion(context)

    private fun animateSurface(from: SurfaceFrame, to: SurfaceFrame, velocityPxPerSecond: Float = 0f) {
        cancelTransition()
        val area = geometry ?: return
        if (from == to || !surfaceAttached || !animationsEnabled()) {
            paintSurface(to)
            return
        }
        val travel = abs(to.bounds.left - from.bounds.left) + abs(to.bounds.width() - from.bounds.width())
        val initialVelocity = (velocityPxPerSecond / travel.coerceAtLeast(dp(48))).coerceIn(0f, 4f)
        transition = SpringAnimation(FloatValueHolder(0f)).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.78f
                stiffness = 560f
            }
            setMinimumVisibleChange(0.003f)
            setStartVelocity(initialVelocity)
            addUpdateListener { _, progress, _ ->
                fun blend(start: Int, end: Int) = (start + (end - start) * progress).roundToInt()
                val width = blend(from.bounds.width(), to.bounds.width()).coerceIn(1, area.availableWidth)
                val height = blend(from.bounds.height(), to.bounds.height()).coerceIn(1, area.availableHeight)
                val x = blend(from.bounds.left, to.bounds.left).coerceIn(0, area.availableWidth - width)
                val y = blend(from.bounds.top, to.bounds.top).coerceIn(0, area.availableHeight - height)
                val dockAmount = (from.dockAmount + (to.dockAmount - from.dockAmount) * progress).coerceIn(0f, 1f)
                runCatching { paintSurface(SurfaceFrame(Rect(x, y, x + width, y + height), dockAmount)) }
                    .onFailure { hideAfterFailure() }
            }
            addEndListener { animation, canceled, _, _ ->
                if (transition === animation) transition = null
                if (!canceled) {
                    runCatching { paintSurface(to) }.onFailure { hideAfterFailure() }
                    scheduleLayout()
                }
            }
        }
        transition?.start()
    }

    private fun cancelTransition() {
        val animation = transition ?: return
        transition = null
        animation.cancel()
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

    private fun createSurface(lyrics: View, handle: View): FrameLayout = object : FrameLayout(windowContext) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
            event.actionMasked == MotionEvent.ACTION_DOWN && transition != null &&
                !FloatingLyricsStyleStore.isLocked(context) || super.onInterceptTouchEvent(event)
    }.apply {
        background = shell
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(lyrics, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(handle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setOnTouchListener { _, event -> handleTransitionTouch(event) }
    }

    private fun attachCurrentSurface() {
        val view = surface ?: return
        val layout = surfaceParams ?: return
        if (!isPresentationVisible) return
        paintRestingSurface()
        if (!surfaceAttached) {
            windowManager.addView(view, layout)
            surfaceAttached = true
            lyricsView?.requestApplyInsets()
        }
    }

    private fun detachSurface(): Boolean {
        val view = surface ?: return true
        if (!surfaceAttached) return true
        surfaceAttached = false
        return runCatching { windowManager.removeView(view) }.isSuccess
    }

    private fun setDockLabel(side: DockSide) {
        dockHandle?.text = if (side == DockSide.LEFT) "›" else "‹"
    }

    private fun expandedFrame(): SurfaceFrame? {
        val layout = params ?: return null
        val bounds = geometry ?: return null
        val x = if (lyricsView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.travelX - layout.x else layout.x
        return SurfaceFrame(Rect(x, layout.y, x + bounds.width, layout.y + bounds.height), 0f)
    }

    private fun restingFrame(): SurfaceFrame? {
        if (!isDocked) return expandedFrame()
        val area = geometry ?: return null
        val width = dp(48).coerceAtMost(area.availableWidth)
        val height = dp(64).coerceAtMost(area.availableHeight)
        val x = if (overlayGeometry.dockSide == DockSide.LEFT) 0 else area.availableWidth - width
        val y = ((area.availableHeight - height) * overlayGeometry.dockVerticalFraction).roundToInt()
        return SurfaceFrame(Rect(x, y, x + width, y + height), 1f)
    }

    private fun paintRestingSurface() {
        if (isDocked) setDockLabel(overlayGeometry.dockSide)
        restingFrame()?.let(::paintSurface)
    }

    private fun paintSurface(frame: SurfaceFrame) {
        val view = surface ?: return
        val p = surfaceParams ?: return
        val bounds = geometry ?: return
        val rect = frame.bounds
        val changed = p.x != rect.left || p.y != rect.top || p.width != rect.width() || p.height != rect.height()
        p.x = rect.left
        p.y = rect.top
        p.width = rect.width()
        p.height = rect.height()
        drawnFrame = frame
        val amount = frame.dockAmount
        val dockFill = if (Color.alpha(cardFill) == 0) Color.argb(235, 24, 29, 38) else
            AirColorUtils.withAlpha(cardFill, Color.alpha(cardFill).coerceAtLeast(235))
        val fill = androidx.core.graphics.ColorUtils.blendARGB(cardFill, dockFill, amount)
        val radius = cardRadius + (dp(24) - cardRadius) * amount
        if (shell.color?.defaultColor != fill) shell.setColor(fill)
        if (shell.cornerRadius != radius) {
            shell.cornerRadius = radius
            view.invalidateOutline()
        }
        lyricsView?.let {
            val child = it.layoutParams
            if (child.width != bounds.width || child.height != bounds.height) {
                child.width = bounds.width
                child.height = bounds.height
                it.layoutParams = child
            }
            it.visibility = if (amount < 1f) View.VISIBLE else View.GONE
            it.alpha = (1f - amount * 1.6f).coerceIn(0f, 1f)
            it.importantForAccessibility = if (amount < 0.5f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dockHandle?.let {
            it.visibility = if (amount > 0f) View.VISIBLE else View.GONE
            it.alpha = ((amount - 0.35f) / 0.65f).coerceIn(0f, 1f)
            it.importantForAccessibility = if (amount >= 0.5f) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (changed && surfaceAttached) windowManager.updateViewLayout(view, p)
    }

    private fun queueDragFrame(frame: SurfaceFrame) {
        if (pendingFrame == null) surface?.postOnAnimation(drawDragFrame)
        pendingFrame = frame
    }

    private fun flushDragFrame() {
        val frame = pendingFrame ?: return
        surface?.removeCallbacks(drawDragFrame)
        pendingFrame = null
        runCatching { paintSurface(frame); updateEditorLayout() }.onFailure { hideAfterFailure() }
    }

    private fun handleTransitionTouch(event: MotionEvent): Boolean {
        val area = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (transition == null) return false
                val frame = drawnFrame ?: return false
                cancelTransition()
                caughtFrame = frame
                touchStartX = event.rawX
                touchStartY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val start = caughtFrame ?: return false
                val rect = Rect(start.bounds)
                rect.offsetTo(
                    (start.bounds.left + (event.rawX - touchStartX).roundToInt())
                        .coerceIn(0, (area.availableWidth - rect.width()).coerceAtLeast(0)),
                    (start.bounds.top + (event.rawY - touchStartY).roundToInt())
                        .coerceIn(0, (area.availableHeight - rect.height()).coerceAtLeast(0))
                )
                queueDragFrame(SurfaceFrame(rect, start.dockAmount))
            }
            MotionEvent.ACTION_UP -> {
                if (caughtFrame == null) return false
                flushDragFrame()
                val rect = drawnFrame?.bounds ?: return false
                val x = (rect.centerX() - area.width / 2).coerceIn(0, area.travelX)
                val y = (rect.centerY() - area.height / 2).coerceIn(0, area.travelY)
                val logicalX = if (lyricsView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) area.travelX - x else x
                position = area.position(logicalX, y, position ?: FloatingPosition(0.5f, 0f)).also {
                    FloatingLyricsStyleStore.saveRelativePosition(context, it)
                }
                caughtFrame = null
                finishExpansion(animate = true)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (caughtFrame == null) return false
                cancelGesture()
                caughtFrame = null
                paintRestingSurface()
            }
        }
        return true
    }

    private fun updateEditorLayout() {
        val controls = editor ?: return
        val view = lyricsView ?: return
        val p = params ?: return
        val (width, height) = availableSize(view)
        val frame = expandedFrame()?.bounds ?: return
        editTextSizeSp?.let { controls.updateTextSize(it.roundToInt()) }
        val gap = dp(8)
        editorParams?.let { ep ->
            val previousWidth = ep.width
            val previousHeight = ep.height
            val previousX = ep.x
            val previousY = ep.y
            ep.width = toolbarWidth(width)
            ep.height = measureToolbar(controls, width).coerceAtMost(height)
            ep.x = (frame.centerX() - ep.width / 2).coerceIn(0, width - ep.width)
            val below = frame.bottom + gap
            val above = frame.top - ep.height - gap
            ep.y = when {
                below + ep.height <= height -> below
                above >= 0 -> above
                else -> below.coerceIn(0, height - ep.height)
            }
            if (controls.panel.isAttachedToWindow && (previousWidth != ep.width || previousHeight != ep.height ||
                previousX != ep.x || previousY != ep.y)) windowManager.updateViewLayout(controls.panel, ep)
        }
        resizeParams?.let { rp ->
            val previousX = rp.x
            val previousY = rp.y
            // Params.x is relative to START; auxiliary windows use physical LEFT.
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val physicalX = frame.left
            controls.resizeHandle.text = if (rtl) "↙" else "↘"
            rp.x = (if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) physicalX else physicalX + p.width - dp(48))
                .coerceIn(0, (width - dp(48)).coerceAtLeast(0))
            rp.y = (p.y + (geometry?.height ?: view.height) - dp(48))
                .coerceIn(0, (height - dp(48)).coerceAtLeast(0))
            if (controls.resizeHandle.isAttachedToWindow && (previousX != rp.x || previousY != rp.y))
                windowManager.updateViewLayout(controls.resizeHandle, rp)
        }
    }

    private fun toolbarWidth(availableWidth: Int) = availableWidth.coerceAtMost(dp(300))

    private fun measureToolbar(controls: FloatingOverlayEditControls, availableWidth: Int): Int {
        controls.panel.measure(
            View.MeasureSpec.makeMeasureSpec(toolbarWidth(availableWidth), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return controls.panel.measuredHeight
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
