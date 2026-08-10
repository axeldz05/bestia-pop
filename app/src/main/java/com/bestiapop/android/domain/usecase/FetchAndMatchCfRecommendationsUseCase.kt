package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbRecordingMetadata
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.TrackMatchKeys

/**
 * Fetches CF recording recommendations, resolves metadata, and matches against the local library.
 */
class FetchAndMatchCfRecommendationsUseCase(
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
    ) -> LbApiResult<Map<String, LbRecordingMetadata>>
) {

    suspend fun execute(
        username: String,
        token: String?,
        library: List<Song>,
        count: Int = DEFAULT_COUNT,
        offset: Int = 0,
        artistType: String = ARTIST_TYPE_TOP
    ): LbApiResult<MatchedCfRecommendations> {
        if (username.isBlank()) {
            return LbApiResult.Failure("Usuario vacío")
        }

        val payloadResult = fetchCf(username, token, count, offset, artistType)
        val payload = when (payloadResult) {
            is LbApiResult.Success -> payloadResult.data
            is LbApiResult.Failure -> return payloadResult
        }

        if (payload.recordings.isEmpty()) {
            return LbApiResult.Success(
                MatchedCfRecommendations(payload = payload, matches = emptyList())
            )
        }

        val mbids = payload.recordings.map { it.recordingMbid }
        val metaByMbid = when (val metaResult = fetchRecordingMetadata(mbids, token)) {
            is LbApiResult.Success -> metaResult.data
            is LbApiResult.Failure -> emptyMap()
        }

        return LbApiResult.Success(matchFromMetadata(payload, metaByMbid, library))
    }

    /** Maps already-fetched CF recordings + metadata to matched playables (no network). */
    fun matchFromMetadata(
        payload: CfRecommendationsPayload,
        metaByMbid: Map<String, LbRecordingMetadata>,
        library: List<Song>
    ): MatchedCfRecommendations {
        val libraryIndex = TrackMatchKeys.buildLibraryIndex(library)
        val matches = ArrayList<MatchedRemoteTrack>(payload.recordings.size)
        for (rec in payload.recordings) {
            val meta = metaByMbid[rec.recordingMbid] ?: continue
            val title = meta.title.takeIf { it.isNotBlank() } ?: continue
            val artist = meta.artist.takeIf { it.isNotBlank() } ?: continue
            val key = TrackMatchKeys.matchKey(artist, title)
            matches.add(
                MatchedRemoteTrack(
                    identity = meta.identity,
                    recordingMbid = rec.recordingMbid,
                    score = rec.score,
                    localSong = if (key.isNotEmpty()) libraryIndex[key] else null
                )
            )
        }
        return MatchedCfRecommendations(payload = payload, matches = matches)
    }

    companion object {
        const val DEFAULT_COUNT = 50
        const val ARTIST_TYPE_TOP = "top"
        const val ARTIST_TYPE_SIMILAR = "similar"
    }
}
