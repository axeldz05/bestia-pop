package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bestiapop.android.domain.util.TrackMatchKeys

/**
 * Deezer artist radio + related tops as Radio NEW/BOTH fill (no token).
 * Optionally tops up with same-artist iTunes search when still under [limit].
 */
class DeezerSimilarRadio(
    private val resolveArtistId: suspend (artist: String) -> Long?,
    private val fetchArtistRadio: suspend (artistId: Long) -> List<TrackIdentity>,
    private val fetchRelatedArtistIds: suspend (artistId: Long, limit: Int) -> List<Long>,
    private val fetchArtistTop: suspend (artistId: Long, limit: Int) -> List<TrackIdentity>,
    private val fetchItunesArtistSongs: suspend (artist: String, limit: Int) -> List<TrackIdentity> =
        { _, _ -> emptyList() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val relatedArtistLimit: Int = RELATED_ARTIST_LIMIT,
    private val relatedTopLimit: Int = RELATED_TOP_LIMIT,
    private val itunesFillLimit: Int = ITUNES_FILL_LIMIT
) : SimilarTracksProvider {

    override val id: String = "deezer"

    private val mutex = Mutex()
    private var cachedArtistKey: String? = null
    private var cachedAtMs: Long = 0L
    private var cachedHints: List<TrackIdentity> = emptyList()

    override suspend fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int
    ): List<PlayableItem> {
        if (limit <= 0 || seed.artist.isBlank() || seed.title.isBlank()) return emptyList()

        val pool = resolvePool(seed.artist)
        if (pool.isEmpty()) return emptyList()

        val libraryIndex = TrackMatchKeys.buildLibraryIndex(library)
        val remotes = ArrayList<PlayableItem.Remote>(limit)
        val localSeen = HashSet<String>()
        fun tryAdd(hint: TrackIdentity) {
            if (remotes.size >= limit) return
            val key = TrackMatchKeys.matchKey(hint.artist, hint.title)
            if (key.isEmpty() || key in localSeen) return
            // NEW/BOTH remote pool: skip tracks already in the library
            if (libraryIndex.containsKey(key)) return
            localSeen.add(key)
            remotes.add(PlayableItem.remoteFrom(identity = hint))
        }

        for (hint in pool) {
            if (remotes.size >= limit) break
            tryAdd(hint)
        }

        if (remotes.size < limit) {
            val itunes = runCatching {
                fetchItunesArtistSongs(seed.artist, itunesFillLimit)
            }.getOrDefault(emptyList())
            val seedTitleNorm = TrackMatchKeys.normalize(seed.title)
            for (hint in itunes) {
                if (remotes.size >= limit) break
                val titleNorm = TrackMatchKeys.normalize(hint.title)
                if (titleNorm.isNotEmpty() && titleNorm == seedTitleNorm) continue
                tryAdd(hint)
            }
        }

        return remotes
    }

    private suspend fun resolvePool(artist: String): List<TrackIdentity> {
        val artistKey = TrackMatchKeys.normalize(artist)
        if (artistKey.isEmpty()) return emptyList()

        mutex.withLock {
            val fresh = cachedArtistKey == artistKey &&
                clockMs() - cachedAtMs < cacheTtlMs &&
                cachedHints.isNotEmpty()
            if (fresh) return cachedHints
        }

        val artistId = runCatching { resolveArtistId(artist) }.getOrNull() ?: return emptyList()
        val hints = ArrayList<TrackIdentity>()
        val seenKeys = HashSet<String>()

        fun append(list: List<TrackIdentity>) {
            for (hint in list) {
                val key = TrackMatchKeys.matchKey(hint.artist, hint.title)
                if (key.isEmpty() || key in seenKeys) continue
                seenKeys.add(key)
                hints.add(hint)
            }
        }

        append(runCatching { fetchArtistRadio(artistId) }.getOrDefault(emptyList()))

        val relatedIds = runCatching {
            fetchRelatedArtistIds(artistId, relatedArtistLimit)
        }.getOrDefault(emptyList())
        for (relatedId in relatedIds) {
            append(
                runCatching { fetchArtistTop(relatedId, relatedTopLimit) }
                    .getOrDefault(emptyList())
            )
        }

        mutex.withLock {
            cachedArtistKey = artistKey
            cachedAtMs = clockMs()
            cachedHints = hints
        }
        return hints
    }

    companion object {
        const val CACHE_TTL_MS = 20L * 60L * 1000L
        const val RELATED_ARTIST_LIMIT = 4
        const val RELATED_TOP_LIMIT = 5
        const val ITUNES_FILL_LIMIT = 25
    }
}
