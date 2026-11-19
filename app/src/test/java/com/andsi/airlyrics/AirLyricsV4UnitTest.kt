package com.andsi.airlyrics

import androidx.core.app.NotificationCompat
import com.andsi.airlyrics.floating.FloatingGesturePhysics
import com.andsi.airlyrics.floating.FloatingSizePreset
import com.andsi.airlyrics.floating.OverlayVisibilityPolicy
import com.andsi.airlyrics.lockscreen.LockScreenNotificationCardPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirLyricsV4UnitTest {

    @Test
    fun lockScreenCardSpec_avoidsTransportNowBarAndAutogrouping() {
        val spec = LockScreenNotificationCardPublisher.DEFAULT_SPEC
        assertEquals(NotificationCompat.CATEGORY_TRANSPORT, spec.category)
        assertEquals(LockScreenNotificationCardPublisher.STANDALONE_GROUP_KEY, spec.groupKey)
        assertFalse(spec.isGroupSummary)
        assertEquals(LockScreenNotificationCardPublisher.USE_DECORATED_STYLE, spec.usesDecoratedCustomViewStyle)
        assertEquals(NotificationCompat.PRIORITY_MAX, spec.priority)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, spec.visibility)
        assertEquals(LockScreenNotificationCardPublisher.CHANNEL_ID, spec.channelId)
    }

    @Test
    fun lockScreenCardVisibility_clearsWhenPausedOrEmpty() {
        assertTrue(
            OverlayVisibilityPolicy.shouldShowLockScreenCard(
                lockScreenCardEnabled = true,
                isPlaying = true,
                currentLine = "He was so regular",
                trackTitle = "Exchange"
            )
        )
        assertFalse(
            OverlayVisibilityPolicy.shouldShowLockScreenCard(
                lockScreenCardEnabled = true,
                isPlaying = false,
                currentLine = "He was so regular",
                trackTitle = "Exchange"
            )
        )
        assertFalse(
            OverlayVisibilityPolicy.shouldShowLockScreenCard(
                lockScreenCardEnabled = false,
                isPlaying = true,
                currentLine = "He was so regular",
                trackTitle = "Exchange"
            )
        )
    }

    @Test
    fun sizePreset_cyclesAcrossAllFourSizesAndWidths() {
        assertEquals(FloatingSizePreset.NORMAL, FloatingSizePreset.COMPACT.next())
        assertEquals(FloatingSizePreset.LARGE, FloatingSizePreset.NORMAL.next())
        assertEquals(FloatingSizePreset.EXTRA_LARGE, FloatingSizePreset.LARGE.next())
        assertEquals(FloatingSizePreset.COMPACT, FloatingSizePreset.EXTRA_LARGE.next())

        assertEquals(15f, FloatingSizePreset.COMPACT.textSizeSp)
        assertEquals(0.70f, FloatingSizePreset.COMPACT.maxWidthFraction)
        assertEquals(18f, FloatingSizePreset.NORMAL.textSizeSp)
        assertEquals(0.85f, FloatingSizePreset.NORMAL.maxWidthFraction)
        assertEquals(22f, FloatingSizePreset.LARGE.textSizeSp)
        assertEquals(0.95f, FloatingSizePreset.LARGE.maxWidthFraction)
        assertEquals(26f, FloatingSizePreset.EXTRA_LARGE.textSizeSp)
        assertEquals(6, FloatingSizePreset.EXTRA_LARGE.maxLines)
    }

    @Test
    fun flingCollapse_ignoresMidScreenAndRequires16dpEdgeProximity() {
        assertNull(
            FloatingGesturePhysics.shouldCollapseOnFling(
                velocityX = 1800f,
                cardX = 290,
                cardWidth = 500,
                screenWidth = 1080,
                edgeThresholdPx = 48
            )
        )
        assertEquals(
            FloatingGesturePhysics.CollapseEdge.LEFT,
            FloatingGesturePhysics.shouldCollapseOnFling(
                velocityX = -1150f,
                cardX = 24,
                cardWidth = 500,
                screenWidth = 1080,
                edgeThresholdPx = 48
            )
        )
        assertEquals(
            FloatingGesturePhysics.CollapseEdge.RIGHT,
            FloatingGesturePhysics.shouldCollapseOnFling(
                velocityX = 1200f,
                cardX = 560,
                cardWidth = 500,
                screenWidth = 1080,
                edgeThresholdPx = 48
            )
        )
    }

    @Test
    fun springPhysics_constantsMatchSmoothTuning() {
        assertEquals(220f, FloatingGesturePhysics.SPRING_STIFFNESS)
        assertEquals(0.85f, FloatingGesturePhysics.SPRING_DAMPING_RATIO)
        assertEquals(1100f, FloatingGesturePhysics.FLING_COLLAPSE_VELOCITY_PX_PER_SEC)
        assertEquals(16, FloatingGesturePhysics.EDGE_PROXIMITY_DP)
    }
}