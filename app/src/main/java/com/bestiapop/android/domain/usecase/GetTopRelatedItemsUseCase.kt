package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbUserStatArtist
import com.bestiapop.android.data.listenbrainz.LbUserStatRecording
import com.bestiapop.android.data.listenbrainz.LbUserStatRelease
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.domain.util.CollectionUtils
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.matchKey
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class RelatedArtistItem(
    val name: String,
    val playCount: Long = 0,
    val source: String, // "Local", "ListenBrainz", "Local + ListenBrainz"
    val artworkUri: String? = null
)

data class RelatedAlbumItem(
    val title: String,
    val artist: String,
    val playCount: Long = 0,
    val source: String, // "Local", "ListenBrainz", "Local + ListenBrainz"
    val artworkUri: String? = null
)

data class RelatedTrackItem(
    val identity: TrackIdentity,
    val playCount: Long = 0,
    val source: String, // "Local", "ListenBrainz", "Local + ListenBrainz"
    val localSong: Song? = null
) : TrackMeta by identity {
    constructor(
        title: String,
        artist: String,
        album: String = "",
        playCount: Long = 0,
        source: String,
        artworkUri: String? = null,
        localSong: Song? = null
    ) : this(
        identity = localSong?.toIdentity() ?: TrackIdentity(
            title = title,
            artist = artist,
            album = album,
            artworkUri = artworkUri
        ),
        playCount = playCount,
        source = source,
        localSong = localSong
    )
}

data class TopRelatedFeed(
    val topArtists: List<RelatedArtistItem> = emptyList(),
    val topAlbums: List<RelatedAlbumItem> = emptyList(),
    val topTracks: List<RelatedTrackItem> = emptyList(),
    val isLoading: Boolean = false
)

class GetTopRelatedItemsUseCase {

    suspend fun execute(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        username: String?,
        token: String?
    ): TopRelatedFeed = withContext(Dispatchers.IO) {
        coroutineScope {
            val lbUser = username?.takeIf { it.isNotBlank() }
            val lbToken = token?.takeIf { it.isNotBlank() }

            // 1. Single deferred fetch for recent listens as fallback for all stats
            val recentListensDeferred = async {
                if (lbUser == null) return@async emptyList<ListenPayload>()
                try {
                    val listensRes = ListenBrainzClient.fetchUserRecentListens(lbUser, count = 30, token = lbToken)
                    (listensRes as? LbApiResult.Success)?.data.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // Fetch ListenBrainz stats async with shared fallback helper
            val lbArtistsDeferred = async {
                fetchLbStatWithFallback(
                    lbUser = lbUser,
                    fetchRemote = { ListenBrainzClient.fetchUserTopArtists(lbUser!!, count = 25, token = lbToken) },
                    recentListensDeferred = recentListensDeferred,
                    extractFallbackKey = { it.artistName.takeIf { a -> a.isNotBlank() } },
                    createFallbackItem = { artist, count -> LbUserStatArtist(artistName = artist, listenCount = count) }
                )
            }

            val lbReleasesDeferred = async {
                fetchLbStatWithFallback(
                    lbUser = lbUser,
                    fetchRemote = { ListenBrainzClient.fetchUserTopReleases(lbUser!!, count = 25, token = lbToken) },
                    recentListensDeferred = recentListensDeferred,
                    extractFallbackKey = { l -> l.releaseName?.takeIf { it.isNotBlank() }?.let { it to l.artistName } },
                    createFallbackItem = { key, count -> LbUserStatRelease(releaseName = key.first, artistName = key.second, listenCount = count) }
                )
            }

            val lbRecordingsDeferred = async {
                fetchLbStatWithFallback(
                    lbUser = lbUser,
                    fetchRemote = { ListenBrainzClient.fetchUserTopRecordings(lbUser!!, count = 25, token = lbToken) },
                    recentListensDeferred = recentListensDeferred,
                    extractFallbackKey = { l -> (l.trackName to l.artistName).takeIf { l.trackName.isNotBlank() } },
                    createFallbackItem = { key, count -> LbUserStatRecording(trackName = key.first, artistName = key.second, listenCount = count) }
                )
            }

            val lbArtists = lbArtistsDeferred.await()
            val lbReleases = lbReleasesDeferred.await()
            val lbRecordings = lbRecordingsDeferred.await()

            // 2. Local stats processing
            val localArtists = CollectionUtils.scoreLocalArtists(
                librarySongs = librarySongs,
                playStats = playStats,
                playedWeight = 5L
            )
            val localAlbums = CollectionUtils.scoreLocalAlbums(
                librarySongs = librarySongs,
                playStats = playStats,
                playedWeight = 5L
            )
            val localTracks = CollectionUtils.scoreLocalTracks(
                librarySongs = librarySongs,
                playStats = playStats,
                playedWeight = 10L
            )

            // 3. Merges using semantic compression helper
            val finalArtists = mergeLocalAndRemoteStats(
                localMap = localArtists,
                remoteList = lbArtists,
                remoteKey = { TrackMatchKeys.normalize(it.artistName) },
                remoteCount = { it.listenCount },
                localScore = { it.score },
                mergeExisting = { acc, totalScore, source ->
                    RelatedArtistItem(
                        name = acc.displayName,
                        playCount = totalScore,
                        source = source,
                        artworkUri = acc.artworkUri
                    )
                },
                createRemoteOnly = { lb, score, source ->
                    RelatedArtistItem(
                        name = lb.artistName,
                        playCount = score,
                        source = source,
                        artworkUri = null
                    )
                },
                itemScore = { it.playCount },
                limit = 20
            )

            val finalAlbums = mergeLocalAndRemoteStats(
                localMap = localAlbums,
                remoteList = lbReleases,
                remoteKey = { TrackMatchKeys.matchKey(it.artistName, it.releaseName) },
                remoteCount = { it.listenCount },
                localScore = { it.score },
                mergeExisting = { acc, totalScore, source ->
                    RelatedAlbumItem(
                        title = acc.title,
                        artist = acc.artist,
                        playCount = totalScore,
                        source = source,
                        artworkUri = acc.artworkUri
                    )
                },
                createRemoteOnly = { lb, score, source ->
                    RelatedAlbumItem(
                        title = lb.releaseName,
                        artist = lb.artistName,
                        playCount = score,
                        source = source,
                        artworkUri = null
                    )
                },
                itemScore = { it.playCount },
                limit = 20
            )

            val finalTracks = mergeLocalAndRemoteStats(
                localMap = localTracks,
                remoteList = lbRecordings,
                remoteKey = { TrackMatchKeys.matchKey(it.artistName, it.trackName) },
                remoteCount = { it.listenCount },
                localScore = { it.score },
                mergeExisting = { acc, totalScore, source ->
                    RelatedTrackItem(
                        identity = acc.song.toIdentity(),
                        playCount = totalScore,
                        source = source,
                        localSong = acc.song
                    )
                },
                createRemoteOnly = { lb, score, source ->
                    RelatedTrackItem(
                        identity = TrackIdentity(
                            title = lb.trackName,
                            artist = lb.artistName,
                            album = lb.releaseName.orEmpty()
                        ),
                        playCount = score,
                        source = source,
                        localSong = null
                    )
                },
                itemScore = { it.playCount },
                limit = 25
            )

            TopRelatedFeed(
                topArtists = finalArtists,
                topAlbums = finalAlbums,
                topTracks = finalTracks,
                isLoading = false
            )
        }
    }
}

private suspend fun <T, K> fetchLbStatWithFallback(
    lbUser: String?,
    fetchRemote: suspend () -> LbApiResult<List<T>>,
    recentListensDeferred: Deferred<List<ListenPayload>>,
    extractFallbackKey: (ListenPayload) -> K?,
    createFallbackItem: (K, Long) -> T
): List<T> {
    if (lbUser == null) return emptyList()
    return try {
        val res = fetchRemote()
        if (res is LbApiResult.Success && res.data.isNotEmpty()) {
            res.data
        } else {
            val listens = recentListensDeferred.await()
            val counts = HashMap<K, Long>()
            for (l in listens) {
                val key = extractFallbackKey(l) ?: continue
                counts[key] = (counts[key] ?: 0L) + 1L
            }
            counts.entries.map { createFallbackItem(it.key, it.value) }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Semantic compression helper: unites local accumulation with remote stats
 * while eliminating repetitive iteration, scoring and sorting loops.
 */
private inline fun <K, L, R, T> mergeLocalAndRemoteStats(
    localMap: Map<K, L>,
    remoteList: List<R>,
    crossinline remoteKey: (R) -> K,
    crossinline remoteCount: (R) -> Long,
    crossinline localScore: (L) -> Long,
    crossinline mergeExisting: (local: L, totalScore: Long, source: String) -> T,
    crossinline createRemoteOnly: (remote: R, score: Long, source: String) -> T,
    crossinline itemScore: (T) -> Long,
    limit: Int
): List<T> {
    val remoteByKey = HashMap<K, R>(remoteList.size)
    for (remote in remoteList) {
        val key = remoteKey(remote)
        if (!remoteByKey.containsKey(key)) {
            remoteByKey[key] = remote
        }
    }

    val merged = LinkedHashMap<K, T>(localMap.size + remoteList.size)
    val processedKeys = HashSet<K>(localMap.size + remoteList.size)

    for ((key, local) in localMap) {
        val matchingRemote = remoteByKey[key]
        val baseScore = localScore(local)
        val (source, totalScore) = if (matchingRemote != null) {
            "Local + ListenBrainz" to (baseScore + remoteCount(matchingRemote) * 2)
        } else {
            "Local" to baseScore
        }
        merged[key] = mergeExisting(local, totalScore, source)
        processedKeys.add(key)
    }

    for (remote in remoteList) {
        val key = remoteKey(remote)
        if (processedKeys.add(key)) {
            val count = remoteCount(remote)
            merged[key] = createRemoteOnly(remote, count, "ListenBrainz")
        }
    }

    return merged.values.sortedByDescending { itemScore(it) }.take(limit)
}
