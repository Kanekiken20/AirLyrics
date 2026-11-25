package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R

/** Only exists during editing. Every control is at least 48 dp tall and has a TalkBack label. */
internal class FloatingOverlayEditControls(
    private val context: Context,
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

    private val textSizeLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
    }

    fun updateTextSize(sizeSp: Int) {
        textSizeLabel.text = context.getString(R.string.ui_overlay_size_value, sizeSp)
    }

    private fun chip(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.rgb(30, 34, 44))
            setStroke(dp(1).coerceAtLeast(1), Color.argb(52, 255, 255, 255))
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    val resizeHandle = TextView(context).apply {
        text = "↘"
        textSize = 16f
        gravity = Gravity.CENTER
        background = InsetDrawable(chip(Color.rgb(54, 60, 72), 9), dp(12), dp(12), dp(3), dp(3))
        setPadding(dp(9), dp(9), 0, 0)
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
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
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    R.id.overlay_dock_left, context.getString(R.string.ui_overlay_dock_left)))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    R.id.overlay_dock_right, context.getString(R.string.ui_overlay_dock_right)))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == R.id.overlay_dock_left) {
                    dockLeft()
                    return true
                }
                if (action == R.id.overlay_dock_right) {
                    dockRight()
                    return true
                }
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
        fun button(label: Int, displayed: String = context.getString(label), primary: Boolean = false, action: () -> Unit) =
            Button(context).apply {
                text = displayed
                contentDescription = context.getString(label)
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minimumHeight = dp(48)
                backgroundTintList = null
                background = chip(if (primary) Color.rgb(224, 230, 248) else Color.TRANSPARENT, 14)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setTextColor(if (primary) Color.rgb(27, 36, 57) else Color.WHITE)
                setOnClickListener { action() }
            }
        fun row(vararg items: View) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            items.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        panel.addView(row(
            button(R.string.ui_overlay_font_smaller, "−", action = smaller),
            textSizeLabel,
            button(R.string.ui_overlay_font_larger, "+", action = larger)
        ))
        panel.addView(row(
            button(R.string.ui_reset, action = reset),
            button(R.string.ui_cancel, action = cancel),
            button(R.string.ui_overlay_done, primary = true, action = done)
        ))
    }
}
