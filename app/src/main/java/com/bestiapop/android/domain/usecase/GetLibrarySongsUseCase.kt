package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption

class GetLibrarySongsUseCase {

    fun execute(
        songs: List<Song>,
        query: String,
        sortOption: SortOption
    ): List<Song> {
        // Build map of album artwork for fallback inheritance
        val albumArtMap = songs.groupBy { it.album }.mapValues { (_, albumSongs) ->
            albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
        }

        // Inherit album artwork across songs belonging to the same album
        val unifiedList = songs.map { song ->
            val albumArt = song.artworkUri ?: albumArtMap[song.album]
            if (albumArt != song.artworkUri) song.copy(artworkUri = albumArt) else song
        }

        // Multi-field search filtering (Title, Artist, Album, Genre)
        val filtered = if (query.isBlank()) {
            unifiedList
        } else {
            unifiedList.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true) ||
                song.genre.contains(query, ignoreCase = true)
            }
        }

        // Sorting by criteria
        return when (sortOption) {
            SortOption.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortOption.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SortOption.ALBUM -> filtered.sortedBy { it.album.lowercase() }
            SortOption.GENRE -> filtered.sortedBy { it.genre.lowercase() }
            SortOption.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
        }
    }

    fun extractAlbums(songs: List<Song>): List<Album> {
        return songs.groupBy { it.album }.map { (albumName, albumSongs) ->
            val firstArt = albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
            val artistName = albumSongs.firstOrNull()?.artist ?: "Unknown Artist"
            Album(
                name = albumName,
                artist = artistName,
                songCount = albumSongs.size,
                artworkUri = firstArt
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun extractArtists(songs: List<Song>, artistPhotoMap: Map<String, String> = emptyMap()): List<Artist> {
        return songs.groupBy { it.artist }.map { (artistName, artistSongs) ->
            val albumCount = artistSongs.map { it.album }.distinct().size
            val photoArt = artistPhotoMap[artistName]
            Artist(
                name = artistName,
                songCount = artistSongs.size,
                albumCount = albumCount,
                photoUri = photoArt
            )
        }.sortedBy { it.name.lowercase() }
    }
}
