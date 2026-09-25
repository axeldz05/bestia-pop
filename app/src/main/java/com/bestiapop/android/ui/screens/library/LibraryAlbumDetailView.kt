package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.findMatchingAlbum
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.AlbumDetailActions
import com.bestiapop.android.ui.components.AlbumDetailLayout
import com.bestiapop.android.ui.components.findAlbumDownloadProgress
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.state.ItemLibraryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated Album Detail Submenu in Library.
 * Loads local album information and songs instantly (0ms delay), followed by an optional
 * asynchronous background check for missing catalog tracks when offline mode is disabled.
 * Also supports viewing albums directly from online catalog / streaming when drilled down.
 */
@Composable
fun LibraryAlbumDetailView(
    albumName: String,
    viewModel: MusicPlayerViewModel,
    actions: LibrarySongListActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val albums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val currentSongId by viewModel.currentSongId.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()

    val album =
        remember(albums, albumName) {
            findMatchingAlbum(albums, albumName)
        }
    val albumDisplayName = album?.displayName ?: albumName

    val matchingCatalog =
        remember(catalogCollection, albumName, albumDisplayName) {
            catalogCollection.takeIf { collection ->
                val title = collection.title?.trim()
                !title.isNullOrEmpty() && (
                    title.equals(albumName.trim(), ignoreCase = true) ||
                        title.equals(albumDisplayName.trim(), ignoreCase = true) ||
                        albumNamesMatch(title, albumName) ||
                        albumNamesMatch(title, albumDisplayName)
                )
            }
        }
    val localSongs =
        remember(allSongs, albumName) {
            viewModel.songsForAlbum(allSongs, albumName)
        }
    val albumArtist =
        album?.artist?.takeIf { it.isNotBlank() }
            ?: remember(localSongs) { localSongs.firstOrNull()?.artist.orEmpty() }.takeIf { it.isNotBlank() }
            ?: matchingCatalog
                ?.candidates
                ?.firstOrNull()
                ?.artist
                ?.takeIf { it.isNotBlank() }
            ?: navigation.libraryStack.artistName.orEmpty()

    var showAlbumMenu by remember { mutableStateOf(false) }

    // Online catalog candidates (missing tracks or full online album tracks)
    var fetchedCandidates by remember { mutableStateOf<List<CatalogTrackCandidate>>(emptyList()) }

    LaunchedEffect(albumName, albumArtist, isOfflineMode, matchingCatalog?.candidates, matchingCatalog?.isLoading) {
        if (matchingCatalog?.candidates?.isNotEmpty() == true) {
            fetchedCandidates = emptyList()
        } else if (matchingCatalog?.isLoading == true) {
            fetchedCandidates = emptyList()
        } else if (!isOfflineMode && albumArtist.isNotBlank() && albumName.isNotBlank()) {
            try {
                val candidates =
                    withContext(Dispatchers.IO) {
                        MetadataFetcher.fetchAlbumTrackCandidates(
                            albumId = "",
                            albumTitle = albumName,
                            artistName = albumArtist,
                            albumCoverUrl = album?.artworkUri,
                        )
                    }
                fetchedCandidates = candidates
            } catch (_: Exception) {
                // Ignore network errors gracefully
            }
        } else {
            fetchedCandidates = emptyList()
        }
    }

    val catalogCandidates = matchingCatalog?.candidates?.takeIf { it.isNotEmpty() } ?: fetchedCandidates

    val albumArtwork =
        album?.artworkUri
            ?: remember(localSongs) { localSongs.firstArtworkUri() }
            ?: matchingCatalog?.coverUrl
            ?: catalogCandidates.firstArtworkUri()

    val missingCandidates =
        remember(localSongs, catalogCandidates) {
            TrackMatchKeys.filterMissingAlbumCandidates<CatalogTrackCandidate>(catalogCandidates, localSongs)
        }

    val metadataTextOverride =
        if (localSongs.isEmpty() && catalogCandidates.isNotEmpty()) {
            val totalDurationMs = catalogCandidates.sumOf { it.durationMs }
            val durationLabel = formatDuration(totalDurationMs)
            buildString {
                append("${catalogCandidates.size} canciones")
                if (durationLabel.isNotEmpty()) {
                    append(" • $durationLabel")
                }
            }
        } else {
            null
        }

    val albumActions =
        remember(
            onBack,
            viewModel,
            album,
            localSongs,
            catalogCandidates,
            missingCandidates,
            actions.songActions,
            albumArtist,
            activeDownloads,
            albumDisplayName,
        ) {
            AlbumDetailActions(
                onBack = onBack,
                onPlayAll = {
                    if (album != null) {
                        viewModel.playAlbum(album, startShuffled = false)
                    } else if (localSongs.isNotEmpty()) {
                        viewModel.playCollection(localSongs, 0)
                    } else if (catalogCandidates.isNotEmpty()) {
                        viewModel.playCatalogCandidates(catalogCandidates, startIndex = 0, startShuffled = false)
                    }
                },
                onShuffleAll = {
                    if (album != null) {
                        viewModel.playAlbum(album, startShuffled = true)
                    } else if (localSongs.isNotEmpty()) {
                        viewModel.shuffleCollection(localSongs)
                    } else if (catalogCandidates.isNotEmpty()) {
                        viewModel.playCatalogCandidates(catalogCandidates, startIndex = 0, startShuffled = true)
                    }
                },
                onPlaySong = { index -> viewModel.playCollection(localSongs, index) },
                onPlayCandidate = { candidate ->
                    viewModel.playCatalogCandidate(
                        candidate = candidate,
                        collection = catalogCandidates.ifEmpty { missingCandidates },
                    )
                },
                onDownloadCandidate = { candidate -> viewModel.downloadCatalogCandidate(candidate) },
                onDownloadAll =
                    if (missingCandidates.isNotEmpty()) {
                        {
                            missingCandidates
                                .filter { viewModel.getTrackLibraryStatus(it.identity) != ItemLibraryStatus.DOWNLOADED }
                                .forEach { viewModel.downloadCatalogCandidate(it) }
                        }
                    } else {
                        null
                    },
                onSelectArtist = { artistName -> viewModel.openLibraryArtist(artistName) },
                songActions = actions.songActions,
                albumDownloadProgress = activeDownloads.findAlbumDownloadProgress(albumDisplayName, albumArtist),
                getTrackStatus = { candidate -> viewModel.getTrackLibraryStatus(candidate.identity) },
            )
        }

    AlbumDetailLayout(
        title = albumDisplayName,
        actions = albumActions,
        artist = albumArtist.takeIf { it.isNotBlank() },
        artworkUri = albumArtwork,
        metadataText = metadataTextOverride,
        localSongs = localSongs,
        catalogCandidates = missingCandidates,
        currentSongId = currentSongId,
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        headerTrailing = {
            Box {
                IconButton(onClick = { showAlbumMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones del álbum",
                    )
                }
                DropdownMenu(
                    expanded = showAlbumMenu,
                    onDismissRequest = { showAlbumMenu = false },
                ) {
                    AlbumEditCoverMenuItems(
                        onEditAlbum = {
                            showAlbumMenu = false
                            actions.albumActions.onEditAlbum(albumName)
                        },
                        onChangeCover = {
                            showAlbumMenu = false
                            actions.albumActions.onChangeAlbumCover(albumName)
                        },
                        onIdentifyAlbum = {
                            showAlbumMenu = false
                            actions.albumActions.onIdentifyAlbum(albumName)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Reproducir siguiente") },
                        onClick = {
                            showAlbumMenu = false
                            if (album != null) {
                                viewModel.playAlbumNext(album)
                            } else {
                                viewModel.playAlbumNext(albumName)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        onClick = {
                            showAlbumMenu = false
                            if (album != null) {
                                viewModel.enqueueAlbum(album)
                            } else {
                                viewModel.enqueueAlbum(albumName)
                            }
                        },
                    )
                }
            }
        },
        modifier = modifier,
    )
}
