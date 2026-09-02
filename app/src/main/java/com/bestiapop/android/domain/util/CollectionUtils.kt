package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song

/**
 * Shared collection utilities for alternating / interleaving items and ranking local library entities.
 */
object CollectionUtils {

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
     * Scores and ranks distinct artists from local library songs using play stats and recent plays.
     * Filters placeholder / empty artist names via [IdentifyRanking.isPlaceholderArtist].
     */
    fun calculateTopLocalArtists(
        librarySongs: List<Song>,
        playStats: Map<Long, Long>
    ): List<String> {
        if (librarySongs.isEmpty()) return emptyList()

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
