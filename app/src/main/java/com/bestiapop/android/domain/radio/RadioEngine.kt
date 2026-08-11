package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

data class RadioSuggestResult(
    val items: List<PlayableItem>,
    val usedOnlineDiscovery: Boolean,
    /** True when NEW/BOTH attempted online fills and none contributed usable Remotes. */
    val onlineDiscoveryFailed: Boolean
)

/**
 * Orchestrates local and online radio suggestions into a deduped batch.
 *
 * Remote fill order: ListenBrainz → CF → [SimilarTracksProvider] (Deezer + iTunes fill).
 *
 * - [RadioMode.KNOWN]: library similarity only
 * - [RadioMode.NEW]: Remotes only; library matches from providers are skipped
 * - [RadioMode.BOTH]: interleave Remote, Local, Remote, Local…; fill from the other side if one runs out
 */
class RadioEngine(
    private val localRadio: LocalMetadataRadio = LocalMetadataRadio(),
    private val listenBrainzRadio: ListenBrainzRadio? = null,
    private val cfRecommendationsRadio: CfRecommendationsRadio? = null,
    private val similarProviders: List<SimilarTracksProvider> = emptyList()
) {

    suspend fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        mode: RadioMode,
        excludeKeys: Set<String>,
        limit: Int = DEFAULT_LIMIT,
        lbToken: String? = null,
        lbAvailable: Boolean = false,
        lbUsername: String? = null,
        networkAvailable: Boolean = false,
        coPlaylistSongIds: Set<Long> = emptySet()
    ): RadioSuggestResult {
        if (limit <= 0 || seed.artist.isBlank() || seed.title.isBlank()) {
            return RadioSuggestResult(
                items = emptyList(),
                usedOnlineDiscovery = false,
                onlineDiscoveryFailed = false
            )
        }

        val seedKey = TrackMatchKeys.matchKey(seed.artist, seed.title)
        val baseSeen = excludeKeys.toMutableSet()
        if (seedKey.isNotEmpty()) baseSeen.add(seedKey)

        return when (mode) {
            RadioMode.KNOWN -> suggestKnown(
                seed = seed,
                library = library,
                excludeKeys = baseSeen,
                limit = limit,
                coPlaylistSongIds = coPlaylistSongIds
            )
            RadioMode.NEW -> suggestNew(
                seed = seed,
                library = library,
                excludeKeys = baseSeen,
                limit = limit,
                lbToken = lbToken,
                lbAvailable = lbAvailable,
                lbUsername = lbUsername,
                networkAvailable = networkAvailable
            )
            RadioMode.BOTH -> suggestBoth(
                seed = seed,
                library = library,
                excludeKeys = baseSeen,
                limit = limit,
                lbToken = lbToken,
                lbAvailable = lbAvailable,
                lbUsername = lbUsername,
                networkAvailable = networkAvailable,
                coPlaylistSongIds = coPlaylistSongIds
            )
        }
    }

    /**
     * Multi-seed radio batch for playlist preview: call [suggest] per seed, then
     * round-robin merge with global [TrackMatchKeys] dedupe (seeds always excluded).
     */
    suspend fun suggestFromSeeds(
        seeds: List<PlayableItem>,
        library: List<Song>,
        mode: RadioMode,
        excludeKeys: Set<String>,
        limit: Int = PREVIEW_DEFAULT_LIMIT,
        lbToken: String? = null,
        lbAvailable: Boolean = false,
        lbUsername: String? = null,
        networkAvailable: Boolean = false,
        coPlaylistSongIds: Set<Long> = emptySet(),
        maxSeeds: Int = MAX_SEEDS
    ): RadioSuggestResult {
        if (limit <= 0) {
            return RadioSuggestResult(
                items = emptyList(),
                usedOnlineDiscovery = false,
                onlineDiscoveryFailed = false
            )
        }
        val validSeeds = seeds
            .asSequence()
            .filter { it.artist.isNotBlank() && it.title.isNotBlank() }
            .distinctBy {
                TrackMatchKeys.matchKey(it.artist, it.title).ifEmpty { it.mediaId }
            }
            .take(maxSeeds)
            .toList()
        if (validSeeds.isEmpty()) {
            return RadioSuggestResult(
                items = emptyList(),
                usedOnlineDiscovery = false,
                onlineDiscoveryFailed = false
            )
        }

        val seedKeys = validSeeds.mapNotNull { seed ->
            TrackMatchKeys.matchKey(seed.artist, seed.title).takeIf { it.isNotEmpty() }
        }.toSet()
        val baseExclude = excludeKeys + seedKeys
        val limitPerSeed = (limit + validSeeds.size - 1) / validSeeds.size

        val perSeed = validSeeds.map { seed ->
            suggest(
                seed = seed,
                library = library,
                mode = mode,
                excludeKeys = baseExclude,
                limit = limitPerSeed,
                lbToken = lbToken,
                lbAvailable = lbAvailable,
                lbUsername = lbUsername,
                networkAvailable = networkAvailable,
                coPlaylistSongIds = coPlaylistSongIds
            )
        }

        val merged = roundRobinMerge(
            lists = perSeed.map { it.items },
            limit = limit,
            initialSeen = baseExclude
        )
        return RadioSuggestResult(
            items = merged,
            usedOnlineDiscovery = perSeed.any { it.usedOnlineDiscovery },
            onlineDiscoveryFailed = merged.isEmpty() && perSeed.any { it.onlineDiscoveryFailed }
        )
    }

    private fun suggestKnown(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        coPlaylistSongIds: Set<Long>
    ): RadioSuggestResult {
        val items = localRadio.suggest(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            coPlaylistSongIds = coPlaylistSongIds
        )
        return RadioSuggestResult(
            items = items,
            usedOnlineDiscovery = false,
            onlineDiscoveryFailed = false
        )
    }

    private suspend fun suggestNew(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?,
        networkAvailable: Boolean
    ): RadioSuggestResult {
        val remote = fetchRemotes(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            lbToken = lbToken,
            lbAvailable = lbAvailable,
            lbUsername = lbUsername,
            networkAvailable = networkAvailable
        )
        return RadioSuggestResult(
            items = remote.items,
            usedOnlineDiscovery = remote.usedOnlineDiscovery,
            onlineDiscoveryFailed = remote.onlineDiscoveryFailed
        )
    }

    private suspend fun suggestBoth(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?,
        networkAvailable: Boolean,
        coPlaylistSongIds: Set<Long>
    ): RadioSuggestResult {
        val localItems = localRadio.suggest(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            coPlaylistSongIds = coPlaylistSongIds
        )
        val remote = fetchRemotes(
            seed = seed,
            library = library,
            excludeKeys = excludeKeys,
            limit = limit,
            lbToken = lbToken,
            lbAvailable = lbAvailable,
            lbUsername = lbUsername,
            networkAvailable = networkAvailable
        )
        val interleaved = interleaveEquitable(remote.items, localItems, limit)
        return RadioSuggestResult(
            items = interleaved,
            usedOnlineDiscovery = remote.usedOnlineDiscovery,
            onlineDiscoveryFailed = remote.onlineDiscoveryFailed && localItems.isEmpty()
        )
    }

    private data class RemoteFetch(
        val items: List<PlayableItem.Remote>,
        val usedOnlineDiscovery: Boolean,
        val onlineDiscoveryFailed: Boolean
    )

    private suspend fun fetchRemotes(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        lbToken: String?,
        lbAvailable: Boolean,
        lbUsername: String?,
        networkAvailable: Boolean
    ): RemoteFetch {
        if (limit <= 0) {
            return RemoteFetch(emptyList(), usedOnlineDiscovery = false, onlineDiscoveryFailed = false)
        }

        val seen = excludeKeys.toMutableSet()
        val remotes = ArrayList<PlayableItem.Remote>(limit)
        var usedOnlineDiscovery = false

        fun addRemotes(items: List<PlayableItem>): Int {
            var added = 0
            for (item in items) {
                if (remotes.size >= limit) break
                if (tryAddRemote(item, remotes, seen, limit)) added++
            }
            return added
        }

        val canUseLb = lbAvailable && !lbToken.isNullOrBlank()
        val lbRadio = listenBrainzRadio
        // canUseLb already implies lbToken is non-null and non-blank
        val token = lbToken.takeIf { canUseLb }

        if (token != null && lbRadio != null) {
            val lbOutcome = runCatching {
                lbRadio.suggest(
                    seed = seed,
                    library = library,
                    excludeKeys = seen,
                    limit = limit,
                    token = token
                )
            }
            if (lbOutcome.isSuccess) {
                val lbItems = lbOutcome.getOrDefault(emptyList())
                if (addRemotes(lbItems) > 0) {
                    usedOnlineDiscovery = true
                }
            }
        }

        val cfRadio = cfRecommendationsRadio
        if (token != null &&
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
                usedOnlineDiscovery = true
            }
        }

        if (networkAvailable && remotes.size < limit) {
            for (provider in similarProviders) {
                if (remotes.size >= limit) break
                val outcome = runCatching {
                    provider.suggest(
                        seed = seed,
                        library = library,
                        excludeKeys = seen,
                        limit = limit - remotes.size
                    )
                }
                val items = outcome.getOrDefault(emptyList())
                if (addRemotes(items) > 0) {
                    usedOnlineDiscovery = true
                }
            }
        }

        val onlineDiscoveryFailed = remotes.isEmpty() && networkAvailable

        return RemoteFetch(
            items = remotes,
            usedOnlineDiscovery = usedOnlineDiscovery,
            onlineDiscoveryFailed = onlineDiscoveryFailed
        )
    }

    /** L2: accept a Remote into [remotes] if not already in [seen] (matchKey + mediaId). */
    private fun tryAddRemote(
        item: PlayableItem,
        remotes: MutableList<PlayableItem.Remote>,
        seen: MutableSet<String>,
        limit: Int
    ): Boolean {
        if (remotes.size >= limit) return false
        if (item !is PlayableItem.Remote) return false
        val key = TrackMatchKeys.matchKey(item.artist, item.title)
        val idKey = item.mediaId
        if (key.isNotEmpty() && key in seen) return false
        if (idKey in seen) return false
        if (key.isNotEmpty()) seen.add(key)
        seen.add(idKey)
        remotes.add(item)
        return true
    }

    companion object {
        const val DEFAULT_LIMIT = 30
        const val PREVIEW_DEFAULT_LIMIT = 40
        const val MAX_SEEDS = 10

        /**
         * Take one item from each seed list per round; skip already-seen matchKey/mediaId.
         */
        internal fun roundRobinMerge(
            lists: List<List<PlayableItem>>,
            limit: Int,
            initialSeen: Set<String>
        ): List<PlayableItem> {
            if (limit <= 0 || lists.isEmpty()) return emptyList()
            val seen = initialSeen.toMutableSet()
            val queues = lists.map { ArrayDeque(it) }
            val result = ArrayList<PlayableItem>(minOf(limit, lists.sumOf { it.size }))
            var progressed = true
            while (result.size < limit && progressed) {
                progressed = false
                for (queue in queues) {
                    if (result.size >= limit) break
                    while (queue.isNotEmpty()) {
                        val item = queue.removeFirst()
                        val key = TrackMatchKeys.matchKey(item.artist, item.title)
                        val idKey = item.mediaId
                        if (key.isNotEmpty() && key in seen) continue
                        if (idKey in seen) continue
                        if (key.isNotEmpty()) seen.add(key)
                        seen.add(idKey)
                        result.add(item)
                        progressed = true
                        break
                    }
                }
            }
            return result
        }

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
