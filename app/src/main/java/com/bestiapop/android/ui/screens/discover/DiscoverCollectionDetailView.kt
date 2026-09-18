package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.ui.components.ActiveAlbumDownloadProgress
import com.bestiapop.android.ui.components.AlbumDownloadStateButton
import com.bestiapop.android.ui.components.CollectionDetailHero
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.isCurrentPlaying
import com.bestiapop.android.ui.state.CatalogCollectionKind
import com.bestiapop.android.ui.state.ItemLibraryStatus

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for collection detail views.
 */
@Immutable
data class DiscoverCollectionActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onSaveAlbum: () -> Unit,
    val onDownloadAll: () -> Unit,
    val onPlayCandidate: (CatalogTrackCandidate) -> Unit,
    val onDownloadCandidate: (CatalogTrackCandidate) -> Unit,
    val onSelectArtist: (String) -> Unit = {},
    val albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
)

/** Level 2: Collection drill-down view using bundled [DiscoverCollectionActions]. */
@Composable
fun DiscoverCollectionDetailView(
    title: String,
    kind: CatalogCollectionKind,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    isLoading: Boolean,
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    currentItem: PlayableItem? = null,
    actions: DiscoverCollectionActions,
    modifier: Modifier = Modifier,
) {
    DiscoverCollectionDetailView(
        title = title,
        kind = kind,
        coverUrl = coverUrl,
        candidates = candidates,
        isLoading = isLoading,
        albumStatus = albumStatus,
        albumDownloadProgress = actions.albumDownloadProgress,
        currentItem = currentItem,
        onBack = actions.onBack,
        onPlayAll = actions.onPlayAll,
        onShuffle = actions.onShuffle,
        onSaveAlbum = actions.onSaveAlbum,
        onDownloadAll = actions.onDownloadAll,
        onPlayCandidate = actions.onPlayCandidate,
        onDownloadCandidate = actions.onDownloadCandidate,
        onSelectArtist = actions.onSelectArtist,
        modifier = modifier,
    )
}

/** Level 1: Collection drill-down view with individual callbacks (continuous granularity). */
@Composable
fun DiscoverCollectionDetailView(
    title: String,
    kind: CatalogCollectionKind,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSaveAlbum: () -> Unit,
    onDownloadAll: () -> Unit,
    onPlayCandidate: (CatalogTrackCandidate) -> Unit,
    onDownloadCandidate: (CatalogTrackCandidate) -> Unit,
    onSelectArtist: (String) -> Unit = {},
    albumStatus: ItemLibraryStatus = ItemLibraryStatus.NOT_IN_LIBRARY,
    albumDownloadProgress: ActiveAlbumDownloadProgress = ActiveAlbumDownloadProgress(),
    currentItem: PlayableItem? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "collection-hero-header") {
                val artistName = candidates.firstOrNull()?.artist.orEmpty()
                val fallback =
                    when (kind) {
                        CatalogCollectionKind.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
                        CatalogCollectionKind.GENRE -> Icons.Default.Album
                        else -> Icons.Default.MusicNote
                    }
                CollectionDetailHero(
                    title = title,
                    subtitle = artistName.takeIf { it.isNotBlank() },
                    metadata = "${candidates.size} canciones",
                    artworkUri = coverUrl,
                    fallbackIcon = fallback,
                    onSubtitleClick =
                        if (artistName.isNotBlank()) {
                            { onSelectArtist(artistName) }
                        } else {
                            null
                        },
                    onPlay = onPlayAll,
                    onShuffle = onShuffle,
                    playEnabled = candidates.isNotEmpty(),
                    shuffleEnabled = candidates.isNotEmpty(),
                    actionButtons = {
                        if (kind == CatalogCollectionKind.ALBUM) {
                            AlbumLibraryActionButton(
                                status = albumStatus,
                                onSave = onSaveAlbum,
                                onAlreadySaved = {},
                                modifier = Modifier.size(36.dp),
                            )
                        }

                        if (albumStatus != ItemLibraryStatus.DOWNLOADED) {
                            AlbumDownloadStateButton(
                                progress = albumDownloadProgress,
                                onDownload = onDownloadAll,
                            )
                        }
                    },
                )
            }

            itemsIndexed(
                items = candidates,
                key = { index, it -> "candidate-${it.trackNumber}-${it.identity.artist}-${it.identity.title}-$index" },
                contentType = { _, _ -> "candidate-track-item" },
            ) { _, candidate ->
                val activeDownload = LocalDiscoverContext.current.activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    DiscoverTrackListItem(
                        track = candidate,
                        onPlay = { onPlayCandidate(candidate) },
                        onDownload = { onDownloadCandidate(candidate) },
                        activeDownload = activeDownload,
                        highlighted = isPlaying,
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
    }
}
