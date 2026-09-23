package com.bestiapop.android.data.model

import com.bestiapop.android.data.network.DeezerArtistHit

/**
 * Structured discography for an artist partitioned into primary albums, singles & EPs,
 * external appearances / collaborations ("Apareció en"), and popular tracks.
 */
data class ArtistDiscography(
    val artistHit: DeezerArtistHit? = null,
    val albums: List<CatalogAlbum> = emptyList(),
    val singlesAndEps: List<CatalogAlbum> = emptyList(),
    val appearedOn: List<CatalogAlbum> = emptyList(),
    val topTracks: List<OnlineCatalogTrack> = emptyList(),
)
