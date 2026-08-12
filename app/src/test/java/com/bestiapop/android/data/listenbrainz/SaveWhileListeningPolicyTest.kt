package com.bestiapop.android.data.listenbrainz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveWhileListeningPolicyTest {

    @Test
    fun progress_savesOnlyAfterConfiguredPercent() {
        assertFalse(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 24_999L,
                durationMs = 100_000L,
                thresholdPercent = 25
            )
        )
        assertTrue(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 25_000L,
                durationMs = 100_000L,
                thresholdPercent = 25
            )
        )
    }

    @Test
    fun hundredPercent_requiresTheWholeKnownDuration() {
        assertFalse(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 99_999L,
                durationMs = 100_000L,
                thresholdPercent = 100
            )
        )
        assertTrue(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 100_000L,
                durationMs = 100_000L,
                thresholdPercent = 100
            )
        )
    }

    @Test
    fun completed_savesWhenFinalPositionWasNotSampled() {
        assertTrue(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 99_999L,
                durationMs = 100_000L,
                thresholdPercent = 100,
                event = SaveWhileListeningEvent.PLAYBACK_COMPLETED
            )
        )
    }

    @Test
    fun automaticTransition_provesCompletionWithUnknownDuration() {
        assertTrue(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 0L,
                durationMs = 0L,
                thresholdPercent = 100,
                event = SaveWhileListeningEvent.AUTOMATIC_TRANSITION
            )
        )
    }

    @Test
    fun unknownDuration_progressCannotReachAPercent() {
        assertFalse(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 240_000L,
                durationMs = 0L,
                thresholdPercent = 25
            )
        )
    }

    @Test
    fun manualSkip_doesNotPretendTheTrackCompleted() {
        assertFalse(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 99_999L,
                durationMs = 100_000L,
                thresholdPercent = 100,
                event = SaveWhileListeningEvent.MANUAL_SKIP
            )
        )
        assertFalse(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 240_000L,
                durationMs = 0L,
                thresholdPercent = 25,
                event = SaveWhileListeningEvent.MANUAL_SKIP
            )
        )
    }

    @Test
    fun manualSkip_afterConfiguredPercent_remainsEligible() {
        assertTrue(
            SaveWhileListeningPolicy.shouldSave(
                positionMs = 30_000L,
                durationMs = 100_000L,
                thresholdPercent = 25,
                event = SaveWhileListeningEvent.MANUAL_SKIP
            )
        )
    }
}
