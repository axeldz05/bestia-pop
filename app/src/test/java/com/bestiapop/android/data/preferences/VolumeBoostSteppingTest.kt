package com.bestiapop.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class VolumeBoostSteppingTest {

    @Test
    fun volumeBoostStepping_increasesByTenPercentPerStepUntilMax() {
        var currentBoost = 0f
        val step = 0.10f

        for (i in 1..10) {
            currentBoost = (currentBoost + step).coerceIn(0f, 1f)
            val expectedPercentage = 100 + i * 10
            val actualPercentage = ((1f + currentBoost) * 100f).roundToInt()
            assertEquals(expectedPercentage, actualPercentage)
        }

        // Stepping beyond 200% stays clamped at 1.0f (200%)
        currentBoost = (currentBoost + step).coerceIn(0f, 1f)
        assertEquals(1.0f, currentBoost, 0.001f)
        assertEquals(200, ((1f + currentBoost) * 100f).roundToInt())
    }

    @Test
    fun volumeBoostStepping_decreasesByTenPercentUntilZero() {
        var currentBoost = 0.50f // 150%
        val step = 0.10f

        // Step down from 150% to 100%
        val expectedPercentages = listOf(140, 130, 120, 110, 100)
        for (expected in expectedPercentages) {
            currentBoost = (currentBoost - step).coerceAtLeast(0f)
            val actualPercentage = ((1f + currentBoost) * 100f).roundToInt()
            assertEquals(expected, actualPercentage)
        }

        // Stepping down at 0% remains 0f
        currentBoost = (currentBoost - step).coerceAtLeast(0f)
        assertEquals(0f, currentBoost, 0.001f)
    }

    @Test
    fun volumeBoostState_distinguishesBoostedFromSystemLevel() {
        val boostEnabled = true
        val boostAmount = 0.30f

        val isBoosted = boostEnabled && boostAmount > 0.001f
        assertTrue(isBoosted)

        val unboosted = boostEnabled && 0.0f > 0.001f
        assertTrue(!unboosted)
    }
}
