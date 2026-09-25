package com.bestiapop.android.ui.screens.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtistDetailActions
import com.bestiapop.android.ui.components.ArtistDetailLayout
import com.bestiapop.android.ui.screens.library.LibraryArtistDetailView

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for artist discovery view.
 */
@Immutable
data class DiscoverArtistActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onStartRadio: () -> Unit,
    val onSelectAlbum: (CatalogAlbum) -> Unit,
    val onSaveAlbum: (CatalogAlbum) -> Unit,
    val onPlayTrack: (CatalogTrackCandidate) -> Unit,
    val onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    val onPlayLocalSong: (Song) -> Unit = {},
) {
    fun toArtistDetailActions(
        artistName: String,
        getAlbumStatus: (CatalogAlbum) -> com.bestiapop.android.ui.state.ItemLibraryStatus,
        getTrackStatus: (CatalogTrackCandidate) -> com.bestiapop.android.ui.state.ItemLibraryStatus,
    ): ArtistDetailActions =
        ArtistDetailActions(
            onBack = onBack,
            onPlayAll = onPlayAll,
            onShuffle = onShuffle,
            onStartRadio = onStartRadio,
            onSelectLocalAlbum = { album ->
                onSelectAlbum(
                    CatalogAlbum(
                        id = "",
                        title = album.displayName,
                        artist = artistName,
                        coverUrl = album.artworkUri,
                        trackCount = album.songCount,
                    ),
                )
            },
            onSelectOnlineAlbum = onSelectAlbum,
            onSaveOnlineAlbum = onSaveAlbum,
            onPlayTrack = onPlayTrack,
            onDownloadTrack = onDownloadTrack,
            onPlayLocalSong = onPlayLocalSong,
            getAlbumStatus = getAlbumStatus,
            getTrackStatus = getTrackStatus,
        )
}

/** Level 3: Stateful artist detail section delegating to the unified [LibraryArtistDetailView]. */
@Composable
internal fun DiscoverArtistDetailSection(
    viewModel: MusicPlayerViewModel,
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    singlesAndEps: List<CatalogAlbum> = emptyList(),
    appearedOn: List<CatalogAlbum> = emptyList(),
    isLoading: Boolean,
    currentItem: PlayableItem?,
    modifier: Modifier = Modifier,
) {
    LibraryArtistDetailView(
        artistName = artistName,
        viewModel = viewModel,
        onBack = { viewModel.clearSelectedCollection() },
        modifier = modifier,
        initialCoverUrl = coverUrl,
        initialAlbums = albums,
        initialSinglesAndEps = singlesAndEps,
        initialAppearedOn = appearedOn,
        initialTopTracks = candidates,
        isLoading = isLoading,
    )
}

/** Level 2: Artist detail view using bundled [DiscoverArtistActions]. */
@Composable
fun DiscoverArtistDetailView(
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    localSongs: List<Song>,
    localAlbums: List<Album>,
    currentItem: PlayableItem?,
    actions: DiscoverArtistActions,
    modifier: Modifier = Modifier,
) = DiscoverArtistDetailView(
    artistName = artistName,
    coverUrl = coverUrl,
    candidates = candidates,
    albums = albums,
    isLoading = isLoading,
    localSongs = localSongs,
    localAlbums = localAlbums,
    currentItem = currentItem,
    onBack = actions.onBack,
    onPlayAll = actions.onPlayAll,
    onShuffle = actions.onShuffle,
    onStartRadio = actions.onStartRadio,
    onSelectAlbum = actions.onSelectAlbum,
    onSaveAlbum = actions.onSaveAlbum,
    onPlayTrack = actions.onPlayTrack,
    onDownloadTrack = actions.onDownloadTrack,
    onPlayLocalSong = actions.onPlayLocalSong,
    modifier = modifier,
)

/** Level 1: Artist detail view showing local library content vs online discography and top tracks. */
@Composable
fun DiscoverArtistDetailView(
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    localSongs: List<Song>,
    localAlbums: List<Album>,
    currentItem: PlayableItem?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    onPlayTrack: (CatalogTrackCandidate) -> Unit,
    onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    onPlayLocalSong: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val discoverContext = LocalDiscoverContext.current
    val summaryText =
        remember(localSongs.size, albums.size, localAlbums.size) {
            buildString {
                if (localSongs.isNotEmpty()) append("${localSongs.size} en biblioteca • ")
                val totalAlbums = if (albums.isNotEmpty()) albums.size else localAlbums.size
                append("$totalAlbums álbumes")
            }
        }

    val actions =
        remember(
            onBack,
            onPlayAll,
            onShuffle,
            onStartRadio,
            onSelectAlbum,
            onSaveAlbum,
            onPlayTrack,
            onDownloadTrack,
            onPlayLocalSong,
            discoverContext,
        ) {
            ArtistDetailActions(
                onBack = onBack,
                onPlayAll = onPlayAll,
                onShuffle = onShuffle,
                onStartRadio = onStartRadio,
                onSelectLocalAlbum = { album ->
                    onSelectAlbum(
                        CatalogAlbum(
                            id = "",
                            title = album.displayName,
                            artist = artistName,
                            coverUrl = album.artworkUri,
                            trackCount = album.songCount,
                        ),
                    )
                },
                onSelectOnlineAlbum = onSelectAlbum,
                onSaveOnlineAlbum = onSaveAlbum,
                onPlayTrack = onPlayTrack,
                onDownloadTrack = onDownloadTrack,
                onPlayLocalSong = onPlayLocalSong,
                getAlbumStatus = { discoverContext.getAlbumStatus(it.title, artistName) },
                getTrackStatus = { discoverContext.getTrackStatus(it.identity) },
            )
        }

    ArtistDetailLayout(
        artistName = artistName,
        actions = actions,
        summary = summaryText,
        displayCoverUrl = coverUrl ?: localAlbums.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) },
        localAlbums = localAlbums,
        onlineAlbums = albums,
        onlineTopTracks = candidates,
        currentItem = currentItem,
        activeDownloads = discoverContext.activeDownloads,
        isLoading = isLoading,
        playEnabled = localSongs.isNotEmpty() || localAlbums.isNotEmpty() || candidates.isNotEmpty(),
        modifier = modifier,
    )
}
