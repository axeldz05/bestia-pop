package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.network.MetadataFetcher

/** One process-wide radio graph shared by playback continuity and UI-only playlist previews. */
fun createBestiaPopRadioEngine(): RadioEngine = RadioEngine(
    localRadio = LocalMetadataRadio(),
    listenBrainzRadio = ListenBrainzRadio(),
    cfRecommendationsRadio = CfRecommendationsRadio(),
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
