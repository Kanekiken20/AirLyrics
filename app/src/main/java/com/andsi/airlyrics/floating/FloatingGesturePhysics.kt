package com.andsi.airlyrics.floating

object FloatingGesturePhysics {
    const val SPRING_STIFFNESS = 220f
    const val SPRING_DAMPING_RATIO = 0.85f
    const val FLING_COLLAPSE_VELOCITY_PX_PER_SEC = 1100f
    const val EDGE_PROXIMITY_DP = 16

    const val PUFFY_DOCK_STIFFNESS = 260f
    const val PUFFY_DOCK_DAMPING = 0.64f
    const val PUFFY_POP_STIFFNESS = 240f
    const val PUFFY_POP_DAMPING = 0.56f
    const val PUFFY_SETTLE_STIFFNESS = 240f
    const val PUFFY_SETTLE_DAMPING = 0.62f
    const val EDGE_DROP_COLLAPSE_RATIO = 0.28f

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

    fun shouldCollapseOnRelease(
        velocityX: Float,
        cardX: Int,
        cardWidth: Int,
        screenWidth: Int,
        edgeThresholdPx: Int
    ): CollapseEdge? {
        shouldCollapseOnFling(velocityX, cardX, cardWidth, screenWidth, edgeThresholdPx)?.let { return it }
        val overhangPx = (cardWidth * EDGE_DROP_COLLAPSE_RATIO).toInt().coerceAtLeast(edgeThresholdPx)
        return when {
            cardX <= -overhangPx -> CollapseEdge.LEFT
            (cardX + cardWidth) >= (screenWidth + overhangPx) -> CollapseEdge.RIGHT
            else -> null
        }
    }
}
