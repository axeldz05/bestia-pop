package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.preferences.clampSaveWhileListeningPercent

enum class SaveWhileListeningEvent {
    PROGRESS,
    PLAYBACK_COMPLETED,
    AUTOMATIC_TRANSITION,
    MANUAL_SKIP
}

/** Pure eligibility policy for saving a streamed track while it is being heard. */
object SaveWhileListeningPolicy {
    fun shouldSave(
        positionMs: Long,
        durationMs: Long,
        thresholdPercent: Int,
        event: SaveWhileListeningEvent = SaveWhileListeningEvent.PROGRESS
    ): Boolean {
        if (event == SaveWhileListeningEvent.PLAYBACK_COMPLETED ||
            event == SaveWhileListeningEvent.AUTOMATIC_TRANSITION
        ) {
            return true
        }
        if (durationMs <= 0L) return false

        val requiredMs = requiredPositionMs(
            durationMs = durationMs,
            thresholdPercent = clampSaveWhileListeningPercent(thresholdPercent)
        )
        return positionMs.coerceAtLeast(0L) >= requiredMs
    }

    private fun requiredPositionMs(durationMs: Long, thresholdPercent: Int): Long {
        val wholeHundreds = durationMs / 100L
        val remainder = durationMs % 100L
        return wholeHundreds * thresholdPercent +
            (remainder * thresholdPercent + 99L) / 100L
    }
}
