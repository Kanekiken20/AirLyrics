package com.andsi.airlyrics.floating

/**
 * Pure gesture and physics decision helper for floating lyrics window collapse and springs.
 */
object FloatingGesturePhysics {
    const val SPRING_STIFFNESS = 220f
    const val SPRING_DAMPING_RATIO = 0.85f // Calibrated smooth response without jarring bounce
    const val FLING_COLLAPSE_VELOCITY_PX_PER_SEC = 1100f
    const val EDGE_PROXIMITY_DP = 16

    enum class CollapseEdge { LEFT, RIGHT }

    fun shouldCollapseOnFling(
        velocityX: Float,
        cardX: Int,
        cardWidth: Int,
        screenWidth: Int,
        edgeThresholdPx: Int
    ): CollapseEdge? {
        val nearLeftEdge = cardX <= edgeThresholdPx
        val nearRightEdge = (cardX + cardWidth) >= (screenWidth - edgeThresholdPx)

        return when {
            nearLeftEdge && velocityX <= -FLING_COLLAPSE_VELOCITY_PX_PER_SEC -> CollapseEdge.LEFT
            nearRightEdge && velocityX >= FLING_COLLAPSE_VELOCITY_PX_PER_SEC -> CollapseEdge.RIGHT
            else -> null
        }
    }
}
