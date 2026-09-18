package com.bestiapop.android.ui.screens.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.ui.MusicPlayerViewModel

/**
 * Dedicated Genre Detail Submenu in Library.
 * Displays genre hero with playback controls and songs list with complete action menus.
 */
@Composable
fun LibraryGenreDetailView(
    genreName: String,
    viewModel: MusicPlayerViewModel,
    actions: LibrarySongListActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val currentSongId by viewModel.currentSongId.collectAsStateWithLifecycle()

    val localSongs =
        remember(allSongs, genreName) {
            viewModel.songsForGenre(allSongs, genreName)
        }

    LibraryCollectionDetailView(
        title = genreName,
        onBack = onBack,
        songs = localSongs,
        currentSongId = currentSongId,
        songActions = actions.songActions,
        onPlaySong = { index -> viewModel.playCollection(localSongs, index) },
        onPlayAll = { viewModel.playCollection(localSongs, 0) },
        onShuffleAll = { viewModel.shuffleCollection(localSongs) },
        heroSubtitle = "Género",
        artworkUri = remember(localSongs) { localSongs.firstArtworkUri() },
        fallbackIcon = Icons.Default.Album,
        showSongArtwork = true,
        modifier = modifier,
    )
}
