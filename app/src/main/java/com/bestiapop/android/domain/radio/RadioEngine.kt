package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase

data class RadioSuggestResult(
    val items: List<PlayableItem>,
    val usedListenBrainz: Boolean,
    /** True when NEW/BOTH attempted LB/CF and neither contributed usable Remotes. */
    val listenBrainzFailed: Boolean
)

/**
 * Orchestrates local and/or ListenBrainz + CF radio suggestions into a deduped batch.
 *
 * - [RadioMode.KNOWN]: library similarity only
 * - [RadioMode.NEW]: Remotes only (LB then CF); library matches from LB/CF are skipped
 * - [RadioMode.BOTH]: interleave Remote, Local, Remote, Local…; fill from the other side if one runs out
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
        val baseSeen = excludeKeys.toMutableSet()
        if (seedKey.isNotEmpty()) baseSeen.add(seedKey)

        return when (mode) {
            RadioMode.KNOWN -> suggestKnown(seed, library, baseSeen, limit)
            RadioMode.NEW -> suggestNew(
                seed = seed,
                library = library,
                excludeKeys = baseSeen,
                limit = limit,
                lbToken = lbToken,
                lbAvailable = lbAvailable,
                lbUsername = lbUsername
            )
            RadioMode.BOTH -> suggestBoth(
                seed = seed,
                library = library,
                excludeKeys = baseSeen,
                limit = limit,
                lbToken = lbToken,
                lbAvailable = lbAvailable,
                lbUsername = lbUsername
            )
        }
    }

    private fun suggestKnown(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int
    ): RadioSuggestResult {
        val items = localRadio.suggest(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit
        )
        return RadioSuggestResult(
            items = items,
            usedListenBrainz = false,
            listenBrainzFailed = false
        )
    }

    private suspend fun suggestNew(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?
    ): RadioSuggestResult {
        val remote = fetchRemotes(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            lbToken = lbToken,
            lbAvailable = lbAvailable,
            lbUsername = lbUsername
        )
        return RadioSuggestResult(
            items = remote.items,
            usedListenBrainz = remote.usedListenBrainz,
            listenBrainzFailed = remote.listenBrainzFailed
        )
    }

    private suspend fun suggestBoth(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?
    ): RadioSuggestResult {
        val localItems = localRadio.suggest(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit
        )
        val remote = fetchRemotes(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            lbToken = lbToken,
            lbAvailable = lbAvailable,
            lbUsername = lbUsername
        )
        val interleaved = interleaveEquitable(remote.items, localItems, limit)
        return RadioSuggestResult(
            items = interleaved,
            usedListenBrainz = remote.usedListenBrainz,
            listenBrainzFailed = remote.listenBrainzFailed && localItems.isEmpty()
        )
    }

    private data class RemoteFetch(
        val items: List<PlayableItem.Remote>,
        val usedListenBrainz: Boolean,
        val listenBrainzFailed: Boolean
    )

    private suspend fun fetchRemotes(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?
    ): RemoteFetch {
        if (limit <= 0) {
            return RemoteFetch(emptyList(), usedListenBrainz = false, listenBrainzFailed = false)
        }

        val seen = excludeKeys.toMutableSet()
        val remotes = ArrayList<PlayableItem.Remote>(limit)
        var usedListenBrainz = false
        var listenBrainzFailed = false

        fun addRemotes(items: List<PlayableItem>): Int {
            var added = 0
            for (item in items) {
                if (remotes.size >= limit) return added
                if (item !is PlayableItem.Remote) continue
                val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
                val idKey = item.mediaId
                if (key.isNotEmpty() && key in seen) continue
                if (idKey in seen) continue
                if (key.isNotEmpty()) seen.add(key)
                seen.add(idKey)
                remotes.add(item)
                added++
            }
            return added
        }

        val canUseOnline = lbAvailable && !lbToken.isNullOrBlank()
        val lbRadio = listenBrainzRadio
        val token = lbToken

        if (canUseOnline && lbRadio != null && token != null) {
            val lbOutcome = runCatching {
                lbRadio.suggest(
                    seed = seed,
                    library = library,
                    excludeKeys = seen,
                    limit = limit,
                    token = token
                )
            }
            if (lbOutcome.isFailure) {
                listenBrainzFailed = true
            } else {
                val lbItems = lbOutcome.getOrDefault(emptyList())
                val added = addRemotes(lbItems)
                if (added > 0) {
                    usedListenBrainz = true
                } else if (lbItems.isEmpty()) {
                    listenBrainzFailed = true
                }
                // Non-empty LB but all Local matches → treat as no usable nuevos for this fill
                else if (remotes.isEmpty()) {
                    listenBrainzFailed = true
                }
            }
        } else if (!canUseOnline) {
            listenBrainzFailed = true
        }

        val cfRadio = cfRecommendationsRadio
        if (canUseOnline &&
            token != null &&
            !lbUsername.isNullOrBlank() &&
            cfRadio != null &&
            remotes.size < limit
        ) {
            val cfOutcome = runCatching {
                cfRadio.suggest(
                    library = library,
                    excludeKeys = seen,
                    limit = limit - remotes.size,
                    username = lbUsername!!,
                    token = token
                )
            }
            val cfItems = cfOutcome.getOrDefault(emptyList())
            if (addRemotes(cfItems) > 0) {
                usedListenBrainz = true
                listenBrainzFailed = false
            }
        }

        if (remotes.isEmpty() && canUseOnline) {
            listenBrainzFailed = true
        }

        return RemoteFetch(
            items = remotes,
            usedListenBrainz = usedListenBrainz,
            listenBrainzFailed = listenBrainzFailed
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 30

        /** Online first, then offline, alternating; drain remaining when one side is empty. */
        internal fun interleaveEquitable(
            online: List<PlayableItem>,
            offline: List<PlayableItem>,
            limit: Int
        ): List<PlayableItem> {
            if (limit <= 0) return emptyList()
            val result = ArrayList<PlayableItem>(minOf(limit, online.size + offline.size))
            var i = 0
            var j = 0
            var takeOnline = true
            while (result.size < limit && (i < online.size || j < offline.size)) {
                if (takeOnline) {
                    if (i < online.size) {
                        result.add(online[i++])
                    } else if (j < offline.size) {
                        result.add(offline[j++])
                    }
                } else {
                    if (j < offline.size) {
                        result.add(offline[j++])
                    } else if (i < online.size) {
                        result.add(online[i++])
                    }
                }
                takeOnline = !takeOnline
            }
            return result
        }
    }
}
