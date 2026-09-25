package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.screens.discover.AlbumLibraryActionButton
import com.bestiapop.android.ui.screens.discover.DiscoverTrackListItem
import com.bestiapop.android.ui.state.ItemLibraryStatus

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for album detail views.
 */
@Immutable
data class AlbumDetailActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffleAll: () -> Unit,
    val onPlaySong: (Int) -> Unit = {},
    val onPlayCandidate: (CatalogTrackCandidate) -> Unit = {},
    val onDownloadCandidate: (CatalogTrackCandidate) -> Unit = {},
    val onDownloadAll: (() -> Unit)? = null,
    val onSaveAlbum: (() -> Unit)? = null,
    val onSelectArtist: (String) -> Unit = {},
    val songActions: SongItemActions = SongItemActions.EMPTY,
    val albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
    val albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    val getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
)

/**
 * Level 2: Centralized layout for Album details, seamlessly supporting local library albums,
 * streaming/online catalog albums, and hybrid albums (local tracks + missing catalog candidates).
 */
@Composable
fun AlbumDetailLayout(
    title: String,
    actions: AlbumDetailActions,
    modifier: Modifier = Modifier,
    artist: String? = null,
    artworkUri: String? = null,
    metadataText: String? = null,
    localSongs: List<Song> = emptyList(),
    catalogCandidates: List<CatalogTrackCandidate> = emptyList(),
    currentSongId: Long? = null,
    currentItem: PlayableItem? = null,
    activeDownloads: List<ActiveDownload> = emptyList(),
    isLoading: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    actionButtons: (@Composable RowScope.() -> Unit)? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    bannerContent: (@Composable ColumnScope.() -> Unit)? = null,
    extraContent: (LazyListScope.() -> Unit)? = null,
) {
    val computedMetadataText =
        metadataText ?: remember(localSongs, catalogCandidates) {
            val count = if (localSongs.isNotEmpty()) localSongs.size else catalogCandidates.size
            if (count == 0) return@remember null
            val totalDurationMs =
                if (localSongs.isNotEmpty()) {
                    localSongs.sumOf { it.durationMs }
                } else {
                    catalogCandidates.sumOf { it.durationMs }
                }
            val durationLabel = formatDuration(totalDurationMs)
            buildString {
                append("$count canciones")
                if (durationLabel.isNotEmpty()) {
                    append(" • $durationLabel")
                }
            }
        }

    val playEnabled = localSongs.isNotEmpty() || catalogCandidates.isNotEmpty()
    val shuffleEnabled = playEnabled

    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = actions.onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            trailing = { headerTrailing?.invoke(this) },
        )

        if (isLoading && localSongs.isEmpty() && catalogCandidates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "album-detail-hero") {
                CollectionDetailHero(
                    title = title,
                    subtitle = artist?.takeIf { it.isNotBlank() },
                    metadata = computedMetadataText,
                    artworkUri = artworkUri,
                    fallbackIcon = fallbackIcon,
                    onSubtitleClick =
                        if (!artist.isNullOrBlank()) {
                            { actions.onSelectArtist(artist) }
                        } else {
                            null
                        },
                    playEnabled = playEnabled,
                    shuffleEnabled = shuffleEnabled,
                    onPlay = actions.onPlayAll,
                    onShuffle = actions.onShuffleAll,
                    actionButtons = {
                        if (actionButtons != null) {
                            actionButtons()
                        } else {
                            actions.onSaveAlbum?.let { onSave ->
                                AlbumLibraryActionButton(
                                    status = actions.albumStatus,
                                    onSave = onSave,
                                    onAlreadySaved = {},
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            if (localSongs.isEmpty() && actions.onDownloadAll != null &&
                                actions.albumStatus != ItemLibraryStatus.DOWNLOADED
                            ) {
                                AlbumDownloadStateButton(
                                    progress = actions.albumDownloadProgress,
                                    onDownload = actions.onDownloadAll,
                                )
                            }
                        }
                    },
                    bannerContent = bannerContent,
                )
            }

            // 1. Local songs section
            if (localSongs.isNotEmpty()) {
                itemsIndexed(
                    items = localSongs,
                    key = { _, song -> "album-local-song-${song.id}" },
                ) { index, song ->
                    val isPlaying = currentSongId == song.id
                    val trackNum = song.trackNumber.takeIf { it > 0 } ?: (index + 1)
                    SongListItem(
                        song = song,
                        actions = actions.songActions,
                        isCurrentPlaying = isPlaying,
                        showArtwork = false,
                        leading = {
                            Text(
                                text = "$trackNum",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color =
                                    if (isPlaying) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(28.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        },
                        onClick = { actions.onPlaySong(index) },
                    )
                }
            }

            // 2. Missing or online catalog candidates section
            if (catalogCandidates.isNotEmpty()) {
                val isStreamingOnly = localSongs.isEmpty()
                if (!isStreamingOnly) {
                    item(key = "missing-catalog-header") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Pistas faltantes del catálogo (${catalogCandidates.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        actions.onDownloadAll?.let { onDownloadAll ->
                            DownloadMissingTracksButton(
                                onClick = onDownloadAll,
                                label = "Descargar faltantes (${catalogCandidates.size})",
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = catalogCandidates,
                    key = { idx, it -> "album-candidate-${it.trackNumber}-${it.identity.artist}-${it.identity.title}-$idx" },
                    contentType = { _, _ -> "candidate-track-item" },
                ) { _, candidate ->
                    val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    val trackStatus = actions.getTrackStatus(candidate)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { actions.onPlayCandidate(candidate) },
                            onDownload = { actions.onDownloadCandidate(candidate) },
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

            extraContent?.invoke(this)
        }
    }
}

/**
 * Level 1: Low-level primitive overload offering individual parameters and slots (continuous granularity).
 */
@Composable
fun AlbumDetailLayout(
    title: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    modifier: Modifier = Modifier,
    artist: String? = null,
    artworkUri: String? = null,
    metadataText: String? = null,
    localSongs: List<Song> = emptyList(),
    catalogCandidates: List<CatalogTrackCandidate> = emptyList(),
    currentSongId: Long? = null,
    currentItem: PlayableItem? = null,
    activeDownloads: List<ActiveDownload> = emptyList(),
    isLoading: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    onPlaySong: (Int) -> Unit = {},
    onPlayCandidate: (CatalogTrackCandidate) -> Unit = {},
    onDownloadCandidate: (CatalogTrackCandidate) -> Unit = {},
    onDownloadAll: (() -> Unit)? = null,
    onSaveAlbum: (() -> Unit)? = null,
    onSelectArtist: (String) -> Unit = {},
    songActions: SongItemActions = SongItemActions.EMPTY,
    albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    actionButtons: (@Composable RowScope.() -> Unit)? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    bannerContent: (@Composable ColumnScope.() -> Unit)? = null,
    extraContent: (LazyListScope.() -> Unit)? = null,
) = AlbumDetailLayout(
    title = title,
    actions =
        AlbumDetailActions(
            onBack = onBack,
            onPlayAll = onPlayAll,
            onShuffleAll = onShuffleAll,
            onPlaySong = onPlaySong,
            onPlayCandidate = onPlayCandidate,
            onDownloadCandidate = onDownloadCandidate,
            onDownloadAll = onDownloadAll,
            onSaveAlbum = onSaveAlbum,
            onSelectArtist = onSelectArtist,
            songActions = songActions,
            albumDownloadProgress = albumDownloadProgress,
            albumStatus = albumStatus,
            getTrackStatus = getTrackStatus,
        ),
    modifier = modifier,
    artist = artist,
    artworkUri = artworkUri,
    metadataText = metadataText,
    localSongs = localSongs,
    catalogCandidates = catalogCandidates,
    currentSongId = currentSongId,
    currentItem = currentItem,
    activeDownloads = activeDownloads,
    isLoading = isLoading,
    listState = listState,
    fallbackIcon = fallbackIcon,
    actionButtons = actionButtons,
    headerTrailing = headerTrailing,
    bannerContent = bannerContent,
    extraContent = extraContent,
)
