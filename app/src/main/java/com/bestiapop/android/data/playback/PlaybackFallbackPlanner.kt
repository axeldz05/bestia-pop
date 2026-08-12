package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.PlayableItem

/**
 * One candidate in circular playback fallback order.
 *
 * Callers apply [ReadyLocal] immediately. A failed [ResolveRemote] advances to the next step.
 */
sealed interface PlaybackFallbackStep {
    val queueIndex: Int
    val item: PlayableItem

    data class ReadyLocal(
        override val queueIndex: Int,
        override val item: PlayableItem.Local
    ) : PlaybackFallbackStep

    data class ResolveRemote(
        override val queueIndex: Int,
        override val item: PlayableItem.Remote
    ) : PlaybackFallbackStep
}

/** Pure planner for trying every queue item once, starting at the selected slot and wrapping. */
object PlaybackFallbackPlanner {
    fun circularPlan(
        items: List<PlayableItem>,
        startIndex: Int
    ): List<PlaybackFallbackStep> {
        if (items.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(items.indices)
        return List(items.size) { offset ->
            val queueIndex = (start + offset) % items.size
            when (val item = items[queueIndex]) {
                is PlayableItem.Local ->
                    PlaybackFallbackStep.ReadyLocal(queueIndex, item)
                is PlayableItem.Remote ->
                    PlaybackFallbackStep.ResolveRemote(queueIndex, item)
            }
        }
    }
}
