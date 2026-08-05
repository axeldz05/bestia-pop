package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbRecordingMetadata
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.FetchAndMatchCfRecommendationsUseCase
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User-based CF recommendations as an extra Radio NEW/BOTH fill pool (not seed-based).
 */
class CfRecommendationsRadio(
    private val fetchCf: suspend (
        username: String,
        token: String?,
        count: Int,
        offset: Int,
        artistType: String
    ) -> LbApiResult<CfRecommendationsPayload>,
    private val fetchRecordingMetadata: suspend (
        mbids: List<String>,
        token: String?
    ) -> LbApiResult<Map<String, LbRecordingMetadata>>,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val fetchCount: Int = FETCH_COUNT,
    private val artistType: String = FetchAndMatchCfRecommendationsUseCase.ARTIST_TYPE_SIMILAR
) {

    private val mutex = Mutex()
    private var cachedUsername: String? = null
    private var cachedAtMs: Long = 0L
    private var cachedPlayables: List<PlayableItem> = emptyList()

    private val matcher = FetchAndMatchCfRecommendationsUseCase(
        fetchCf = fetchCf,
        fetchRecordingMetadata = fetchRecordingMetadata
    )

    suspend fun suggest(
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        username: String,
        token: String
    ): List<PlayableItem> {
        if (limit <= 0 || username.isBlank() || token.isBlank()) return emptyList()

        val pool = resolvePool(username, token, library)
        if (pool.isEmpty()) return emptyList()

        val seen = excludeKeys.toMutableSet()
        val results = ArrayList<PlayableItem>(limit)
        for (item in pool) {
            if (results.size >= limit) break
            val key = MatchListenBrainzTracksUseCase.matchKey(item.artist, item.title)
            val idKey = item.mediaId
            if (key.isNotEmpty() && key in seen) continue
            if (idKey in seen) continue
            if (key.isNotEmpty()) seen.add(key)
            seen.add(idKey)
            results.add(item)
        }
        return results
    }

    private suspend fun resolvePool(
        username: String,
        token: String,
        library: List<Song>
    ): List<PlayableItem> {
        mutex.withLock {
            val fresh = cachedUsername == username &&
                clockMs() - cachedAtMs < cacheTtlMs &&
                cachedPlayables.isNotEmpty()
            if (fresh) return cachedPlayables
        }

        val matchedResult = matcher.execute(
            username = username,
            token = token,
            library = library,
            count = fetchCount,
            artistType = artistType
        )
        val playables = when (matchedResult) {
            is LbApiResult.Success -> matchedResult.data.toPlayableItems()
            is LbApiResult.Failure -> emptyList()
        }

        mutex.withLock {
            cachedUsername = username
            cachedAtMs = clockMs()
            cachedPlayables = playables
        }
        return playables
    }

    companion object {
        const val CACHE_TTL_MS = 20L * 60L * 1000L
        const val FETCH_COUNT = 50
    }
}
