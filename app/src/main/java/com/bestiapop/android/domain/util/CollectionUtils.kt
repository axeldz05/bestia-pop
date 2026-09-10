package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song

/**
 * Shared collection utilities for alternating / interleaving items and ranking local library entities.
 */
object CollectionUtils {

    /**
     * Level 1: Scored artist model with normalized key, display name, accumulated score, and artwork URI.
     */
    data class ScoredArtist(
        val key: String,
        val displayName: String,
        val score: Long,
        val artworkUri: String? = null
    )

    /**
     * Interleaves two lists taking turns from each; drains remaining items when one side finishes.
     */
    fun <T> interleaveEquitable(
        first: List<T>,
        second: List<T>,
        limit: Int
    ): List<T> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<T>(minOf(limit, first.size + second.size))
        var i = 0
        var j = 0
        var takeFirst = true
        while (result.size < limit && (i < first.size || j < second.size)) {
            if (takeFirst) {
                if (i < first.size) {
                    result.add(first[i++])
                } else if (j < second.size) {
                    result.add(second[j++])
                }
            } else {
                if (j < second.size) {
                    result.add(second[j++])
                } else if (i < first.size) {
                    result.add(first[i++])
                }
            }
            takeFirst = !takeFirst
        }
        return result
    }

    /**
     * Level 1: Calculates play score weight for a song based on last played timestamp or play stats.
     */
    fun calculateSongPlayScore(
        song: Song,
        playStats: Map<Long, Long>,
        playedWeight: Long,
        unplayedWeight: Long = 1L
    ): Long {
        val lastPlayed = playStats[song.id] ?: song.lastPlayedAt
        return if (lastPlayed > 0) playedWeight else unplayedWeight
    }

    /**
     * Level 1: Scores distinct artists from library songs using normalized match keys,
     * aggregating play score and preserving artwork URI.
     */
    fun scoreLocalArtists(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        playedWeight: Long = 10L,
        unplayedWeight: Long = 1L
    ): Map<String, ScoredArtist> {
        if (librarySongs.isEmpty()) return emptyMap()

        class MutableArtistEntry(
            val key: String,
            val displayName: String,
            var score: Long = 0L,
            var artworkUri: String? = null
        )

        val entryMap = HashMap<String, MutableArtistEntry>()
        for (song in librarySongs) {
            val artist = song.artist.trim()
            if (artist.isBlank() || IdentifyRanking.isPlaceholderArtist(artist)) continue
            val artistKey = TrackMatchKeys.normalize(artist)
            if (artistKey.isEmpty()) continue

            val weight = calculateSongPlayScore(song, playStats, playedWeight, unplayedWeight)
            val entry = entryMap.getOrPut(artistKey) {
                MutableArtistEntry(key = artistKey, displayName = artist)
            }
            entry.score += weight
            if (entry.artworkUri == null && !song.artworkUri.isNullOrBlank()) {
                entry.artworkUri = song.artworkUri
            }
        }

        return entryMap.mapValues { (_, e) ->
            ScoredArtist(
                key = e.key,
                displayName = e.displayName,
                score = e.score,
                artworkUri = e.artworkUri
            )
        }
    }

    /**
     * Level 1: Scored album model with key, title, artist, accumulated score, and artwork URI.
     */
    data class ScoredAlbum(
        val key: String,
        val title: String,
        val artist: String,
        val score: Long,
        val artworkUri: String? = null
    )

    /**
     * Level 1: Scored track model with key, song reference, and accumulated score.
     */
    data class ScoredTrack(
        val key: String,
        val song: Song,
        val score: Long
    )

    /**
     * Level 2: Scores and ranks distinct artists from local library songs using play stats and recent plays.
     * Filters placeholder / empty artist names via [IdentifyRanking.isPlaceholderArtist].
     */
    fun calculateTopLocalArtists(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>
    ): List<String> = scoreLocalArtists(librarySongs, playStats)
        .values
        .sortedByDescending { it.score }
        .map { it.displayName }

    /**
     * Level 1: Scores distinct albums from library songs, resolving match keys and preserving artwork.
     */
    fun scoreLocalAlbums(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        playedWeight: Long = 5L,
        unplayedWeight: Long = 1L
    ): Map<String, ScoredAlbum> {
        if (librarySongs.isEmpty()) return emptyMap()

        class MutableAlbumEntry(
            val key: String,
            val title: String,
            val artist: String,
            var score: Long = 0L,
            var artworkUri: String? = null
        )

        val entryMap = HashMap<String, MutableAlbumEntry>()
        for (song in librarySongs) {
            val album = song.album.trim()
            if (album.isBlank() || IdentifyRanking.isGenericAlbum(album)) continue

            val artist = song.artist.trim()
            val isArtistValid = artist.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(artist)
            val artistKey = if (isArtistValid) TrackMatchKeys.normalize(artist) else ""

            val albumKey = if (artistKey.isNotEmpty()) {
                TrackMatchKeys.composeKey(artistKey, TrackMatchKeys.normalize(album))
            } else {
                TrackMatchKeys.matchKey(artist, album)
            }
            if (albumKey.isEmpty()) continue

            val weight = calculateSongPlayScore(song, playStats, playedWeight, unplayedWeight)
            val entry = entryMap.getOrPut(albumKey) {
                MutableAlbumEntry(key = albumKey, title = album, artist = artist)
            }
            entry.score += weight
            if (entry.artworkUri == null && !song.artworkUri.isNullOrBlank()) {
                entry.artworkUri = song.artworkUri
            }
        }

        return entryMap.mapValues { (_, e) ->
            ScoredAlbum(
                key = e.key,
                title = e.title,
                artist = e.artist,
                score = e.score,
                artworkUri = e.artworkUri
            )
        }
    }

    /**
     * Level 1: Scores distinct tracks from library songs, resolving match keys.
     */
    fun scoreLocalTracks(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        playedWeight: Long = 10L,
        unplayedWeight: Long = 1L
    ): Map<String, ScoredTrack> {
        if (librarySongs.isEmpty()) return emptyMap()

        class MutableTrackEntry(
            val key: String,
            val song: Song,
            var score: Long = 0L
        )

        val entryMap = HashMap<String, MutableTrackEntry>()
        for (song in librarySongs) {
            val artist = song.artist.trim()
            val isArtistValid = artist.isNotBlank() && !IdentifyRanking.isPlaceholderArtist(artist)
            val artistKey = if (isArtistValid) TrackMatchKeys.normalize(artist) else ""

            val trackKey = if (artistKey.isNotEmpty()) {
                TrackMatchKeys.composeKey(artistKey, TrackMatchKeys.normalize(song.title))
            } else {
                song.matchKey()
            }
            if (trackKey.isEmpty()) continue

            val weight = calculateSongPlayScore(song, playStats, playedWeight, unplayedWeight)
            val entry = entryMap.getOrPut(trackKey) {
                MutableTrackEntry(key = trackKey, song = song)
            }
            entry.score += weight
        }

        return entryMap.mapValues { (_, e) ->
            ScoredTrack(
                key = e.key,
                song = e.song,
                score = e.score
            )
        }
    }

    /**
     * Level 2: Scores and ranks distinct albums from local library songs using play stats and recent plays.
     */
    fun recommendLocalAlbums(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        limit: Int = 12
    ): List<ScoredAlbum> = scoreLocalAlbums(librarySongs, playStats)
        .values
        .sortedByDescending { it.score }
        .take(limit)

    /**
     * Level 2: Scores and ranks distinct tracks from local library songs using play stats and recent plays.
     */
    fun recommendLocalTracks(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>,
        limit: Int = 16
    ): List<Song> = scoreLocalTracks(librarySongs, playStats)
        .values
        .sortedByDescending { it.score }
        .take(limit)
        .map { it.song }
}
