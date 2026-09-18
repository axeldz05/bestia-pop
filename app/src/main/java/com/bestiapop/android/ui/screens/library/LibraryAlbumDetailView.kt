package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.DownloadMissingTracksButton
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.components.isCurrentPlaying
import com.bestiapop.android.ui.screens.discover.DiscoverTrackListItem
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

    val matchingCatalog =
        if (catalogCollection.title?.trim().equals(albumName.trim(), ignoreCase = true)) {
            catalogCollection
        } else {
            null
        }

    val album =
        remember(albums, albumName) {
            albums.firstOrNull { albumNamesMatch(it.name, albumName) || albumNamesMatch(it.displayName, albumName) }
        }
    val albumDisplayName = album?.displayName ?: albumName
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

    LaunchedEffect(albumName, albumArtist, isOfflineMode, matchingCatalog?.candidates) {
        if (matchingCatalog?.candidates?.isNotEmpty() == true) {
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
            if (catalogCandidates.isEmpty()) {
                emptyList()
            } else if (localSongs.isEmpty()) {
                catalogCandidates
            } else {
                val localTitles = localSongs.map { TrackMatchKeys.normalize(it.title) }.toSet()
                catalogCandidates.filter { candidate ->
                    !localTitles.contains(TrackMatchKeys.normalize(candidate.title))
                }
            }
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

    LibraryCollectionDetailView(
        title = albumDisplayName,
        onBack = onBack,
        songs = localSongs,
        currentSongId = currentSongId,
        songActions = actions.songActions,
        onPlaySong = { index -> viewModel.playCollection(localSongs, index) },
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
        heroSubtitle = albumArtist.takeIf { it.isNotBlank() },
        onSubtitleClick =
            if (albumArtist.isNotBlank()) {
                { viewModel.openLibraryArtist(albumArtist) }
            } else {
                null
            },
        artworkUri = albumArtwork,
        showSongArtwork = false,
        playEnabled = localSongs.isNotEmpty() || catalogCandidates.isNotEmpty(),
        shuffleEnabled = localSongs.isNotEmpty() || catalogCandidates.isNotEmpty(),
        metadataTextOverride = metadataTextOverride,
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
        extraContent = {
            if (missingCandidates.isNotEmpty()) {
                val isStreamingOnly = localSongs.isEmpty()
                val headerTitle =
                    if (isStreamingOnly) {
                        "Canciones del álbum (${missingCandidates.size})"
                    } else {
                        "Pistas faltantes del catálogo (${missingCandidates.size})"
                    }
                item(key = "missing-catalog-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    DownloadMissingTracksButton(
                        onClick = {
                            missingCandidates
                                .filter { viewModel.getTrackLibraryStatus(it.identity) != ItemLibraryStatus.DOWNLOADED }
                                .forEach { viewModel.downloadCatalogCandidate(it) }
                        },
                        label =
                            if (isStreamingOnly) {
                                "Descargar álbum (${missingCandidates.size})"
                            } else {
                                "Descargar faltantes (${missingCandidates.size})"
                            },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                itemsIndexed(
                    items = missingCandidates,
                    key = { idx, it -> "missing-candidate-${it.trackNumber}-${it.title}-$idx" },
                ) { _, candidate ->
                    val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    val trackStatus = viewModel.getTrackLibraryStatus(candidate.identity)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { viewModel.playCatalogCandidate(candidate) },
                            onDownload = { viewModel.downloadCatalogCandidate(candidate) },
                            activeDownload = activeDownload,
                            status = trackStatus,
                            highlighted = isPlaying,
                            showArtwork = false,
                            leading = {
                                val num = candidate.trackNumber.takeIf { it > 0 }
                                if (num != null) {
                                    Text(
                                        text = "$num",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
        modifier = modifier,
    )
}
