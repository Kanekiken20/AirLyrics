package com.andsi.airlyrics.floating

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.FloatingOverlayStore
import com.andsi.airlyrics.settings.store.FloatingOverlayStore.DockSide
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingOverlayInteractionTest {
    private lateinit var context: Application
    private lateinit var window: FloatingLyricsWindow
    private lateinit var wm: ShadowWindowManagerImpl

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("floating_lyrics_style", "floating_overlay_geometry", "lyrics_display").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LyricsDisplaySettingsStore.setMinimalMotion(context, true)
        ShadowSettings.setCanDrawOverlays(true)
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port")
        ShadowDisplayManager.changeDisplay(0, "w400dp-h800dp-port")
        window = FloatingLyricsWindow(context) {}
        assertTrue(window.show())
        idle()
        wm = Shadow.extract(requireNotNull(window.textView).context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    }

    @After fun cleanup() { window.hide() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(view: View, action: Int, x: Float = 10f, y: Float = 20f, at: Long = 0L) {
        val event = MotionEvent.obtain(0L, at, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }
    private fun control(label: Int): View {
        val description = context.getString(label)
        fun find(view: View): View? {
            if (view.contentDescription == description) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return wm.views.firstNotNullOfOrNull(::find) ?: error("Missing control $description")
    }

    @Test fun slowPushPastEitherEdgeDocksWithoutEditingOrFling() {
        val text = requireNotNull(window.textView)
        val distance = 800f * text.resources.displayMetrics.density
        for (direction in listOf(-1, 1)) {
            touch(text, MotionEvent.ACTION_DOWN, 10f, at = 0L)
            touch(text, MotionEvent.ACTION_MOVE, 10f + direction * distance, at = 10_000L)
            assertTrue("Slow edge push should dock before release", window.isDocked)
            assertFalse(window.isEditing)
            idle()
            assertEquals(1, wm.views.size)
            window.expand(animate = false)
            idle()
        }
    }

    @Test fun disabledDockingAndSmallDragsKeepTheWindowExpanded() {
        val text = requireNotNull(window.textView)
        touch(text, MotionEvent.ACTION_DOWN)
        touch(text, MotionEvent.ACTION_MOVE, 24f)
        assertFalse(window.isDocked)
        touch(text, MotionEvent.ACTION_CANCEL)
        FloatingOverlayStore.setDockingEnabled(context, false)
        touch(text, MotionEvent.ACTION_DOWN)
        touch(text, MotionEvent.ACTION_MOVE, -800f * text.resources.displayMetrics.density)
        assertFalse(window.isDocked)
        touch(text, MotionEvent.ACTION_CANCEL)
    }

    @Test fun physicalEdgePushStillWorksInRtl() {
        val text = requireNotNull(window.textView)
        text.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.onConfigurationChanged()
        idle()
        touch(text, MotionEvent.ACTION_DOWN)
        touch(text, MotionEvent.ACTION_MOVE, -800f * text.resources.displayMetrics.density)
        assertTrue(window.isDocked)
        assertEquals(DockSide.LEFT, FloatingOverlayStore.geometry(context).dockSide)
    }

    @Test fun threeSecondStationaryHoldOpensEditorOnce() {
        val text = requireNotNull(window.textView)
        touch(text, MotionEvent.ACTION_DOWN)
        advance(2_999)
        assertFalse(window.isEditing)
        advance(1)
        assertTrue(window.isEditing)
        assertEquals(3, wm.views.size)
        advance(10_000)
        assertEquals(3, wm.views.size)
        assertTrue(control(R.string.ui_overlay_done).performClick())
        assertFalse(window.isEditing)
        assertEquals(1, wm.views.size)
    }

    @Test fun movementCancelSecondPointerAndHiddenWindowCancelHold() {
        val text = requireNotNull(window.textView)
        touch(text, MotionEvent.ACTION_DOWN)
        touch(text, MotionEvent.ACTION_MOVE, 200f)
        advance(3_000)
        assertFalse(window.isEditing)
        touch(text, MotionEvent.ACTION_CANCEL)
        touch(text, MotionEvent.ACTION_DOWN)
        touch(text, MotionEvent.ACTION_POINTER_DOWN)
        advance(3_000)
        assertFalse(window.isEditing)
        touch(text, MotionEvent.ACTION_DOWN)
        window.setPresentationVisible(false)
        advance(3_000)
        assertFalse(window.isEditing)
        assertTrue(window.isVisible)
        assertTrue(wm.views.isEmpty())
        window.setPresentationVisible(true)
        assertSame(text, window.textView)
        assertEquals(1, wm.views.size)
    }

    @Test fun editCancelDoesNotPersistSizeOrFontAndDoneDoes() {
        val before = FloatingOverlayStore.geometry(context)
        val font = FloatingLyricsStyleStore.getStyle(context).textSizeSp
        val text = requireNotNull(window.textView)
        assertTrue(window.enterEditMode())
        control(R.string.ui_overlay_font_larger).performClick()
        val resize = control(R.string.ui_overlay_resize)
        touch(resize, MotionEvent.ACTION_DOWN)
        touch(resize, MotionEvent.ACTION_MOVE, -100f, 200f)
        touch(resize, MotionEvent.ACTION_UP, -100f, 200f)
        assertEquals(before, FloatingOverlayStore.geometry(context))
        assertEquals(font, FloatingLyricsStyleStore.getStyle(context).textSizeSp)
        assertEquals(font + 2f, text.textSize / text.resources.displayMetrics.scaledDensity, 0.1f)
        control(R.string.ui_cancel).performClick()
        assertEquals(before, FloatingOverlayStore.geometry(context))
        assertEquals(font, text.textSize / text.resources.displayMetrics.scaledDensity, 0.1f)
        window.enterEditMode()
        control(R.string.ui_overlay_font_larger).performClick()
        val secondResize = control(R.string.ui_overlay_resize)
        touch(secondResize, MotionEvent.ACTION_DOWN)
        touch(secondResize, MotionEvent.ACTION_MOVE, -100f, 200f)
        touch(secondResize, MotionEvent.ACTION_UP, -100f, 200f)
        control(R.string.ui_overlay_done).performClick()
        assertNotNull(FloatingOverlayStore.geometry(context).widthFraction)
        assertNotNull(FloatingOverlayStore.geometry(context).heightFraction)
        assertEquals(font + 2f, FloatingLyricsStyleStore.getStyle(context).textSizeSp)
    }

    @Test fun legacyWidthSliderReplacesResizeWidthWithoutDiscardingHeightOrFont() {
        window.enterEditMode()
        control(R.string.ui_overlay_resize).performAccessibilityAction(R.id.overlay_resize_narrower, null)
        control(R.string.ui_overlay_resize).performAccessibilityAction(R.id.overlay_resize_taller, null)
        window.finishEditMode(true)
        val saved = FloatingOverlayStore.geometry(context)
        val text = requireNotNull(window.textView)
        val resizedWidth = (text.layoutParams as WindowManager.LayoutParams).width
        val font = text.textSize
        assertNotNull(saved.widthFraction)
        assertNotNull(saved.heightFraction)
        FloatingLyricsStyleStore.setMaxWidthPercent(context, 45)
        assertTrue(window.applyStyle())
        idle()
        assertNull(FloatingOverlayStore.geometry(context).widthFraction)
        assertEquals(saved.heightFraction, FloatingOverlayStore.geometry(context).heightFraction)
        assertTrue((text.layoutParams as WindowManager.LayoutParams).width < resizedWidth)
        assertEquals(font, text.textSize)
    }

    @Test fun accessibleResizeActionsChangeBoxIndependentlyOfFont() {
        window.enterEditMode()
        val text = requireNotNull(window.textView)
        val font = text.textSize
        val initialWidth = (text.layoutParams as WindowManager.LayoutParams).width
        val handle = control(R.string.ui_overlay_resize)
        assertTrue(handle.performAccessibilityAction(R.id.overlay_resize_narrower, null))
        val narrower = (text.layoutParams as WindowManager.LayoutParams).width
        assertTrue(narrower < initialWidth)
        assertTrue(handle.performAccessibilityAction(R.id.overlay_resize_taller, null))
        assertEquals(font, text.textSize)
        window.finishEditMode(true)
        assertNotNull(FloatingOverlayStore.geometry(context).heightFraction)
    }

    @Test fun cancelledEditingFromDockRestoresHandleWithoutCommittingExpansion() {
        window.dock(DockSide.LEFT)
        assertTrue(window.enterEditMode())
        assertFalse(window.isDocked)
        assertEquals(DockSide.LEFT, FloatingOverlayStore.geometry(context).dockSide)
        window.finishEditMode(false)
        assertTrue(window.isDocked)
        assertEquals(1, wm.views.size)
        assertEquals(context.getString(R.string.ui_overlay_expand), wm.views.single().contentDescription)
    }

    @Test fun dockUsesOnlySmallWindowAndExpandRestoresLatestLyric() {
        val text = requireNotNull(window.textView)
        text.text = "Before docking"
        val expandedWidth = (text.layoutParams as WindowManager.LayoutParams).width
        window.dock(DockSide.LEFT)
        assertTrue(window.isDocked)
        assertFalse(window.isContentVisible)
        // WindowManager.removeView defers ViewRoot teardown to the main queue.
        // The content policy is already suspended; inspect attachment after that transaction settles.
        idle()
        assertFalse(text.isAttachedToWindow)
        assertEquals(1, wm.views.size)
        val handle = wm.views.single()
        val params = handle.layoutParams as WindowManager.LayoutParams
        val density = text.resources.displayMetrics.density
        assertEquals((48 * density).toInt(), params.width)
        assertEquals((64 * density).toInt(), params.height)
        assertEquals(0, params.x)
        text.text = "Newest lyric wins"
        handle.performClick()
        assertFalse(window.isDocked)
        // Attachment is delivered on the new ViewRoot's first traversal.
        idle()
        assertTrue(text.isAttachedToWindow)
        assertSame(text, window.textView)
        assertEquals("Newest lyric wins", text.text.toString())
        assertEquals(expandedWidth, (text.layoutParams as WindowManager.LayoutParams).width)
        assertEquals(1, wm.views.size)
    }

    @Test fun disablingDockingDuringAnimationCannotLeaveHiddenExpandedWindow() {
        LyricsDisplaySettingsStore.setMinimalMotion(context, false)
        val text = requireNotNull(window.textView)
        window.dock(DockSide.RIGHT)
        FloatingOverlayStore.setDockingEnabled(context, false)
        window.applyStyle()
        advance(500)
        assertFalse(window.isDocked)
        assertTrue(window.isContentVisible)
        assertEquals(0f, text.translationX)
        assertEquals(DockSide.NONE, FloatingOverlayStore.geometry(context).dockSide)
        assertEquals(1, wm.views.size)
    }

    @Test fun lyricAnimationCancellationCannotCancelWindowDockTransition() {
        LyricsDisplaySettingsStore.setMinimalMotion(context, false)
        val text = requireNotNull(window.textView)
        window.dock(DockSide.RIGHT)
        text.animate().cancel()
        text.text = "New line during dock motion"
        advance(500)
        assertTrue(window.isDocked)
        assertEquals(1, wm.views.size)
        assertFalse(text.isAttachedToWindow)
        window.expand()
        window.setPresentationVisible(false)
        advance(500)
        assertEquals(0f, text.translationX)
        assertTrue(wm.views.isEmpty())
    }

    @Test fun clickThroughIsRetainedAndNotificationCanEnableEditing() {
        window.setClickThrough(true)
        assertFalse(window.enterEditMode())
        val flags = (requireNotNull(window.textView).layoutParams as WindowManager.LayoutParams).flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        window.setClickThrough(false)
        window.setLocked(false)
        assertTrue(window.enterEditMode())
        window.finishEditMode(true)
        window.dock(DockSide.RIGHT)
        window.setClickThrough(true)
        val handle = wm.views.single()
        assertTrue((handle.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test fun lockOrRotationCancelsDraftAndNoEditorWindowsLeak() {
        val saved = FloatingPosition(0.5f, 0.4f)
        FloatingLyricsStyleStore.saveRelativePosition(context, saved)
        window.hide()
        window.show()
        window.enterEditMode()
        control(R.string.ui_overlay_font_larger).performClick()
        window.onConfigurationChanged()
        idle()
        assertFalse(window.isEditing)
        assertEquals(saved, FloatingLyricsStyleStore.getRelativePosition(context))
        assertEquals(1, wm.views.size)
        window.enterEditMode()
        window.setPresentationVisible(false)
        assertFalse(window.isEditing)
        assertEquals(0, wm.views.size)
        window.setPresentationVisible(true)
        assertEquals(1, wm.views.size)
    }
}
