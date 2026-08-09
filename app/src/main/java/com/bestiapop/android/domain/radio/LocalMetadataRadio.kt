package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import kotlin.math.abs
import kotlin.random.Random
import com.bestiapop.android.domain.util.TrackMatchKeys

/**
 * Suggests similar library tracks from metadata (artist / genre / year / album / co-playlist).
 */
class LocalMetadataRadio(
    private val random: Random = Random.Default,
    private val maxPerAlbum: Int = DEFAULT_MAX_PER_ALBUM
) {

    fun suggest(
        seed: PlayableItem,
        library: List<Song>,
        excludeKeys: Set<String>,
        limit: Int,
        coPlaylistSongIds: Set<Long> = emptySet()
    ): List<PlayableItem.Local> {
        if (limit <= 0 || library.isEmpty()) return emptyList()

        val seedArtist = TrackMatchKeys.normalize(seed.artist)
        val seedKey = TrackMatchKeys.matchKey(seed.artist, seed.title)
        val seedGenre = meaningfulGenre(seedLocalGenre(seed))
        val seedYear = seedLocalYear(seed)
        val seedAlbum = TrackMatchKeys.normalize(seedLocalAlbum(seed))

        val scored = ArrayList<ScoredSong>(library.size)
        for (song in library) {
            val key = TrackMatchKeys.matchKey(song.artist, song.title)
            if (key.isEmpty()) continue
            if (key == seedKey) continue
            if (key in excludeKeys) continue
            if (TrackMatchKeys.normalize(song.uriString) in excludeKeys) continue
            if (song.uriString in excludeKeys) continue

            var score = 0
            val artistNorm = TrackMatchKeys.normalize(song.artist)
            if (seedArtist.isNotEmpty() && artistNorm == seedArtist) {
                score += SCORE_SAME_ARTIST
            }
            val genre = meaningfulGenre(song.genre)
            if (seedGenre != null && genre != null && genre == seedGenre) {
                score += SCORE_SAME_GENRE
            }
            if (seedYear > 0 && song.year > 0 && abs(song.year - seedYear) <= YEAR_WINDOW) {
                score += SCORE_YEAR_NEAR
            }
            val albumNorm = TrackMatchKeys.normalize(song.album)
            if (seedAlbum.isNotEmpty() && albumNorm == seedAlbum) {
                score += SCORE_SAME_ALBUM
            }
            if (coPlaylistSongIds.isNotEmpty() && song.id in coPlaylistSongIds) {
                score += SCORE_CO_PLAYLIST
            }

            if (score > 0) {
                scored.add(ScoredSong(song, score))
            }
        }

        if (scored.isEmpty()) {
            // Fallback: random library sample excluding seed/cooldown
            return library
                .asSequence()
                .filter {
                    val key = TrackMatchKeys.matchKey(it.artist, it.title)
                    key.isNotEmpty() &&
                        key != seedKey &&
                        key !in excludeKeys &&
                        it.uriString !in excludeKeys
                }
                .shuffled(random)
                .take(limit)
                .map { it.toPlayable() }
                .toList()
        }

        // Shuffle within equal-score buckets, then take with album cap
        val byScore = scored.groupBy { it.score }.toSortedMap(compareByDescending { it })
        val picked = ArrayList<Song>(limit)
        val albumCounts = HashMap<String, Int>()

        for ((_, bucket) in byScore) {
            val shuffled = bucket.shuffled(random)
            for (entry in shuffled) {
                if (picked.size >= limit) break
                val albumKey = TrackMatchKeys.normalize(entry.song.album)
                    .ifEmpty { entry.song.uriString }
                val count = albumCounts[albumKey] ?: 0
                if (count >= maxPerAlbum) continue
                albumCounts[albumKey] = count + 1
                picked.add(entry.song)
            }
            if (picked.size >= limit) break
        }

        return picked.map { it.toPlayable() }
    }

    private fun seedLocalGenre(seed: PlayableItem): String =
        (seed as? PlayableItem.Local)?.song?.genre.orEmpty()

    private fun seedLocalYear(seed: PlayableItem): Int =
        (seed as? PlayableItem.Local)?.song?.year ?: 0

    private fun seedLocalAlbum(seed: PlayableItem): String =
        when (seed) {
            is PlayableItem.Local -> seed.song.album
            is PlayableItem.Remote -> seed.album.orEmpty()
        }

    private fun meaningfulGenre(genre: String?): String? {
        val norm = TrackMatchKeys.normalize(genre.orEmpty())
        if (norm.isEmpty()) return null
        if (norm in GENERIC_GENRES) return null
        return norm
    }

    private data class ScoredSong(val song: Song, val score: Int)

    companion object {
        /** Prefer same artist over same album (diversity across albums). */
        const val SCORE_SAME_ARTIST = 120
        const val SCORE_CO_PLAYLIST = 55
        const val SCORE_SAME_GENRE = 40
        const val SCORE_YEAR_NEAR = 25
        const val SCORE_SAME_ALBUM = 5
        const val YEAR_WINDOW = 5
        const val DEFAULT_MAX_PER_ALBUM = 2

        private val GENERIC_GENRES = setOf(
            "unknown genre",
            "music",
            "unknown",
            "various",
            "various artists"
        )
    }
}
