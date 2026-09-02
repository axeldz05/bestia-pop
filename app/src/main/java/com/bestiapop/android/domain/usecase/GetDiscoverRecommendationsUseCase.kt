package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toCatalogTrack
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.domain.util.CollectionUtils
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.distinctCatalogAlbums
import com.bestiapop.android.domain.util.distinctCatalogTracks
import com.bestiapop.android.domain.util.matchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DiscoverFeed(
    val recommendedTracks: List<OnlineCatalogTrack> = emptyList(),
    val recommendedAlbums: List<CatalogAlbum> = emptyList(),
    val chartTracks: List<OnlineCatalogTrack> = emptyList(),
    val recommendationSource: String = "Deezer"
)

class GetDiscoverRecommendationsUseCase {

    private val deezerSemaphore = Semaphore(4)

    suspend fun execute(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        userToken: String?,
        username: String?,
        sourcePreference: DiscoverSourcePreference = DiscoverSourcePreference.BOTH,
        preloadedLbTracks: List<OnlineCatalogTrack> = emptyList()
    ): DiscoverFeed = withContext(Dispatchers.IO) {
        coroutineScope {
            val chartTracksDeferred = async {
                try {
                    MetadataFetcher.fetchChartTracks(limit = 20)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // Find top played or recent local artists using shared CollectionUtils
            val localTopArtists = CollectionUtils.calculateTopLocalArtists(librarySongs, playStats)

            val lbToken = userToken?.takeIf { it.isNotBlank() }
            val lbUser = username?.takeIf { it.isNotBlank() }
            val hasLb = lbToken != null && lbUser != null

            // 1. Fetch ListenBrainz tracks & artists if required by source preference
            var lbTracks = preloadedLbTracks
            val lbArtists = mutableListOf<String>()

            if (hasLb && sourcePreference != DiscoverSourcePreference.DEEZER) {
                // CF Recording recommendations (reuse preloaded if available)
                if (lbTracks.isEmpty()) {
                    try {
                        val cfResult = ListenBrainzClient.fetchCfRecordingRecommendations(
                            username = lbUser,
                            token = lbToken,
                            count = 25
                        )
                        if (cfResult is LbApiResult.Success && cfResult.data.recordings.isNotEmpty()) {
                            val mbids = cfResult.data.recordings.map { it.recordingMbid }
                            val metaResult = ListenBrainzClient.fetchRecordingMetadata(mbids, lbToken)
                            if (metaResult is LbApiResult.Success) {
                                lbTracks = cfResult.data.recordings.mapNotNull { rec ->
                                    val meta = metaResult.data[rec.recordingMbid] ?: return@mapNotNull null
                                    meta.identity.toListenBrainzCatalogTrack(rec.recordingMbid)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                // Top artists from ListenBrainz for albums / related
                try {
                    val topArtists = ListenBrainzClient.fetchUserTopArtistsWithRecentFallback(lbUser, count = 10, token = lbToken)
                    lbArtists.addAll(topArtists)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }

                // Also incorporate artists from CF recommendations
                for (track in lbTracks) {
                    val artist = track.artist.trim()
                    if (artist.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(artist)) {
                        lbArtists.add(artist)
                    }
                }
            }

            val distinctLbArtists = lbArtists.distinct().filter {
                it.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(it)
            }

            // 2. Fetch Deezer tracks if needed
            val shouldFetchDeezerTracks = sourcePreference == DiscoverSourcePreference.DEEZER ||
                sourcePreference == DiscoverSourcePreference.BOTH ||
                lbTracks.isEmpty()

            var deezerTracks = emptyList<OnlineCatalogTrack>()
            if (shouldFetchDeezerTracks) {
                val candidateArtists = resolveSeedArtists(sourcePreference, distinctLbArtists, localTopArtists, limit = 4)

                val collectedDeezer = ArrayList<OnlineCatalogTrack>()
                if (candidateArtists.isNotEmpty()) {
                    val artistTrackJobs = candidateArtists.take(2).map { artist ->
                        async {
                            val tracks = mutableListOf<OnlineCatalogTrack>()
                            try {
                                val artistId = deezerSemaphore.withPermit {
                                    MetadataFetcher.resolveDeezerArtistId(artist)
                                }
                                if (artistId != null) {
                                    // Direct top tracks for seed artist
                                    val directTopDeferred = async {
                                        try {
                                            deezerSemaphore.withPermit {
                                                MetadataFetcher.fetchDeezerArtistTop(artistId, limit = 5).map { identity ->
                                                    identity.toCatalogTrack(provider = "Deezer")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            emptyList()
                                        }
                                    }
                                    // Related artists top tracks (limit to 2 for diversity without request explosion)
                                    val relatedIds = deezerSemaphore.withPermit {
                                        MetadataFetcher.fetchDeezerRelatedArtistIds(artistId, limit = 2)
                                    }
                                    val topJobs = relatedIds.map { relId ->
                                        async {
                                            try {
                                                deezerSemaphore.withPermit {
                                                    MetadataFetcher.fetchDeezerArtistTop(relId, limit = 4).map { identity ->
                                                        identity.toCatalogTrack(provider = "Deezer")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                emptyList()
                                            }
                                        }
                                    }
                                    tracks.addAll(directTopDeferred.await())
                                    tracks.addAll(topJobs.awaitAll().flatten())
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                            tracks
                        }
                    }
                    collectedDeezer.addAll(artistTrackJobs.awaitAll().flatten())
                }

                if (collectedDeezer.isEmpty()) {
                    val chartTracks = chartTracksDeferred.await()
                    collectedDeezer.addAll(chartTracks)
                }
                deezerTracks = collectedDeezer.distinctCatalogTracks(25)
            }

            // 3. Resolve Recommended Tracks and Source Label
            val chartTracks = chartTracksDeferred.await()
            val fallbackTracks = if (deezerTracks.isNotEmpty()) deezerTracks else chartTracks.take(20)
            val recTracks: List<OnlineCatalogTrack>
            val sourceLabel: String

            when (sourcePreference) {
                DiscoverSourcePreference.LISTENBRAINZ -> {
                    if (lbTracks.isNotEmpty()) {
                        recTracks = lbTracks.distinctCatalogTracks(25)
                        sourceLabel = "ListenBrainz"
                    } else {
                        recTracks = fallbackTracks
                        sourceLabel = "Deezer (Fallback)"
                    }
                }
                DiscoverSourcePreference.DEEZER -> {
                    recTracks = fallbackTracks
                    sourceLabel = "Deezer"
                }
                DiscoverSourcePreference.BOTH -> {
                    if (lbTracks.isNotEmpty() && deezerTracks.isNotEmpty()) {
                        recTracks = CollectionUtils.interleaveEquitable(lbTracks, deezerTracks, limit = 25).distinctCatalogTracks(25)
                        sourceLabel = "Ambos (Deezer + ListenBrainz)"
                    } else if (lbTracks.isNotEmpty()) {
                        recTracks = lbTracks.distinctCatalogTracks(25)
                        sourceLabel = "ListenBrainz"
                    } else {
                        recTracks = fallbackTracks
                        sourceLabel = "Deezer"
                    }
                }
            }

            // 4. Resolve Recommended Albums
            val albumSeedArtists = resolveSeedArtists(sourcePreference, distinctLbArtists, localTopArtists, limit = 6)

            val albums = ArrayList<CatalogAlbum>()
            if (albumSeedArtists.isNotEmpty()) {
                val albumJobs = albumSeedArtists.take(3).map { artist ->
                    async {
                        try {
                            deezerSemaphore.withPermit {
                                MetadataFetcher.searchAlbums(artist).take(4)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            emptyList()
                        }
                    }
                }
                albums.addAll(albumJobs.awaitAll().flatten())
            }

            // Fallback for recommended albums when empty
            if (albums.isEmpty()) {
                try {
                    val chartAlbums = MetadataFetcher.fetchChartAlbums(limit = 16)
                    albums.addAll(chartAlbums)
                } catch (_: Exception) {
                }
                if (albums.isEmpty()) {
                    try {
                        albums.addAll(MetadataFetcher.searchAlbums("hits").take(16))
                    } catch (_: Exception) {
                    }
                }
            }

            val recAlbums = albums.distinctCatalogAlbums(16, artistOf = { it.artist }, titleOf = { it.title })

            DiscoverFeed(
                recommendedTracks = recTracks,
                recommendedAlbums = recAlbums,
                chartTracks = chartTracks,
                recommendationSource = sourceLabel
            )
        }
    }
}

private fun resolveSeedArtists(
    sourcePreference: DiscoverSourcePreference,
    lbArtists: List<String>,
    localArtists: List<String>,
    limit: Int
): List<String> = when (sourcePreference) {
    DiscoverSourcePreference.LISTENBRAINZ -> {
        if (lbArtists.isNotEmpty()) lbArtists.take(limit) else localArtists.take(limit)
    }
    DiscoverSourcePreference.DEEZER -> {
        localArtists.take(limit)
    }
    DiscoverSourcePreference.BOTH -> {
        if (lbArtists.isNotEmpty() && localArtists.isNotEmpty()) {
            CollectionUtils.interleaveEquitable(lbArtists, localArtists, limit = limit)
        } else if (lbArtists.isNotEmpty()) {
            lbArtists.take(limit)
        } else {
            localArtists.take(limit)
        }
    }
}

