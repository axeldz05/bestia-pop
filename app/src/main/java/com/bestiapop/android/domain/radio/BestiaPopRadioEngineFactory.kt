package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher

/** One process-wide radio graph shared by playback continuity and UI-only playlist previews. */
fun createBestiaPopRadioEngine(): RadioEngine = RadioEngine(
    localRadio = LocalMetadataRadio(),
    listenBrainzRadio = ListenBrainzRadio(
        lookupMetadata = { artist, recording, token ->
            ListenBrainzClient.lookupRecordingMetadata(artist, recording, token)
        },
        fetchLbRadio = { artistMbid, token, mode ->
            ListenBrainzClient.fetchLbRadioArtist(artistMbid, token, mode = mode)
        },
        fetchRecordingMetadata = { mbids, token ->
            ListenBrainzClient.fetchRecordingMetadata(mbids, token)
        }
    ),
    cfRecommendationsRadio = CfRecommendationsRadio(
        fetchCf = { username, token, count, offset, artistType ->
            ListenBrainzClient.fetchCfRecordingRecommendations(
                username = username,
                token = token,
                count = count,
                offset = offset,
                artistType = artistType
            )
        },
        fetchRecordingMetadata = { mbids, token ->
            ListenBrainzClient.fetchRecordingMetadata(mbids, token)
        }
    ),
    similarProviders = listOf(
        DeezerSimilarRadio(
            resolveArtistId = MetadataFetcher::resolveDeezerArtistId,
            fetchArtistRadio = MetadataFetcher::fetchDeezerArtistRadio,
            fetchRelatedArtistIds = MetadataFetcher::fetchDeezerRelatedArtistIds,
            fetchArtistTop = MetadataFetcher::fetchDeezerArtistTop,
            fetchItunesArtistSongs = MetadataFetcher::fetchItunesArtistSongs
        )
    )
)
