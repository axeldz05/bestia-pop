package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toCatalogTrack
import com.bestiapop.android.data.model.toIdentity
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
            // 1. Chart tracks (parallel async)
            val chartTracksDeferred = async {
                try {
                    MetadataFetcher.fetchChartTracks(limit = 20)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // Find top played or recent local artists using shared CollectionUtils (instant local)
            val localTopArtists = CollectionUtils.calculateTopLocalArtists(librarySongs, playStats)

            val lbToken = userToken?.takeIf { it.isNotBlank() }
            val lbUser = username?.takeIf { it.isNotBlank() }
            val hasLb = lbToken != null && lbUser != null

            // 2. Fetch ListenBrainz tracks & artists in parallel
            val lbDeferred = async {
                if (!hasLb || sourcePreference == DiscoverSourcePreference.DEEZER) {
                    return@async Pair(preloadedLbTracks, emptyList<String>())
                }

                var tracks = preloadedLbTracks
                val artists = mutableListOf<String>()

                val cfDeferred = async {
                    if (tracks.isEmpty()) {
                        try {
                            val cfResult = ListenBrainzClient.fetchCfRecordingsWithMetadata(
                                username = lbUser!!,
                                token = lbToken,
                                count = 25
                            )
                            if (cfResult is LbApiResult.Success) {
                                return@async cfResult.data.mapNotNull { (rec, meta) ->
                                    meta?.identity?.toListenBrainzCatalogTrack(rec.recordingMbid)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }
                    emptyList()
                }

                val topArtistsDeferred = async {
                    try {
                        ListenBrainzClient.fetchUserTopArtistsWithRecentFallback(lbUser!!, count = 10, token = lbToken)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                }

                val cfTracks = cfDeferred.await()
                if (tracks.isEmpty()) {
                    tracks = cfTracks
                }
                artists.addAll(topArtistsDeferred.await())
                for (track in tracks) {
                    val artist = track.artist.trim()
                    if (artist.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(artist)) {
                        artists.add(artist)
                    }
                }
                Pair(tracks, artists.distinct().filter { it.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(it) })
            }

            // 3. Fetch Deezer tracks in parallel (using local seed artists first to avoid waiting for LB)
            val shouldFetchDeezerTracks = sourcePreference == DiscoverSourcePreference.DEEZER ||
                sourcePreference == DiscoverSourcePreference.BOTH

            val deezerTracksDeferred = async {
                if (!shouldFetchDeezerTracks && sourcePreference == DiscoverSourcePreference.LISTENBRAINZ) {
                    return@async emptyList<OnlineCatalogTrack>()
                }
                val candidateArtists = localTopArtists.take(2)
                if (candidateArtists.isEmpty()) return@async emptyList<OnlineCatalogTrack>()

                val collectedDeezer = ArrayList<OnlineCatalogTrack>()
                val artistTrackJobs = candidateArtists.map { artist ->
                    async {
                        val tracks = mutableListOf<OnlineCatalogTrack>()
                        try {
                            val artistId = deezerSemaphore.withPermit {
                                MetadataFetcher.resolveDeezerArtistId(artist)
                            }
                            if (artistId != null) {
                                val directTop = deezerSemaphore.withPermit {
                                    MetadataFetcher.fetchDeezerArtistTop(artistId, limit = 8).map { identity ->
                                        identity.toCatalogTrack(provider = "Deezer")
                                    }
                                }
                                tracks.addAll(directTop)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                        tracks
                    }
                }
                collectedDeezer.addAll(artistTrackJobs.awaitAll().flatten())
                collectedDeezer.distinctCatalogTracks(25)
            }

            // 4. Fetch Deezer albums in parallel
            val deezerAlbumsDeferred = async {
                val albumSeedArtists = localTopArtists.take(2)
                if (albumSeedArtists.isEmpty()) return@async emptyList<CatalogAlbum>()

                val albumJobs = albumSeedArtists.map { artist ->
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
                albumJobs.awaitAll().flatten().distinctCatalogAlbums(16)
            }

            // Await all parallel results
            val (lbTracks, _) = lbDeferred.await()
            var deezerTracks = deezerTracksDeferred.await()
            var recAlbums = deezerAlbumsDeferred.await()
            val chartTracks = chartTracksDeferred.await()

            if (deezerTracks.isEmpty() && (shouldFetchDeezerTracks || lbTracks.isEmpty())) {
                deezerTracks = chartTracks.take(20)
            }

            val recTracks: List<OnlineCatalogTrack>
            val sourceLabel: String

            when (sourcePreference) {
                DiscoverSourcePreference.LISTENBRAINZ -> {
                    if (lbTracks.isNotEmpty()) {
                        recTracks = lbTracks.distinctCatalogTracks(25)
                        sourceLabel = "ListenBrainz"
                    } else {
                        recTracks = if (deezerTracks.isNotEmpty()) deezerTracks else chartTracks.take(20)
                        sourceLabel = if (recTracks.isNotEmpty()) "Deezer (Fallback)" else "Sin conexión"
                    }
                }
                DiscoverSourcePreference.DEEZER -> {
                    recTracks = if (deezerTracks.isNotEmpty()) deezerTracks else chartTracks.take(20)
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
                        recTracks = if (deezerTracks.isNotEmpty()) deezerTracks else chartTracks.take(20)
                        sourceLabel = "Deezer"
                    }
                }
            }

            // Fallback for recommended albums when empty
            if (recAlbums.isEmpty()) {
                if (chartTracks.isNotEmpty()) {
                    try {
                        val chartAlbums = MetadataFetcher.fetchChartAlbums(limit = 12)
                        recAlbums = chartAlbums.distinctCatalogAlbums(16)
                    } catch (_: Exception) {
                    }
                }
            }

            // Local fallback when device is offline or remote APIs failed completely
            var finalRecTracks = recTracks
            var finalRecAlbums = recAlbums
            var finalSource = sourceLabel

            if (finalRecTracks.isEmpty() && librarySongs.isNotEmpty()) {
                finalRecTracks = CollectionUtils.recommendLocalTracks(librarySongs, playStats, limit = 16)
                    .map { it.toIdentity().toCatalogTrack(provider = "Local") }
                finalSource = "Biblioteca local"
            }

            if (finalRecAlbums.isEmpty() && librarySongs.isNotEmpty()) {
                finalRecAlbums = CollectionUtils.recommendLocalAlbums(librarySongs, playStats, limit = 12)
                    .map { album ->
                        CatalogAlbum(
                            id = album.key,
                            title = album.title,
                            artist = album.artist,
                            coverUrl = album.artworkUri,
                            trackCount = 0
                        )
                    }
            }

            DiscoverFeed(
                recommendedTracks = finalRecTracks,
                recommendedAlbums = finalRecAlbums,
                chartTracks = chartTracks,
                recommendationSource = finalSource
            )
        }
    }
}


