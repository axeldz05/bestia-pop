package com.bestiapop.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCrossfadeTest {

    @Test
    fun calculateCrossfadeVolume_fadesInAtStart() {
        val duration = 100_000L
        val fadeSeconds = 3 // 3000ms

        assertEquals(0f, calculateCrossfadeVolume(0L, duration, fadeSeconds), 0.001f)
        assertEquals(0.5f, calculateCrossfadeVolume(1500L, duration, fadeSeconds), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(3000L, duration, fadeSeconds), 0.001f)
    }

    @Test
    fun calculateCrossfadeVolume_fullVolumeInMiddle() {
        val duration = 100_000L
        val fadeSeconds = 3

        assertEquals(1f, calculateCrossfadeVolume(3001L, duration, fadeSeconds), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(50_000L, duration, fadeSeconds), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(97_000L, duration, fadeSeconds), 0.001f)
    }

    @Test
    fun calculateCrossfadeVolume_fadesOutAtEnd() {
        val duration = 100_000L
        val fadeSeconds = 3 // 3000ms, fade-out starts at 97_000L

        assertEquals(0.5f, calculateCrossfadeVolume(98_500L, duration, fadeSeconds), 0.001f)
        assertEquals(0f, calculateCrossfadeVolume(100_000L, duration, fadeSeconds), 0.001f)
    }

    @Test
    fun calculateCrossfadeVolume_clampsFadeForShortTracks() {
        val duration = 6000L
        val fadeSeconds = 5 // requested 5000ms, capped at duration / 3 = 2000ms

        assertEquals(0f, calculateCrossfadeVolume(0L, duration, fadeSeconds), 0.001f)
        assertEquals(0.5f, calculateCrossfadeVolume(1000L, duration, fadeSeconds), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(2000L, duration, fadeSeconds), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(3000L, duration, fadeSeconds), 0.001f)
        assertEquals(0.5f, calculateCrossfadeVolume(5000L, duration, fadeSeconds), 0.001f)
        assertEquals(0f, calculateCrossfadeVolume(6000L, duration, fadeSeconds), 0.001f)
    }

    @Test
    fun calculateCrossfadeVolume_handlesInvalidOrUnknownDuration() {
        assertEquals(1f, calculateCrossfadeVolume(0L, 0L, 3), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(0L, -1L, 3), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(-10L, 100_000L, 3), 0.001f)
    }

    @Test
    fun calculateCrossfadeVolume_bypassesCrossfadeForVeryShortTracks() {
        val shortDuration = 1200L
        assertEquals(1f, calculateCrossfadeVolume(0L, shortDuration, 3), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(600L, shortDuration, 3), 0.001f)
        assertEquals(1f, calculateCrossfadeVolume(1199L, shortDuration, 3), 0.001f)
    }
}
