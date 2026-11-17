package com.andsi.airlyrics.floating

import org.junit.Assert.*
import org.junit.Test

class LyricsPresentationPolicyTest {
    private fun decision(screen: LyricsScreenClass, desired: Boolean = true, overlay: Boolean = true,
                         docked: Boolean = false, editing: Boolean = false, locked: Boolean = true,
                         motion: Boolean = true) = LyricsPresentationPolicy.decide(
        screen, desired, overlay, docked, editing, locked, motion
    )

    @Test fun ambientAndUnknownNeverDrawOrScheduleLiveText() {
        listOf(LyricsScreenClass.DOZE, LyricsScreenClass.DOZE_SUSPEND,
            LyricsScreenClass.OFF, LyricsScreenClass.UNKNOWN).forEach {
            val result = decision(it)
            assertFalse(result.drawOverlay)
            assertFalse(result.updateLockScreen)
            assertFalse(result.animate)
        }
    }

    @Test fun lockedInteractiveIsIndependentOfChosenAppOverlayFilter() {
        val result = decision(LyricsScreenClass.LOCKED_INTERACTIVE, overlay = false)
        assertEquals(LyricsPresentationMode.LOCKSCREEN, result.mode)
        assertTrue(result.updateLockScreen)
        assertFalse(result.drawOverlay)
        assertFalse(result.animate)
        assertEquals(LyricsPresentationMode.HIDDEN,
            decision(LyricsScreenClass.LOCKED_INTERACTIVE, locked = false).mode)
    }

    @Test fun userHideWinsOverEverySurface() {
        LyricsScreenClass.entries.forEach {
            assertEquals(LyricsPresentationDecision(LyricsPresentationMode.HIDDEN), decision(it, desired = false))
        }
    }

    @Test fun dockedAndReducedMotionDoNotAnimate() {
        val docked = decision(LyricsScreenClass.UNLOCKED, docked = true)
        assertEquals(LyricsPresentationMode.DOCKED, docked.mode)
        assertFalse(docked.drawOverlay)
        assertFalse(docked.animate)
        val reduced = decision(LyricsScreenClass.UNLOCKED, editing = true, motion = false)
        assertEquals(LyricsPresentationMode.EDIT, reduced.mode)
        assertTrue(reduced.drawOverlay)
        assertFalse(reduced.animate)
    }
}
