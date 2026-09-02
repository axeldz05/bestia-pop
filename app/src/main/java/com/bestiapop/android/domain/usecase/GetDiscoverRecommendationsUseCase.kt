package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toCatalogTrack
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.data.network.DeezerArtistHit
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
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
        username: String?
    ): DiscoverFeed = withContext(Dispatchers.IO) {
        coroutineScope {
            val chartTracksDeferred = async {
                try {
                    MetadataFetcher.fetchChartTracks(limit = 20)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // Find top played or recent artists
            val topArtists = getTopArtists(librarySongs, playStats)

            val lbToken = userToken?.takeIf { it.isNotBlank() }
            val lbUser = username?.takeIf { it.isNotBlank() }

            var recTracks: List<OnlineCatalogTrack> = emptyList()
            var recAlbums: List<CatalogAlbum> = emptyList()
            var source = "Deezer"

            if (lbToken != null && lbUser != null) {
                // Try ListenBrainz CF first
                try {
                    val cfResult = ListenBrainzClient.fetchCfRecordingRecommendations(
                        username = lbUser,
                        token = lbToken,
                        count = 20
                    )
                    if (cfResult is LbApiResult.Success && cfResult.data.recordings.isNotEmpty()) {
                        val mbids = cfResult.data.recordings.map { it.recordingMbid }
                        val metaResult = ListenBrainzClient.fetchRecordingMetadata(mbids, lbToken)
                        if (metaResult is LbApiResult.Success) {
                            recTracks = cfResult.data.recordings.mapNotNull { rec ->
                                val meta = metaResult.data[rec.recordingMbid] ?: return@mapNotNull null
                                meta.identity.toListenBrainzCatalogTrack(rec.recordingMbid)
                            }
                            if (recTracks.isNotEmpty()) {
                                source = "ListenBrainz"
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Fallback to Deezer
                }
            }

            // If ListenBrainz did not return tracks, use Deezer recommendations based on library or charts
            if (recTracks.isEmpty()) {
                val deezerTracks = ArrayList<OnlineCatalogTrack>()
                if (topArtists.isNotEmpty()) {
                    val artistTrackJobs = topArtists.take(3).map { artist ->
                        async {
                            val tracks = mutableListOf<OnlineCatalogTrack>()
                            try {
                                val artistId = deezerSemaphore.withPermit {
                                    MetadataFetcher.resolveDeezerArtistId(artist)
                                }
                                if (artistId != null) {
                                    val relatedIds = deezerSemaphore.withPermit {
                                        MetadataFetcher.fetchDeezerRelatedArtistIds(artistId, limit = 3)
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
                                    tracks.addAll(topJobs.awaitAll().flatten())
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                            tracks
                        }
                    }
                    deezerTracks.addAll(artistTrackJobs.awaitAll().flatten())
                }

                // Fallback: If library was empty or artist matching produced no results, fallback to Deezer charts
                if (deezerTracks.isEmpty()) {
                    val chartTracks = chartTracksDeferred.await()
                    deezerTracks.addAll(chartTracks)
                }

                recTracks = deezerTracks.distinctCatalogTracks(25)
                source = "Deezer"
            }

            // Recommended Albums from top artists with Deezer chart albums fallback
            val albums = ArrayList<CatalogAlbum>()
            if (topArtists.isNotEmpty()) {
                val albumJobs = topArtists.take(4).map { artist ->
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

            // Fallback for recommended albums when library is empty or no albums found
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
            recAlbums = albums.distinctCatalogAlbums(16)

            val chartTracks = chartTracksDeferred.await()

            // If recommendations are still sparse, use charts as fallback
            val finalRecTracks = if (recTracks.isEmpty()) chartTracks.take(20) else recTracks

            DiscoverFeed(
                recommendedTracks = finalRecTracks,
                recommendedAlbums = recAlbums,
                chartTracks = chartTracks,
                recommendationSource = source
            )
        }
    }

    private fun getTopArtists(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>
    ): List<String> {
        if (librarySongs.isEmpty()) return emptyList()

        // Group by artist and score by play count or recent playback
        val scoreByArtist = HashMap<String, Long>()
        for (song in librarySongs) {
            val artist = song.artist.trim()
            if (artist.isBlank() || IdentifyRanking.isPlaceholderArtist(artist)) continue
            val lastPlayed = playStats[song.id] ?: song.lastPlayedAt
            val currentScore = scoreByArtist[artist] ?: 0L
            scoreByArtist[artist] = currentScore + (if (lastPlayed > 0) 10L else 1L)
        }

        return scoreByArtist.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }
}

private inline fun <T> List<T>.distinctByTrackKey(limit: Int, crossinline keyOf: (T) -> String): List<T> =
    distinctBy { keyOf(it).ifEmpty { it.hashCode().toString() } }.take(limit)

private fun List<OnlineCatalogTrack>.distinctCatalogTracks(limit: Int): List<OnlineCatalogTrack> =
    distinctByTrackKey(limit) { it.matchKey() }

private fun List<CatalogAlbum>.distinctCatalogAlbums(limit: Int): List<CatalogAlbum> =
    distinctByTrackKey(limit) { TrackMatchKeys.matchKey(it.artist, it.title) }

