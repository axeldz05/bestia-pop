package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption

data class BrowseLocalLibrarySnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<Playlist>
)

class BrowseLocalLibraryUseCase(
    private val library: GetLibrarySongsUseCase = GetLibrarySongsUseCase()
) {
    fun snapshot(
        songs: List<Song>,
        overrides: List<AlbumOverride>,
        playlists: List<Playlist>
    ): BrowseLocalLibrarySnapshot {
        val orderedSongs = library.execute(
            songs = songs,
            query = "",
            sortOption = SortOption.TITLE,
            sortDirection = SortDirection.ASC
        )
        val overrideMap = overrides.associateBy(AlbumOverride::albumKey)
        val artistArtwork = orderedSongs
            .groupBy(Song::artist)
            .mapValues { (_, artistSongs) ->
                artistSongs.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
                    .orEmpty()
            }
            .filterValues(String::isNotBlank)
        return BrowseLocalLibrarySnapshot(
            songs = orderedSongs,
            albums = library.extractAlbums(
                songs = orderedSongs,
                overrides = overrideMap,
                sortOption = SortOption.TITLE,
                sortDirection = SortDirection.ASC
            ),
            artists = library.extractArtists(
                songs = orderedSongs,
                artistPhotoMap = artistArtwork,
                sortOption = SortOption.TITLE,
                sortDirection = SortDirection.ASC
            ),
            playlists = playlists.sortedWith(
                compareBy<Playlist> { it.name.lowercase() }.thenBy(Playlist::id)
            )
        )
    }

    fun songsForAlbum(snapshot: BrowseLocalLibrarySnapshot, albumKey: String): List<Song> =
        library.sortSongsWithinAlbum(
            snapshot.songs.filter { it.album.equals(albumKey, ignoreCase = true) }
        )

    fun songsForArtist(snapshot: BrowseLocalLibrarySnapshot, artistName: String): List<Song> =
        snapshot.songs.filter { it.artist.equals(artistName, ignoreCase = true) }

    fun search(snapshot: BrowseLocalLibrarySnapshot, query: String): List<Song> =
        library.execute(
            songs = snapshot.songs,
            query = query,
            sortOption = SortOption.TITLE,
            sortDirection = SortDirection.ASC
        )

    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_PAGE_SIZE)
        val from = page.toLong() * safePageSize
        if (from >= items.size || from > Int.MAX_VALUE) return emptyList()
        val start = from.toInt()
        return items.subList(start, (start + safePageSize).coerceAtMost(items.size))
    }

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
