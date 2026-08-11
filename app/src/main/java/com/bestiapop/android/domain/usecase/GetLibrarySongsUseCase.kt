package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.albumTrackSortKey
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryViewMode

class GetLibrarySongsUseCase {

    fun execute(
        songs: List<Song>,
        query: String,
        sortOption: SortOption,
        sortDirection: SortDirection = SortDirection.defaultFor(sortOption)
    ): List<Song> {
        // Album artwork fallback, skipping generic albums: "Unknown Album" is the literal stored for
        // every albumless song, so inheriting inside that bucket showed one cover on unrelated tracks.
        val albumArtMap = songs.asSequence()
            .filterNot { IdentifyRanking.isGenericAlbum(it.album) }
            .groupBy { it.album }
            .mapValues { (_, albumSongs) -> firstArtwork(albumSongs) }

        val unifiedList = songs.map { song ->
            val albumArt = song.artworkUri ?: albumArtMap[song.album]
            if (albumArt != song.artworkUri) song.copy(artworkUri = albumArt) else song
        }

        // Multi-field search (title/artist/album/genre); case + diacritic insensitive
        val filtered = if (query.isBlank()) {
            unifiedList
        } else {
            unifiedList.filter { song ->
                TrackMatchKeys.containsNormalized(song.title, query) ||
                    TrackMatchKeys.containsNormalized(song.artist, query) ||
                    TrackMatchKeys.containsNormalized(song.album, query) ||
                    TrackMatchKeys.containsNormalized(song.genre, query)
            }
        }

        val ascending = sortDirection == SortDirection.ASC
        return when (sortOption) {
            SortOption.TITLE -> filtered.sortedByDir(ascending) { it.title.lowercase() }
            SortOption.ARTIST -> filtered.sortedByDir(ascending) { it.artist.lowercase() }
            SortOption.ALBUM -> filtered.sortedByDir(ascending) { it.album.lowercase() }
            SortOption.GENRE -> filtered.sortedByDir(ascending) { it.genre.lowercase() }
            SortOption.DATE_ADDED -> filtered.sortedByDir(ascending) { it.dateAdded }
        }
    }

    private fun <T : Comparable<T>> List<Song>.sortedByDir(
        ascending: Boolean,
        selector: (Song) -> T
    ): List<Song> =
        if (ascending) sortedBy(selector) else sortedByDescending(selector)

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
        viewMode: LibraryViewMode,
        overrides: Map<String, AlbumOverride> = emptyMap()
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
                    val override = overrides[albumName]
                    items += LibraryListItem.AlbumHeader(
                        albumName = albumName,
                        displayName = override?.displayName?.takeIf { it.isNotBlank() } ?: albumName,
                        artistName = override?.artist?.takeIf { it.isNotBlank() }
                            ?: albumSongs.firstOrNull()?.artist
                            ?: "Artista desconocido",
                        artworkUri = override?.artworkUri?.takeIf { it.isNotBlank() }
                            ?: firstArtwork(albumSongs),
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
        overrides: Map<String, AlbumOverride> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<Album> {
        val ascending = sortDirection == SortDirection.ASC
        val albums = songs.groupBy { it.album }.map { (albumName, albumSongs) ->
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
        }
        return when (sortOption) {
            SortOption.TITLE, SortOption.ALBUM ->
                albums.sortedAggregates(ascending) { it.displayName }
            SortOption.ARTIST ->
                albums.sortedAggregates(ascending) { it.artist }
            SortOption.GENRE ->
                albums.sortedAggregates(ascending) { it.genre ?: "" }
            SortOption.DATE_ADDED ->
                albums.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.displayName }
        }
    }

    fun extractArtists(
        songs: List<Song>,
        artistPhotoMap: Map<String, String> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<Artist> {
        val ascending = sortDirection == SortDirection.ASC
        val artists = songs.groupBy { it.artist }.map { (artistName, artistSongs) ->
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
        }
        return when (sortOption) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM ->
                artists.sortedAggregates(ascending) { it.name }
            SortOption.GENRE ->
                artists.sortedAggregates(ascending) { it.genre ?: "" }
            SortOption.DATE_ADDED ->
                artists.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.name }
        }
    }

    /**
     * Groups by genre label; blank → [Song.UNKNOWN_GENRE]. Known genres sorted; Unknown always last.
     */
    fun extractGenres(
        songs: List<Song>,
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<GenreGroup> {
        if (songs.isEmpty()) return emptyList()
        val ascending = sortDirection == SortDirection.ASC
        val groups = songs.groupBy { genreKey(it) }.map { (name, genreSongs) ->
            GenreGroup(
                name = name,
                songCount = genreSongs.size,
                artworkUri = firstArtwork(genreSongs),
                dateAdded = genreSongs.maxOfOrNull { it.dateAdded }
            )
        }
        val (unknown, known) = groups.partition { it.name.equals(Song.UNKNOWN_GENRE, ignoreCase = true) }
        val sortedKnown = when (sortOption) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM, SortOption.GENRE ->
                known.sortedAggregates(ascending) { it.name }
            SortOption.DATE_ADDED ->
                known.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.name }
        }
        return sortedKnown + unknown
    }

    private fun <T> List<T>.sortedAggregates(
        ascending: Boolean,
        useLong: Boolean = false,
        longKey: ((T) -> Long?)? = null,
        stringKey: (T) -> String
    ): List<T> =
        if (useLong && longKey != null) {
            if (ascending) sortedBy { longKey(it) ?: 0L } else sortedByDescending { longKey(it) ?: 0L }
        } else {
            if (ascending) sortedBy { stringKey(it).lowercase() }
            else sortedByDescending { stringKey(it).lowercase() }
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
                songs.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
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
            .filter { it.isNotBlank() && !it.equals(Song.UNKNOWN_GENRE, ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    companion object {
        fun genreKey(song: Song): String =
            song.genre.trim().ifBlank { Song.UNKNOWN_GENRE }
    }
}
