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

    /** Batch catalog job id; pairs with [downloadIdFor] for [findByTrack] lookup. */
    fun batchDownloadIdFor(artist: String, title: String): String {
        val key = downloadIdFor(artist, title)
        return if (key.isEmpty()) "" else "batch:$key"
    }

    fun buildLibraryIndex(library: List<Song>): Map<String, Song> =
        buildIndex(library, artistOf = { it.artist }, titleOf = { it.title })

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

    /** Resolve a library song from a pre-built [buildLibraryIndex] map. */
    fun lookupLocalSong(index: Map<String, Song>, meta: TrackMeta): Song? {
        val key = meta.matchKey()
        return if (key.isEmpty()) null else index[key]
    }

    fun lookupLocalSong(library: List<Song>, meta: TrackMeta): Song? =
        lookupLocalSong(buildLibraryIndex(library), meta)

    /**
     * L2: build a library index once and map each item (via [metaOf]) to a result.
     * When [skipBlank] is true, rows with blank title or artist are omitted.
     * Level 1 ([buildLibraryIndex], [lookupLocalSong]) stays public for radio/dedupe paths.
     */
    fun <T, R> matchAgainstLibrary(
        items: List<T>,
        library: List<Song>,
        metaOf: (T) -> TrackMeta,
        skipBlank: Boolean = false,
        transform: (T, Song?) -> R?
    ): List<R> {
        val index = buildLibraryIndex(library)
        val out = ArrayList<R>(items.size)
        for (item in items) {
            val meta = metaOf(item)
            if (skipBlank && (meta.title.isBlank() || meta.artist.isBlank())) continue
            val result = transform(item, lookupLocalSong(index, meta)) ?: continue
            out.add(result)
        }
        return out
    }

    /** L2 convenience when items themselves are [TrackMeta]. */
    fun <T : TrackMeta, R> matchMetasAgainstLibrary(
        items: List<T>,
        library: List<Song>,
        skipBlank: Boolean = false,
        transform: (T, Song?) -> R?
    ): List<R> = matchAgainstLibrary(
        items = items,
        library = library,
        metaOf = { it },
        skipBlank = skipBlank,
        transform = transform
    )
}

fun TrackMeta.matchKey(): String = TrackMatchKeys.matchKey(artist, title)
