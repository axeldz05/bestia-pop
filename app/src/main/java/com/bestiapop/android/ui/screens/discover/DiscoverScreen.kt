package com.bestiapop.android.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.LocalSubmenuGestureSettings
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.ui.components.SubmenuSwipeBox
import com.bestiapop.android.ui.components.findAlbumDownloadProgress
import com.bestiapop.android.ui.components.rememberSongQueueActions
import com.bestiapop.android.ui.screens.library.rememberSongActionDialogs
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.PlaylistDetailNav

/** Level 2: Host for remote/streaming drill-down details (artist, album/collection, ListenBrainz playlist/CF). */
@Composable
fun DiscoverScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val gestureSettings by viewModel.submenuGestureSettings.collectAsStateWithLifecycle()
    val catalogCollection by viewModel.catalogCollection.collectAsStateWithLifecycle()
    val lbPlaylistDetail by viewModel.lbPlaylistDetail.collectAsStateWithLifecycle()
    val cfRecommendationsState by viewModel.cfRecommendations.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val playlistDetail = navigation.playlistDetail
    val selectedLbPlaylistMbid = (playlistDetail as? PlaylistDetailNav.ListenBrainz)?.mbid
    val cfDetailOpen = playlistDetail is PlaylistDetailNav.CfRecommendations

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val songActions = rememberSongQueueActions(viewModel)
    val songDialogs = rememberSongActionDialogs(viewModel = viewModel, playlists = playlists)
    val songItemActions =
        remember(songActions, songDialogs) {
            SongItemActions.from(songActions, songDialogs)
        }

    val selectedCollectionTitle = catalogCollection.title
    val activeCandidates = catalogCollection.candidates
    val isLoadingCollection = catalogCollection.isLoading

    val hasNestedBack = selectedCollectionTitle != null || selectedLbPlaylistMbid != null || cfDetailOpen
    BackHandler(enabled = hasNestedBack) {
        if (selectedCollectionTitle != null) {
            viewModel.clearSelectedCollection()
        } else if (selectedLbPlaylistMbid != null || cfDetailOpen) {
            viewModel.closePlaylistDetail()
        }
    }

    val swipeActions =
        remember(viewModel, gestureSettings.swipeLeftAction) {
            DiscoverSwipeActions(
                onSwipeTrack = { track ->
                    viewModel.executeSubmenuActionForTrack(
                        gestureSettings.swipeLeftAction,
                        track,
                        onAddToPlaylist = { song -> songItemActions.onAddToPlaylist?.invoke(song) },
                    )
                },
                onSwipeAlbum = { album ->
                    viewModel.executeSubmenuActionForAlbum(
                        gestureSettings.swipeLeftAction,
                        album,
                    )
                },
                onSwipeArtist = { artistName ->
                    viewModel.executeSubmenuActionForArtist(
                        gestureSettings.swipeLeftAction,
                        artistName = artistName,
                    )
                },
            )
        }

    val discoverContext =
        remember(swipeActions, activeDownloads, viewModel) {
            DiscoverContext(
                swipeActions = swipeActions,
                activeDownloads = activeDownloads,
                getTrackStatus = viewModel::getTrackLibraryStatus,
                getAlbumStatus = viewModel::getAlbumLibraryStatus,
                onNotifyStatus = { viewModel.toast(it) },
            )
        }

    CompositionLocalProvider(
        LocalSubmenuGestureSettings provides gestureSettings,
        LocalDiscoverContext provides discoverContext,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            if (selectedCollectionTitle != null) {
                SubmenuSwipeBox(
                    settings = gestureSettings,
                    onSwipeRight = { viewModel.clearSelectedCollection() },
                    onSwipeLeft = {
                        if (catalogCollection.kind == CatalogCollectionKind.ARTIST) {
                            swipeActions.onSwipeArtist?.invoke(selectedCollectionTitle)
                        } else {
                            viewModel.executeSubmenuActionForAlbum(
                                gestureSettings.swipeLeftAction,
                                albumTitle = selectedCollectionTitle,
                                artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                                albumId = catalogCollection.selectionKey.orEmpty(),
                                coverUrl = catalogCollection.coverUrl,
                            )
                        }
                    },
                    canSwipeBack = true,
                    canExecuteAction = activeCandidates.isNotEmpty(),
                ) {
                    val albumProgress =
                        activeDownloads.findAlbumDownloadProgress(
                            albumTitle = selectedCollectionTitle,
                            artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                        )

                    if (catalogCollection.kind == CatalogCollectionKind.ARTIST) {
                        DiscoverArtistDetailSection(
                            viewModel = viewModel,
                            artistName = selectedCollectionTitle,
                            coverUrl = catalogCollection.coverUrl,
                            candidates = activeCandidates,
                            albums = catalogCollection.albums,
                            singlesAndEps = catalogCollection.singlesAndEps,
                            appearedOn = catalogCollection.appearedOn,
                            isLoading = isLoadingCollection,
                            currentItem = currentItem,
                        )
                    } else {
                        val albumStatus =
                            viewModel.getAlbumLibraryStatus(
                                albumTitle = selectedCollectionTitle,
                                artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                            )

                        val collectionActions =
                            remember(
                                activeCandidates,
                                selectedCollectionTitle,
                                albumProgress,
                            ) {
                                DiscoverCollectionActions(
                                    onBack = { viewModel.clearSelectedCollection() },
                                    onPlayAll = {
                                        viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = false)
                                    },
                                    onShuffle = {
                                        viewModel.playCatalogCandidates(activeCandidates, startIndex = 0, startShuffled = true)
                                    },
                                    onSaveAlbum = {
                                        viewModel.saveAlbumToLibrary(
                                            albumTitle = selectedCollectionTitle,
                                            artistName = activeCandidates.firstOrNull()?.artist.orEmpty(),
                                            coverUrl = catalogCollection.coverUrl,
                                            candidates = activeCandidates,
                                            albumId = catalogCollection.selectionKey.orEmpty(),
                                        )
                                    },
                                    onDownloadAll = {
                                        viewModel.downloadSelectedCandidatesBatch()
                                    },
                                    onPlayCandidate = { candidate ->
                                        viewModel.playCatalogCandidate(candidate, activeCandidates)
                                    },
                                    onDownloadCandidate = { candidate ->
                                        viewModel.downloadCatalogCandidate(candidate)
                                    },
                                    onSelectArtist = { artistName ->
                                        viewModel.selectArtistForInspection(artistName)
                                    },
                                    albumDownloadProgress = albumProgress,
                                )
                            }

                        // Collection Drill-down view (Album / Playlist / Genre)
                        DiscoverCollectionDetailView(
                            title = selectedCollectionTitle,
                            kind = catalogCollection.kind ?: CatalogCollectionKind.ALBUM,
                            coverUrl = catalogCollection.coverUrl,
                            candidates = activeCandidates,
                            isLoading = isLoadingCollection,
                            albumStatus = albumStatus,
                            currentItem = currentItem,
                            actions = collectionActions,
                            artist = catalogCollection.artist,
                        )
                    }
                }
            } else if (selectedLbPlaylistMbid != null || cfDetailOpen) {
                val lbItems = lbPlaylistDetail.data?.toPlayableItems() ?: emptyList()
                val cfItems = cfRecommendationsState.data?.toPlayableItems() ?: emptyList()
                val currentItems = if (selectedLbPlaylistMbid != null) lbItems else cfItems
                SubmenuSwipeBox(
                    settings = gestureSettings,
                    onSwipeRight = { viewModel.closePlaylistDetail() },
                    onSwipeLeft = { viewModel.executeSubmenuActionForPlayables(gestureSettings.swipeLeftAction, currentItems) },
                    canSwipeBack = true,
                    canExecuteAction = currentItems.isNotEmpty(),
                ) {
                    DiscoverPlaylistDetailHost(
                        selectedLbPlaylistMbid = selectedLbPlaylistMbid,
                        cfDetailOpen = cfDetailOpen,
                        lbPlaylistDetail = lbPlaylistDetail,
                        cfRecommendationsState = cfRecommendationsState,
                        currentItem = currentItem,
                        activeDownloads = activeDownloads,
                        songActions = songActions,
                        onEditLyrics = songDialogs.onEditLyrics,
                        onBack = { viewModel.closePlaylistDetail() },
                        onDownloadRemote = { viewModel.downloadRemoteItem(it) },
                        onRetryDownload = viewModel::retryActiveDownload,
                        onCancelDownload = viewModel::dismissActiveDownload,
                        onPlayMatched = { items, origin, startIndex ->
                            viewModel.playMatchedTracks(items, origin, startIndex = startIndex)
                        },
                        onShuffleMatched = { items, origin ->
                            viewModel.shuffleMatchedTracks(items, origin)
                        },
                        onSaveLbAsLocal = { onComplete ->
                            viewModel.saveListenBrainzPlaylistAsLocal(onComplete)
                        },
                        onOpenLocalPlaylist = { newId ->
                            viewModel.openLocalPlaylist(newId)
                        },
                        onImportLbWithDownloads = {
                            viewModel.importListenBrainzPlaylistWithDownloads()
                        },
                        songItemActions = songItemActions,
                        onSwipeRemote = { remote ->
                            viewModel.executeSubmenuActionForPlayables(gestureSettings.swipeLeftAction, listOf(remote))
                        },
                    )
                }
            }
        }
    }
}
