package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song

/**
 * Contract for online (or hybrid) similar-track fills used by [RadioEngine].
 * Implementations should prefer [PlayableItem.Remote]; [RadioEngine] keeps only Remotes in NEW/BOTH.
 */
interface SimilarTracksProvider {
    /** Short id for logs / internal telemetry (not UI). */
    val id: String

    suspend fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int
    ): List<PlayableItem>
}
