package com.bestiapop.android.service

/**
 * Calculates linear volume attenuation (0.0f to 1.0f) for crossfade transition.
 * Fades in during the first [crossfadeDurationSeconds] of playback, and fades out
 * during the last [crossfadeDurationSeconds].
 *
 * For short tracks (duration < 3 * fadeMs), the effective fade time is clamped
 * to duration / 3 (with a 500ms minimum) to guarantee a stable playback body.
 */
fun calculateCrossfadeVolume(
    positionMs: Long,
    durationMs: Long,
    crossfadeDurationSeconds: Int
): Float {
    if (durationMs < 1500L || positionMs < 0L) return 1f
    val fadeMs = (crossfadeDurationSeconds * 1000L).coerceIn(500L, 10000L)
    val effectiveFadeMs = minOf(fadeMs, durationMs / 3).coerceAtLeast(500L)

    return when {
        positionMs < effectiveFadeMs -> {
            (positionMs.toFloat() / effectiveFadeMs).coerceIn(0f, 1f)
        }
        positionMs > durationMs - effectiveFadeMs -> {
            ((durationMs - positionMs).toFloat() / effectiveFadeMs).coerceIn(0f, 1f)
        }
        else -> 1f
    }
}
