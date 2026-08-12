package com.bestiapop.android.data.playback

import java.util.concurrent.atomic.AtomicLong

/** Opaque generation captured when an asynchronous remote selection starts. */
@JvmInline
value class PlaybackSelectionIntentToken internal constructor(
    internal val generation: Long
)

/**
 * Latest-wins gate for asynchronous playback selections.
 *
 * Check [isCurrent] immediately before mutating the queue/player. The caller still owns and may
 * cancel its coroutine; [onLocalSelected] invalidates every outstanding remote completion.
 */
class PlaybackSelectionIntentGate {
    private val generation = AtomicLong(0L)

    fun beginRemoteSelection(): PlaybackSelectionIntentToken =
        PlaybackSelectionIntentToken(generation.incrementAndGet())

    fun isCurrent(token: PlaybackSelectionIntentToken): Boolean =
        generation.get() == token.generation

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun onLocalSelected() {
        invalidate()
    }
}
