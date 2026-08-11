package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.albumTrackSortKey
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryBrowseFilter
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
            firstArtwork(albumSongs)
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

    fun compareSongsWithinAlbum(a: Song, b: Song): Int {
        val byTrack = albumTrackSortKey(a.trackNumber).compareTo(albumTrackSortKey(b.trackNumber))
        if (byTrack != 0) return byTrack
        return a.title.lowercase().compareTo(b.title.lowercase())
    }

    fun sortSongsWithinAlbum(songs: List<Song>): List<Song> =
        songs.sortedWith(::compareSongsWithinAlbum)

    fun songsFromListItems(items: List<LibraryListItem>): List<Song> =
        items.mapNotNull { (it as? LibraryListItem.SongRow)?.song }

    /**
     * Builds a flat, keyed list for LazyColumn: optional album headers + song rows.
     * [LibraryListItem.SongRow.index] is the play index into [songsFromListItems].
     * ALBUM_GROUPS sorts each album by track number (unknown tracks last).
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
                val items = ArrayList<LibraryListItem>(songs.size + songs.size / 4 + 1)
                var songIndex = 0
                songs.groupBy { it.album }.forEach { (albumName, groupSongs) ->
                    val albumSongs = sortSongsWithinAlbum(groupSongs)
                    items += LibraryListItem.AlbumHeader(
                        albumName = albumName,
                        artistName = albumSongs.firstOrNull()?.artist ?: "Artista desconocido",
                        artworkUri = firstArtwork(albumSongs),
                        songCount = albumSongs.size,
                        albumSongs = albumSongs
                    )
                    albumSongs.forEach { song ->
                        items += LibraryListItem.SongRow(song = song, index = songIndex)
                        songIndex++
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
            val firstArt = firstArtwork(albumSongs)
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

    /**
     * Groups by genre label; blank → [UNKNOWN_GENRE]. Known genres A–Z; Unknown last.
     */
    fun extractGenres(songs: List<Song>): List<GenreGroup> {
        if (songs.isEmpty()) return emptyList()
        val groups = songs.groupBy { genreKey(it) }.map { (name, genreSongs) ->
            GenreGroup(
                name = name,
                songCount = genreSongs.size,
                artworkUri = firstArtwork(genreSongs),
                dateAdded = genreSongs.maxOfOrNull { it.dateAdded }
            )
        }
        val (unknown, known) = groups.partition { it.name.equals(UNKNOWN_GENRE, ignoreCase = true) }
        return known.sortedBy { it.name.lowercase() } + unknown
    }

    fun songsMatchingGenre(songs: List<Song>, genreName: String): List<Song> =
        songs.filter { genreKey(it).equals(genreName, ignoreCase = true) }

    /**
     * Flattens the songs represented by the current browse projection (play-all / shuffle).
     */
    fun songsForBrowseProjection(
        filter: LibraryBrowseFilter,
        songs: List<Song>,
        viewMode: LibraryViewMode = LibraryViewMode.FLAT,
        albums: List<Album>? = null,
        artists: List<Artist>? = null,
        genres: List<GenreGroup>? = null
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()
        return when (filter) {
            LibraryBrowseFilter.SONGS ->
                songsFromListItems(buildListItems(songs, viewMode))
            LibraryBrowseFilter.RECENT ->
                songs.sortedByDescending { it.dateAdded }
            LibraryBrowseFilter.ALBUMS -> {
                val albumList = albums ?: extractAlbums(songs)
                albumList.flatMap { album ->
                    sortSongsWithinAlbum(
                        songs.filter { it.album.equals(album.name, ignoreCase = true) }
                    )
                }
            }
            LibraryBrowseFilter.ARTISTS -> {
                val artistList = artists ?: extractArtists(songs)
                artistList.flatMap { artist ->
                    songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
                }
            }
            LibraryBrowseFilter.GENRES -> {
                val genreList = genres ?: extractGenres(songs)
                genreList.flatMap { genre -> songsMatchingGenre(songs, genre.name) }
            }
        }
    }

    private fun firstArtwork(songs: List<Song>): String? =
        songs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri

    private fun dominantGenre(genres: List<String>): String? {
        if (genres.isEmpty()) return null
        return genres
            .filter { it.isNotBlank() && !it.equals(UNKNOWN_GENRE, ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    companion object {
        const val UNKNOWN_GENRE = "Unknown Genre"

        fun genreKey(song: Song): String =
            song.genre.trim().ifBlank { UNKNOWN_GENRE }
    }
}
