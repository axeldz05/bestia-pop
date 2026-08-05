package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbMetadataLookup
import com.bestiapop.android.data.listenbrainz.LbRadioRecording
import com.bestiapop.android.data.listenbrainz.LbRecordingMetadata
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches ListenBrainz lb-radio suggestions and maps them to Local or Remote playables.
 */
class ListenBrainzRadio(
    private val lookupMetadata: suspend (
        artist: String,
        recording: String,
        token: String
    ) -> LbApiResult<LbMetadataLookup>,
    private val fetchLbRadio: suspend (
        artistMbid: String,
        token: String,
        mode: String
    ) -> LbApiResult<List<LbRadioRecording>>,
    private val fetchRecordingMetadata: suspend (
        mbids: List<String>,
        token: String
    ) -> LbApiResult<Map<String, LbRecordingMetadata>>,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val artistMbidTtlMs: Long = ARTIST_MBID_TTL_MS
) {

    private val artistMbidCache = HashMap<String, CachedArtistMbid>()
    private val recordingMetaCache = HashMap<String, LbRecordingMetadata>()
    private val mutex = Mutex()

    suspend fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        token: String,
        lbMode: String = DEFAULT_LB_MODE
    ): List<PlayableItem> {
        if (limit <= 0 || token.isBlank()) return emptyList()
        if (seed.artist.isBlank() || seed.title.isBlank()) return emptyList()

        val artistMbid = resolveArtistMbid(seed.artist, seed.title, token) ?: return emptyList()
        val radioResult = fetchLbRadio(artistMbid, token, lbMode)
        val recordings = when (radioResult) {
            is LbApiResult.Success -> radioResult.data
            is LbApiResult.Failure -> return emptyList()
        }
        if (recordings.isEmpty()) return emptyList()

        val mbids = recordings.map { it.recordingMbid }.distinct()
        val metaByMbid = resolveRecordingMetadata(mbids, token)

        val libraryIndex = MatchListenBrainzTracksUseCase.buildLibraryIndex(library)
        val artistFallback = HashMap<String, String>()
        for (rec in recordings) {
            val name = rec.similarArtistName?.takeIf { it.isNotBlank() } ?: continue
            artistFallback[rec.recordingMbid] = name
        }

        val results = ArrayList<PlayableItem>(limit)
        val seen = excludeKeys.toMutableSet()
        val seedKey = MatchListenBrainzTracksUseCase.matchKey(seed.artist, seed.title)
        if (seedKey.isNotEmpty()) seen.add(seedKey)

        for (rec in recordings) {
            if (results.size >= limit) break
            val meta = metaByMbid[rec.recordingMbid]
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: continue
            val artist = meta?.artist?.takeIf { it.isNotBlank() }
                ?: artistFallback[rec.recordingMbid]
                ?: continue

            val key = MatchListenBrainzTracksUseCase.matchKey(artist, title)
            if (key.isEmpty() || key in seen) continue
            seen.add(key)

            val local = libraryIndex[key]
            if (local != null) {
                results.add(local.toPlayable())
            } else {
                results.add(
                    PlayableItem.Remote(
                        title = title,
                        artist = artist,
                        album = meta?.releaseName,
                        recordingMbid = rec.recordingMbid,
                        youtubeQueryOrId = "$artist $title"
                    )
                )
            }
        }
        return results
    }

    private suspend fun resolveArtistMbid(
        artist: String,
        title: String,
        token: String
    ): String? {
        val cacheKey = MatchListenBrainzTracksUseCase.normalize(artist)
        if (cacheKey.isEmpty()) return null

        mutex.withLock {
            val cached = artistMbidCache[cacheKey]
            if (cached != null && clockMs() - cached.storedAtMs < artistMbidTtlMs) {
                return cached.mbid
            }
        }

        val result = lookupMetadata(artist, title, token)
        val mbid = when (result) {
            is LbApiResult.Success -> result.data.artistMbids.firstOrNull()?.takeIf { it.isNotBlank() }
            is LbApiResult.Failure -> null
        }

        if (mbid != null) {
            mutex.withLock {
                artistMbidCache[cacheKey] = CachedArtistMbid(mbid, clockMs())
            }
        }
        return mbid
    }

    private suspend fun resolveRecordingMetadata(
        mbids: List<String>,
        token: String
    ): Map<String, LbRecordingMetadata> {
        if (mbids.isEmpty()) return emptyMap()

        val missing = ArrayList<String>()
        val result = HashMap<String, LbRecordingMetadata>()
        mutex.withLock {
            for (mbid in mbids) {
                val cached = recordingMetaCache[mbid]
                if (cached != null) {
                    result[mbid] = cached
                } else {
                    missing.add(mbid)
                }
            }
        }

        if (missing.isNotEmpty()) {
            when (val fetched = fetchRecordingMetadata(missing, token)) {
                is LbApiResult.Success -> {
                    mutex.withLock {
                        for ((mbid, meta) in fetched.data) {
                            recordingMetaCache[mbid] = meta
                            result[mbid] = meta
                        }
                    }
                }
                is LbApiResult.Failure -> Unit
            }
        }
        return result
    }

    private data class CachedArtistMbid(val mbid: String, val storedAtMs: Long)

    companion object {
        const val DEFAULT_LB_MODE = "medium"
        const val ARTIST_MBID_TTL_MS = 60L * 60L * 1000L
    }
}
