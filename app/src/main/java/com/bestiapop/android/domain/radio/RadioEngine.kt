package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase

data class RadioSuggestResult(
    val items: List<PlayableItem>,
    val usedListenBrainz: Boolean,
    /** True when EXPLORE attempted LB and it failed or threw (not merely empty because library was full). */
    val listenBrainzFailed: Boolean
)

/**
 * Orchestrates local (+ optional ListenBrainz + CF) radio suggestions into a deduped batch.
 * Seed similarity from library is primary; LB then CF fill remaining slots in EXPLORE mode.
 */
class RadioEngine(
    private val localRadio: LocalMetadataRadio = LocalMetadataRadio(),
    private val listenBrainzRadio: ListenBrainzRadio? = null,
    private val cfRecommendationsRadio: CfRecommendationsRadio? = null
) {

    suspend fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        mode: RadioMode,
        excludeKeys: Set<String>,
        limit: Int = DEFAULT_LIMIT,
        lbToken: String? = null,
        lbAvailable: Boolean = false,
        lbUsername: String? = null
    ): RadioSuggestResult {
        if (limit <= 0 || seed.artist.isBlank() || seed.title.isBlank()) {
            return RadioSuggestResult(
                items = emptyList(),
                usedListenBrainz = false,
                listenBrainzFailed = false
            )
        }

        val seedKey = MatchListenBrainzTracksUseCase.matchKey(seed.artist, seed.title)
        val seen = excludeKeys.toMutableSet()
        if (seedKey.isNotEmpty()) seen.add(seedKey)

        val combined = ArrayList<PlayableItem>(limit)

        fun addAll(items: List<PlayableItem>): Int {
            var added = 0
            for (item in items) {
                if (combined.size >= limit) return added
                val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
                val idKey = item.mediaId
                if (key.isNotEmpty() && key in seen) continue
                if (idKey in seen) continue
                if (key.isNotEmpty()) seen.add(key)
                seen.add(idKey)
                combined.add(item)
                added++
            }
            return added
        }

        // Primary: similar tracks from the seed song's library metadata
        val localItems = localRadio.suggest(
            seed = seed,
            library = library,
            excludeKeys = seen,
            limit = limit
        )
        addAll(localItems)

        val useLb = mode == RadioMode.EXPLORE &&
            lbAvailable &&
            !lbToken.isNullOrBlank() &&
            listenBrainzRadio != null

        var usedListenBrainz = false
        var listenBrainzFailed = false

        if (useLb && combined.size < limit) {
            val lbOutcome = runCatching {
                listenBrainzRadio!!.suggest(
                    seed = seed,
                    library = library,
                    excludeKeys = seen,
                    limit = limit - combined.size,
                    token = lbToken!!
                )
            }
            if (lbOutcome.isFailure) {
                listenBrainzFailed = true
            } else {
                val lbItems = lbOutcome.getOrDefault(emptyList())
                if (lbItems.isEmpty()) {
                    listenBrainzFailed = true
                } else {
                    addAll(lbItems)
                    usedListenBrainz = true
                }
            }
        }

        val useCf = mode == RadioMode.EXPLORE &&
            lbAvailable &&
            !lbToken.isNullOrBlank() &&
            !lbUsername.isNullOrBlank() &&
            cfRecommendationsRadio != null &&
            combined.size < limit

        if (useCf) {
            val cfOutcome = runCatching {
                cfRecommendationsRadio!!.suggest(
                    library = library,
                    excludeKeys = seen,
                    limit = limit - combined.size,
                    username = lbUsername!!,
                    token = lbToken!!
                )
            }
            val cfItems = cfOutcome.getOrDefault(emptyList())
            if (cfItems.isNotEmpty()) {
                addAll(cfItems)
                usedListenBrainz = true
                // CF rescued online EXPLORE; do not force-degrade to EASY
                listenBrainzFailed = false
            }
        }

        return RadioSuggestResult(
            items = combined,
            usedListenBrainz = usedListenBrainz,
            listenBrainzFailed = listenBrainzFailed
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 30
    }
}
