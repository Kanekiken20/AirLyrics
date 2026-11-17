package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.andsi.airlyrics.R

/** Only exists during editing. Every control is at least 48 dp tall and has a TalkBack label. */
internal class FloatingOverlayEditControls(
    context: Context,
    done: () -> Unit,
    cancel: () -> Unit,
    reset: () -> Unit,
    smaller: () -> Unit,
    larger: () -> Unit,
    dockLeft: () -> Unit,
    dockRight: () -> Unit,
    resize: (MotionEvent) -> Boolean,
    resizeBy: (Int, Int) -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(24, 29, 38))
            setStroke(dp(1).coerceAtLeast(1), Color.WHITE)
        }
        contentDescription = context.getString(R.string.ui_adjustment_mode)
    }
    val resizeHandle = Button(context).apply {
        text = "↘"
        textSize = 24f
        setTextColor(Color.WHITE)
        contentDescription = context.getString(R.string.ui_overlay_resize)
        setOnTouchListener { _, event -> resize(event) }
        setOnKeyListener { _, key, event ->
            val delta = when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -16 to 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 16 to 0
                KeyEvent.KEYCODE_DPAD_UP -> 0 to -16
                KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 16
                else -> null
            }
            if (delta != null && event.action == KeyEvent.ACTION_DOWN) {
                resizeBy(delta.first, delta.second)
                true
            } else false
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            val actions = listOf(
                Triple(R.id.overlay_resize_wider, R.string.ui_overlay_wider, 16 to 0),
                Triple(R.id.overlay_resize_narrower, R.string.ui_overlay_narrower, -16 to 0),
                Triple(R.id.overlay_resize_taller, R.string.ui_overlay_taller, 0 to 16),
                Triple(R.id.overlay_resize_shorter, R.string.ui_overlay_shorter, 0 to -16)
            )
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                actions.forEach { (id, label, _) ->
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, context.getString(label)))
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val delta = actions.firstOrNull { it.first == action }?.third
                if (delta != null) {
                    resizeBy(delta.first, delta.second)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    init {
        fun row(vararg items: Pair<Int, () -> Unit>) {
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                items.forEach { (label, action) ->
                    addView(Button(context).apply {
                        text = context.getString(label)
                        contentDescription = text
                        textSize = 12f
                        isAllCaps = false
                        minimumWidth = 0
                        minWidth = 0
                        setPadding(dp(4), 0, dp(4), 0)
                        setTextColor(Color.WHITE)
                        setOnClickListener { action() }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        }
        row(R.string.ui_overlay_done to done, R.string.ui_cancel to cancel, R.string.ui_reset to reset)
        row(R.string.ui_overlay_font_smaller to smaller, R.string.ui_overlay_font_larger to larger)
        row(R.string.ui_overlay_dock_left to dockLeft, R.string.ui_overlay_dock_right to dockRight)
    }
}
