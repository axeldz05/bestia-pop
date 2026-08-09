package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta

/**
 * Shared artist+title matching keys used by radio, downloads, imports, and library lookups.
 */
object TrackMatchKeys {
    fun normalize(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[\\p{Punct}\\p{IsPunctuation}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun matchKey(artist: String, title: String): String {
        val a = normalize(artist)
        val t = normalize(title)
        if (a.isEmpty() || t.isEmpty()) return ""
        return "$a|$t"
    }

    /** L2: stable [ActiveDownload] / queue id from artist+title (empty if either blank). */
    fun downloadIdFor(artist: String, title: String): String = matchKey(artist, title)

    fun buildLibraryIndex(library: List<Song>): Map<String, Song> {
        val map = HashMap<String, Song>(library.size)
        for (song in library) {
            val key = matchKey(song.artist, song.title)
            if (key.isNotEmpty() && !map.containsKey(key)) {
                map[key] = song
            }
        }
        return map
    }

    fun <T> buildIndex(
        items: List<T>,
        artistOf: (T) -> String,
        titleOf: (T) -> String
    ): Map<String, T> {
        val map = HashMap<String, T>(items.size)
        for (item in items) {
            val key = matchKey(artistOf(item), titleOf(item))
            if (key.isNotEmpty() && !map.containsKey(key)) {
                map[key] = item
            }
        }
        return map
    }
}

fun TrackMeta.matchKey(): String = TrackMatchKeys.matchKey(artist, title)
