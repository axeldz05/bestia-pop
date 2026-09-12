package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.ui.state.ItemLibraryStatus
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.findUiDownloadByTrack
import com.bestiapop.android.ui.components.isCurrentPlaying

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
    val onPlayLocalSong: (Song) -> Unit
)

/** Level 3: Stateful artist detail section that collects library songs and albums only when mounted. */
@Composable
internal fun DiscoverArtistDetailSection(
    viewModel: MusicPlayerViewModel,
    artistName: String,
    coverUrl: String?,
    candidates: List<CatalogTrackCandidate>,
    albums: List<CatalogAlbum>,
    isLoading: Boolean,
    currentItem: PlayableItem?,
    modifier: Modifier = Modifier
) {
    val librarySongs by viewModel.libraryProjection.songs.collectAsStateWithLifecycle()
    val libraryAlbums by viewModel.libraryProjection.albums.collectAsStateWithLifecycle()
    val artistLocalSongs = remember(librarySongs, artistName) {
        viewModel.songsForArtist(librarySongs, artistName)
    }
    val artistLocalAlbums = remember(libraryAlbums, artistLocalSongs, artistName) {
        val albumTitles = artistLocalSongs.map { it.album.lowercase().trim() }.toSet()
        libraryAlbums.filter { album ->
            album.artist.equals(artistName, ignoreCase = true) ||
                albumTitles.contains(album.name.lowercase().trim())
        }
    }

    val artistActions = remember(candidates, artistName, artistLocalSongs) {
        DiscoverArtistActions(
            onBack = { viewModel.clearSelectedCollection() },
            onPlayAll = {
                viewModel.playCatalogCandidates(candidates, startIndex = 0, startShuffled = false)
            },
            onShuffle = {
                viewModel.playCatalogCandidates(candidates, startIndex = 0, startShuffled = true)
            },
            onStartRadio = {
                val seed = artistLocalSongs.randomOrNull()
                if (seed != null) {
                    viewModel.startRadio(seedSong = seed)
                } else {
                    viewModel.startRadio()
                }
            },
            onSelectAlbum = { album ->
                viewModel.selectAlbumForInspection(album)
            },
            onSaveAlbum = { album ->
                viewModel.saveAlbumToLibrary(album)
            },
            onPlayTrack = { candidate ->
                val index = candidates.indexOf(candidate)
                if (index >= 0) {
                    viewModel.playCatalogCandidates(candidates, startIndex = index, startShuffled = false)
                } else {
                    viewModel.playCatalogCandidate(candidate)
                }
            },
            onDownloadTrack = { candidate ->
                viewModel.downloadCatalogCandidate(candidate)
            },
            onPlayLocalSong = { song ->
                viewModel.playSong(song)
            }
        )
    }

    DiscoverArtistDetailView(
        artistName = artistName,
        coverUrl = coverUrl,
        candidates = candidates,
        albums = albums,
        isLoading = isLoading,
        localSongs = artistLocalSongs,
        localAlbums = artistLocalAlbums,
        currentItem = currentItem,
        actions = artistActions,
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    DiscoverArtistDetailView(
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
        modifier = modifier
    )
}

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
    onPlayLocalSong: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = artistName,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Hero
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!coverUrl.isNullOrBlank()) {
                            ArtworkThumbnail(
                                artworkUri = coverUrl,
                                contentDescription = artistName,
                                size = 88.dp,
                                cornerRadius = 44.dp,
                                modifier = Modifier.clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = artistName.firstOrNull()?.uppercase().orEmpty(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artistName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        val summaryText = buildString {
                            if (localSongs.isNotEmpty()) append("${localSongs.size} en biblioteca • ")
                            append("${albums.size} álbumes")
                        }
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onPlayAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }

                            FilledTonalButton(
                                onClick = onShuffle,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            }

                            OutlinedButton(
                                onClick = onStartRadio,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Radio")
                            }
                        }
                    }
                }
            }

            // Sección 1: En tu biblioteca / Guardados
            item {
                Text(
                    text = "En tu biblioteca",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (localSongs.isEmpty() && localAlbums.isEmpty()) {
                item {
                    EmptyListHint(
                        text = "No tienes canciones descargadas de este artista todavía",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                if (localAlbums.isNotEmpty()) {
                    item {
                        DiscoverCarouselRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = localAlbums,
                                key = { "local-alb-${it.name}" }
                            ) { album ->
                                val isDownloaded = localSongs.any { it.album == album.name && !it.isRemote }
                                val catalogAlbum = CatalogAlbum(
                                    id = "",
                                    title = album.name,
                                    artist = artistName,
                                    coverUrl = album.artworkUri,
                                    trackCount = album.songCount
                                )
                                DiscoverMediaCard(
                                    title = album.name,
                                    subtitle = "${album.songCount} canciones",
                                    artworkUri = album.artworkUri,
                                    cardWidth = 150.dp,
                                    imageSize = 134.dp,
                                    onClick = { onSelectAlbum(catalogAlbum) },
                                    topEndBadge = {
                                        Box(
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.BookmarkAdded,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (localSongs.isNotEmpty()) {
                    items(
                        items = localSongs.take(5),
                        key = { "local-song-${it.id}" }
                    ) { song ->
                        val isPlaying = currentItem is PlayableItem.Local && currentItem.song.id == song.id
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                            DiscoverTrackListItem(
                                track = song,
                                onPlay = { onPlayLocalSong(song) },
                                status = if (song.isRemote) ItemLibraryStatus.SAVED_REMOTE else ItemLibraryStatus.DOWNLOADED,
                                highlighted = isPlaying,
                                subtitle = if (song.isRemote) "${song.album} • Guardado" else "${song.album} • Descargado"
                            )
                        }
                    }
                }
            }

            // Sección 2: Álbumes del artista (Catálogo online)
            if (albums.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Álbumes del artista (${albums.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(
                            items = albums,
                            key = { index, it -> "online-alb-${it.id.ifEmpty { it.title }}-$index" }
                        ) { _, album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { onSelectAlbum(album) },
                                onSave = { onSaveAlbum(album) }
                            )
                        }
                    }
                }
            }

            // Sección 3: Canciones populares (Top tracks streaming)
            if (candidates.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Canciones populares",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                itemsIndexed(
                    items = candidates,
                    key = { index, it -> "top-cand-${it.trackNumber}-${it.identity.artist}-${it.identity.title}-$index" }
                ) { _, candidate ->
                    val activeDownload = LocalDiscoverContext.current.activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { onPlayTrack(candidate) },
                            onDownload = { onDownloadTrack(candidate) },
                            activeDownload = activeDownload,
                            highlighted = isPlaying,
                            leading = {
                                val num = candidate.trackNumber.takeIf { it > 0 }
                                if (num != null) {
                                    Text(
                                        text = "$num",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
