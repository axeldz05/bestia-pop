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
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtistDetailActions
import com.bestiapop.android.ui.components.ArtistDetailLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated Artist Detail Submenu in Library and Discover.
 * Loads local artist information and local albums instantly (0ms delay),
 * followed by an asynchronous background fetch of artist discography and top tracks
 * when offline mode is disabled. Also supports displaying preloaded catalog data.
 */
@Composable
fun LibraryArtistDetailView(
    artistName: String,
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialCoverUrl: String? = null,
    initialAlbums: List<CatalogAlbum> = emptyList(),
    initialSinglesAndEps: List<CatalogAlbum> = emptyList(),
    initialAppearedOn: List<CatalogAlbum> = emptyList(),
    initialTopTracks: List<CatalogTrackCandidate> = emptyList(),
    isLoading: Boolean = false,
) {
    val allSongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val allAlbums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()

    val localSongs =
        remember(allSongs, artistName) {
            viewModel.songsForArtist(allSongs, artistName)
        }
    val localAlbums =
        remember(allAlbums, artistName) {
            allAlbums.filter { album ->
                album.artist.equals(artistName, ignoreCase = true)
            }
        }
    val localAppearedOn =
        remember(allAlbums, localSongs, artistName) {
            val albumTitles = localSongs.map { it.album.lowercase().trim() }.toSet()
            allAlbums.filter { album ->
                !album.artist.equals(artistName, ignoreCase = true) &&
                    albumTitles.contains(album.name.lowercase().trim())
            }
        }

    var showArtistMenu by remember { mutableStateOf(false) }

    // Online catalog data
    var onlineCoverUrl by remember { mutableStateOf<String?>(initialCoverUrl) }
    var onlineAlbums by remember { mutableStateOf<List<CatalogAlbum>>(initialAlbums) }
    var onlineSinglesAndEps by remember { mutableStateOf<List<CatalogAlbum>>(initialSinglesAndEps) }
    var onlineAppearedOn by remember { mutableStateOf<List<CatalogAlbum>>(initialAppearedOn) }
    var onlineTopTracks by remember { mutableStateOf<List<CatalogTrackCandidate>>(initialTopTracks) }

    LaunchedEffect(artistName, isOfflineMode, initialAlbums, initialSinglesAndEps, initialAppearedOn, initialTopTracks) {
        if (initialAlbums.isNotEmpty() || initialSinglesAndEps.isNotEmpty() || initialAppearedOn.isNotEmpty() ||
            initialTopTracks.isNotEmpty()
        ) {
            onlineAlbums = initialAlbums
            onlineSinglesAndEps = initialSinglesAndEps
            onlineAppearedOn = initialAppearedOn
            onlineTopTracks = initialTopTracks
            onlineCoverUrl =
                initialCoverUrl
                    ?: initialAlbums.firstOrNull()?.coverUrl
                    ?: initialSinglesAndEps.firstOrNull()?.coverUrl
                    ?: initialAppearedOn.firstOrNull()?.coverUrl
            return@LaunchedEffect
        }
        if (!isOfflineMode && artistName.isNotBlank()) {
            try {
                val discography =
                    withContext(Dispatchers.IO) {
                        MetadataFetcher.fetchArtistDiscography(artistName)
                    }
                onlineCoverUrl =
                    discography.artistHit?.pictureUrl
                        ?: discography.albums.firstOrNull()?.coverUrl
                        ?: discography.singlesAndEps.firstOrNull()?.coverUrl
                        ?: discography.appearedOn.firstOrNull()?.coverUrl
                onlineAlbums = discography.albums
                onlineSinglesAndEps = discography.singlesAndEps
                onlineAppearedOn = discography.appearedOn
                onlineTopTracks = discography.topTracks.map { MetadataFetcher.toCatalogCandidate(it) }
            } catch (_: Exception) {
                // Ignore network errors gracefully
            }
        } else {
            onlineCoverUrl = null
            onlineAlbums = emptyList()
            onlineSinglesAndEps = emptyList()
            onlineAppearedOn = emptyList()
            onlineTopTracks = emptyList()
        }
    }

    val displayCoverUrl =
        onlineCoverUrl
            ?: localAlbums.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
            ?: localSongs.firstArtworkUri()

    val summaryText =
        buildString {
            if (localSongs.isNotEmpty()) append("${localSongs.size} en biblioteca • ")
            val totalReleases =
                if (onlineAlbums.isNotEmpty() || onlineSinglesAndEps.isNotEmpty()) {
                    onlineAlbums.size + onlineSinglesAndEps.size
                } else {
                    localAlbums.size
                }
            append("$totalReleases lanzamientos")
        }

    val onStartRadio: () -> Unit = {
        val seed = localSongs.randomOrNull()
        if (seed != null) {
            viewModel.startRadio(seedSong = seed)
        } else {
            viewModel.startRadio()
        }
    }

    val artistActions =
        remember(
            viewModel,
            localSongs,
            onlineTopTracks,
            artistName,
            onBack,
            onStartRadio,
        ) {
            ArtistDetailActions(
                onBack = onBack,
                onPlayAll = {
                    if (localSongs.isNotEmpty()) {
                        viewModel.playArtist(artistName, startShuffled = false)
                    } else if (onlineTopTracks.isNotEmpty()) {
                        viewModel.playCatalogCandidates(onlineTopTracks, startIndex = 0, startShuffled = false)
                    }
                },
                onShuffle = {
                    if (localSongs.isNotEmpty()) {
                        viewModel.playArtist(artistName, startShuffled = true)
                    } else if (onlineTopTracks.isNotEmpty()) {
                        viewModel.playCatalogCandidates(onlineTopTracks, startIndex = 0, startShuffled = true)
                    }
                },
                onStartRadio = onStartRadio,
                onSelectLocalAlbum = { album ->
                    viewModel.openLibraryAlbum(album.name, fromNestedParent = true)
                },
                onSelectOnlineAlbum = { album ->
                    viewModel.openAlbum(album, fromNestedParent = true)
                },
                onSaveOnlineAlbum = { album ->
                    viewModel.saveAlbumToLibrary(album)
                },
                onPlayTrack = { candidate ->
                    viewModel.playCatalogCandidate(candidate, onlineTopTracks)
                },
                onDownloadTrack = { candidate ->
                    viewModel.downloadCatalogCandidate(candidate)
                },
                getAlbumStatus = { album -> viewModel.getAlbumLibraryStatus(album) },
                getTrackStatus = { candidate -> viewModel.getTrackLibraryStatus(candidate.identity) },
            )
        }

    ArtistDetailLayout(
        artistName = artistName,
        actions = artistActions,
        summary = summaryText,
        displayCoverUrl = displayCoverUrl,
        localAlbums = localAlbums,
        onlineAlbums = onlineAlbums,
        onlineSinglesAndEps = onlineSinglesAndEps,
        localAppearedOn = localAppearedOn,
        onlineAppearedOn = onlineAppearedOn,
        onlineTopTracks = onlineTopTracks,
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        isLoading = isLoading,
        playEnabled = localSongs.isNotEmpty() || localAlbums.isNotEmpty() || onlineTopTracks.isNotEmpty(),
        modifier = modifier,
        headerTrailing = {
            Box {
                IconButton(onClick = { showArtistMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones del artista",
                    )
                }
                DropdownMenu(
                    expanded = showArtistMenu,
                    onDismissRequest = { showArtistMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproducir siguiente") },
                        onClick = {
                            showArtistMenu = false
                            viewModel.playArtistNext(artistName)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        onClick = {
                            showArtistMenu = false
                            viewModel.enqueueArtist(artistName)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Iniciar radio") },
                        onClick = {
                            showArtistMenu = false
                            onStartRadio()
                        },
                    )
                }
            }
        },
    )
}
