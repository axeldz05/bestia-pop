package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode

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

    /**
     * Builds a flat, keyed list for LazyColumn: optional album headers + song rows
     * with stable indices into [songs] for playCollection.
     */
    fun buildListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode
    ): List<LibraryListItem> {
        if (songs.isEmpty()) return emptyList()

        return when (viewMode) {
            LibraryViewMode.FLAT -> songs.mapIndexed { index, song ->
                LibraryListItem.SongRow(song = song, index = index)
            }

            LibraryViewMode.ALBUM_GROUPS -> {
                val indexById = HashMap<Long, Int>(songs.size)
                songs.forEachIndexed { index, song -> indexById[song.id] = index }

                val items = ArrayList<LibraryListItem>(songs.size + songs.size / 4 + 1)
                songs.groupBy { it.album }.forEach { (albumName, albumSongs) ->
                    items += LibraryListItem.AlbumHeader(
                        albumName = albumName,
                        artistName = albumSongs.firstOrNull()?.artist ?: "Artista desconocido",
                        artworkUri = albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri,
                        songCount = albumSongs.size,
                        albumSongs = albumSongs
                    )
                    albumSongs.forEach { song ->
                        items += LibraryListItem.SongRow(
                            song = song,
                            index = indexById[song.id] ?: 0
                        )
                    }
                }
                items
            }
        }
    }

    fun extractAlbums(
        songs: List<Song>,
        overrides: Map<String, AlbumOverride> = emptyMap()
    ): List<Album> {
        return songs.groupBy { it.album }.map { (albumName, albumSongs) ->
            val override = overrides[albumName]
            val firstArt = albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
            val artistName = albumSongs.firstOrNull()?.artist ?: "Unknown Artist"
            val derivedYear = albumSongs.map { it.year }.firstOrNull { it > 0 } ?: 0
            Album(
                name = albumName,
                displayName = override?.displayName?.takeIf { it.isNotBlank() } ?: albumName,
                artist = override?.artist?.takeIf { it.isNotBlank() } ?: artistName,
                songCount = albumSongs.size,
                artworkUri = override?.artworkUri?.takeIf { it.isNotBlank() } ?: firstArt,
                genre = override?.genre?.takeIf { it.isNotBlank() }
                    ?: dominantGenre(albumSongs.map { it.genre }),
                year = if (override != null && override.year > 0) override.year else derivedYear,
                dateAdded = albumSongs.maxOfOrNull { it.dateAdded }
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    fun extractArtists(songs: List<Song>, artistPhotoMap: Map<String, String> = emptyMap()): List<Artist> {
        return songs.groupBy { it.artist }.map { (artistName, artistSongs) ->
            val albumCount = artistSongs.map { it.album }.distinct().size
            val photoArt = artistPhotoMap[artistName]
            Artist(
                name = artistName,
                songCount = artistSongs.size,
                albumCount = albumCount,
                photoUri = photoArt,
                genre = dominantGenre(artistSongs.map { it.genre }),
                dateAdded = artistSongs.maxOfOrNull { it.dateAdded }
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun dominantGenre(genres: List<String>): String? {
        if (genres.isEmpty()) return null
        return genres
            .filter { it.isNotBlank() && !it.equals("Unknown Genre", ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }
}
