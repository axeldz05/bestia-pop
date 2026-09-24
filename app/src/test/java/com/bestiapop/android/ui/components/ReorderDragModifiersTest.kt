package com.bestiapop.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderDragModifiersTest {
    private val containerHeight = 1000f
    private val edgeZonePx = 100f
    private val maxSpeed = 800f

    @Test
    fun calculateAutoScrollSpeed_inNeutralCenter_returnsZero() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 500f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertEquals(0f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_nearTopEdge_returnsNegativeSpeed() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 50f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        // 50px into 100px zone: fraction = (100 - 50) / 100 = 0.5 -> speed = -800 * 0.5 = -400
        assertEquals(-400f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_atTopBoundary_returnsFullNegativeSpeed() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 0f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertEquals(-800f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_pastTopBoundary_clampsFactor() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = -200f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        // Past edge, clamped to 2.0x max speed
        assertEquals(-1600f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_nearBottomEdge_returnsPositiveSpeed() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 950f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        // 50px into bottom zone (start at 900): fraction = 50 / 100 = 0.5 -> speed = 800 * 0.5 = 400
        assertEquals(400f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_atBottomBoundary_returnsFullPositiveSpeed() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 1000f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertEquals(800f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_pastBottomBoundary_clampsFactor() {
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 1300f,
                containerHeight = containerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        // Clamped to 2.0x max speed
        assertEquals(1600f, speed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_zeroOrNegativeContainerHeight_returnsZero() {
        val zeroSpeed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 10f,
                containerHeight = 0f,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertEquals(0f, zeroSpeed, 0.001f)

        val negativeSpeed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 10f,
                containerHeight = -100f,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertEquals(0f, negativeSpeed, 0.001f)
    }

    @Test
    fun calculateAutoScrollSpeed_smallContainer_shrinksEdgeZoneProportionally() {
        val smallContainerHeight = 100f
        // Max edge is 28% of 100 = 28px
        val speed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 50f,
                containerHeight = smallContainerHeight,
                edgeZonePx = edgeZonePx, // 100px originally, should shrink to 28px
                maxSpeedPxPerSecond = maxSpeed,
            )
        // 50 is in the middle (between 28 and 72), so 0 speed
        assertEquals(0f, speed, 0.001f)

        val topSpeed =
            calculateAutoScrollSpeedPxPerSecond(
                pointerY = 14f,
                containerHeight = smallContainerHeight,
                edgeZonePx = edgeZonePx,
                maxSpeedPxPerSecond = maxSpeed,
            )
        assertTrue(topSpeed < 0f)
    }
}
