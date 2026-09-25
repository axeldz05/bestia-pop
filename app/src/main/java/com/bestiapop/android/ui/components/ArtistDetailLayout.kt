package com.bestiapop.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.screens.discover.DiscoverAlbumCard
import com.bestiapop.android.ui.screens.discover.DiscoverCarouselRow
import com.bestiapop.android.ui.screens.discover.DiscoverMediaCard
import com.bestiapop.android.ui.screens.discover.DiscoverTrackListItem
import com.bestiapop.android.ui.state.ItemLibraryStatus

/**
 * Level 2: Shared stack frame bundling user interaction callbacks for artist detail views.
 */
@Immutable
data class ArtistDetailActions(
    val onBack: () -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onStartRadio: () -> Unit,
    val onSelectLocalAlbum: (Album) -> Unit = {},
    val onSelectOnlineAlbum: (CatalogAlbum) -> Unit = {},
    val onSaveOnlineAlbum: (CatalogAlbum) -> Unit = {},
    val onPlayTrack: (CatalogTrackCandidate) -> Unit = {},
    val onDownloadTrack: (CatalogTrackCandidate) -> Unit = {},
    val onPlayLocalSong: (Song) -> Unit = {},
    val getAlbumStatus: (CatalogAlbum) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
)

/**
 * Level 2: Centralized layout for Artist details across Library and Discover, integrating local albums,
 * online studio albums, singles/EPs, appearances, and top tracks with continuous granularity.
 */
@Composable
fun ArtistDetailLayout(
    artistName: String,
    actions: ArtistDetailActions,
    modifier: Modifier = Modifier,
    summary: String = "",
    displayCoverUrl: String? = null,
    localAlbums: List<Album> = emptyList(),
    onlineAlbums: List<CatalogAlbum> = emptyList(),
    onlineSinglesAndEps: List<CatalogAlbum> = emptyList(),
    localAppearedOn: List<Album> = emptyList(),
    onlineAppearedOn: List<CatalogAlbum> = emptyList(),
    onlineTopTracks: List<CatalogTrackCandidate> = emptyList(),
    currentItem: PlayableItem? = null,
    activeDownloads: List<ActiveDownload> = emptyList(),
    isLoading: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    playEnabled: Boolean = localAlbums.isNotEmpty() || onlineTopTracks.isNotEmpty(),
    shuffleEnabled: Boolean = playEnabled,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = artistName,
            onBack = actions.onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            trailing = { headerTrailing?.invoke(this) },
        )

        if (isLoading && localAlbums.isEmpty() && onlineAlbums.isEmpty() &&
            onlineSinglesAndEps.isEmpty() && onlineAppearedOn.isEmpty() && onlineTopTracks.isEmpty()
        ) {
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
            // Artist Hero
            item(key = "artist-hero-header") {
                ArtistDetailHero(
                    artistName = artistName,
                    summary = summary,
                    coverUrl = displayCoverUrl,
                    playEnabled = playEnabled,
                    shuffleEnabled = shuffleEnabled,
                    radioEnabled = true,
                    onPlay = actions.onPlayAll,
                    onShuffle = actions.onShuffle,
                    onStartRadio = actions.onStartRadio,
                )
            }

            // Section 1: En tu biblioteca
            if (localAlbums.isNotEmpty()) {
                item(key = "local-section-header") {
                    Text(
                        text = "En tu biblioteca",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item(key = "local-albums-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = localAlbums,
                            key = { "local-alb-${it.name}" },
                        ) { album ->
                            DiscoverMediaCard(
                                title = album.displayName,
                                subtitle = "${album.songCount} canciones",
                                artworkUri = album.artworkUri,
                                cardWidth = 150.dp,
                                imageSize = 134.dp,
                                onClick = { actions.onSelectLocalAlbum(album) },
                                topEndBadge = {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Section 2: Álbumes del artista (Catálogo online)
            if (onlineAlbums.isNotEmpty()) {
                item(key = "online-albums-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Álbumes del artista (${onlineAlbums.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item(key = "online-albums-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = onlineAlbums,
                            key = { index, it -> "online-alb-${it.id.ifEmpty { it.title }}-$index" },
                        ) { _, album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { actions.onSelectOnlineAlbum(album) },
                                onSave = { actions.onSaveOnlineAlbum(album) },
                                status = actions.getAlbumStatus(album),
                            )
                        }
                    }
                }
            }

            // Section 3: Sencillos y EPs
            if (onlineSinglesAndEps.isNotEmpty()) {
                item(key = "online-singles-eps-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sencillos y EPs (${onlineSinglesAndEps.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item(key = "online-singles-eps-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = onlineSinglesAndEps,
                            key = { index, it -> "online-single-${it.id.ifEmpty { it.title }}-$index" },
                        ) { _, album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { actions.onSelectOnlineAlbum(album) },
                                onSave = { actions.onSaveOnlineAlbum(album) },
                                status = actions.getAlbumStatus(album),
                            )
                        }
                    }
                }
            }

            // Section 4: Apareció en (Colaboraciones y participaciones)
            val totalAppearedOn = localAppearedOn.size + onlineAppearedOn.size
            if (totalAppearedOn > 0) {
                item(key = "appeared-on-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Apareció en ($totalAppearedOn)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item(key = "appeared-on-carousel") {
                    DiscoverCarouselRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = localAppearedOn,
                            key = { "local-app-${it.name}-${it.artist}" },
                        ) { album ->
                            DiscoverMediaCard(
                                title = album.displayName,
                                subtitle = album.artist,
                                artworkUri = album.artworkUri,
                                cardWidth = 150.dp,
                                imageSize = 134.dp,
                                onClick = { actions.onSelectLocalAlbum(album) },
                                topEndBadge = {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                            )
                        }

                        itemsIndexed(
                            items = onlineAppearedOn,
                            key = { index, it -> "online-app-${it.id.ifEmpty { it.title }}-$index" },
                        ) { _, album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = { actions.onSelectOnlineAlbum(album) },
                                onSave = { actions.onSaveOnlineAlbum(album) },
                                status = actions.getAlbumStatus(album),
                            )
                        }
                    }
                }
            }

            // Section 5: Canciones populares (Top tracks online)
            if (onlineTopTracks.isNotEmpty()) {
                item(key = "online-top-tracks-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Canciones populares",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                itemsIndexed(
                    items = onlineTopTracks,
                    key = { index, it -> "online-top-${it.trackNumber}-${it.identity.artist}-${it.identity.title}-$index" },
                ) { _, candidate ->
                    val activeDownload = activeDownloads.findUiDownloadByTrack(candidate.artist, candidate.title)
                    val isPlaying = isCurrentPlaying(currentItem, candidate.identity.artist, candidate.identity.title)
                    val trackStatus = actions.getTrackStatus(candidate)
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = candidate,
                            onPlay = { actions.onPlayTrack(candidate) },
                            onDownload = { actions.onDownloadTrack(candidate) },
                            activeDownload = activeDownload,
                            status = trackStatus,
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
}

/**
 * Level 1: Low-level primitive overload offering individual parameters and slots (continuous granularity).
 */
@Composable
fun ArtistDetailLayout(
    artistName: String,
    summary: String,
    displayCoverUrl: String?,
    localAlbums: List<Album>,
    onlineAlbums: List<CatalogAlbum>,
    onlineTopTracks: List<CatalogTrackCandidate>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectLocalAlbum: (Album) -> Unit = {},
    onSelectOnlineAlbum: (CatalogAlbum) -> Unit = {},
    onSaveOnlineAlbum: (CatalogAlbum) -> Unit = {},
    onPlayTrack: (CatalogTrackCandidate) -> Unit = {},
    onDownloadTrack: (CatalogTrackCandidate) -> Unit = {},
    onPlayLocalSong: (Song) -> Unit = {},
    getAlbumStatus: (CatalogAlbum) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    localAppearedOn: List<Album> = emptyList(),
    onlineSinglesAndEps: List<CatalogAlbum> = emptyList(),
    onlineAppearedOn: List<CatalogAlbum> = emptyList(),
    isLoading: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    playEnabled: Boolean = localAlbums.isNotEmpty() || onlineTopTracks.isNotEmpty(),
    shuffleEnabled: Boolean = playEnabled,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
) = ArtistDetailLayout(
    artistName = artistName,
    actions =
        ArtistDetailActions(
            onBack = onBack,
            onPlayAll = onPlayAll,
            onShuffle = onShuffle,
            onStartRadio = onStartRadio,
            onSelectLocalAlbum = onSelectLocalAlbum,
            onSelectOnlineAlbum = onSelectOnlineAlbum,
            onSaveOnlineAlbum = onSaveOnlineAlbum,
            onPlayTrack = onPlayTrack,
            onDownloadTrack = onDownloadTrack,
            onPlayLocalSong = onPlayLocalSong,
            getAlbumStatus = getAlbumStatus,
            getTrackStatus = getTrackStatus,
        ),
    modifier = modifier,
    summary = summary,
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
    listState = listState,
    playEnabled = playEnabled,
    shuffleEnabled = shuffleEnabled,
    headerTrailing = headerTrailing,
)

/**
 * Backward compatibility alias delegating to [ArtistDetailLayout].
 */
@Composable
fun ArtistDetailContent(
    artistName: String,
    summary: String,
    displayCoverUrl: String?,
    localAlbums: List<Album>,
    onlineAlbums: List<CatalogAlbum>,
    onlineTopTracks: List<CatalogTrackCandidate>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit,
    onSelectLocalAlbum: (Album) -> Unit,
    onSelectOnlineAlbum: (CatalogAlbum) -> Unit,
    onSaveOnlineAlbum: (CatalogAlbum) -> Unit,
    onPlayTrack: (CatalogTrackCandidate) -> Unit,
    onDownloadTrack: (CatalogTrackCandidate) -> Unit,
    getAlbumStatus: (CatalogAlbum) -> ItemLibraryStatus,
    getTrackStatus: (CatalogTrackCandidate) -> ItemLibraryStatus,
    modifier: Modifier = Modifier,
    localAppearedOn: List<Album> = emptyList(),
    onlineSinglesAndEps: List<CatalogAlbum> = emptyList(),
    onlineAppearedOn: List<CatalogAlbum> = emptyList(),
    isLoading: Boolean = false,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
) = ArtistDetailLayout(
    artistName = artistName,
    summary = summary,
    displayCoverUrl = displayCoverUrl,
    localAlbums = localAlbums,
    onlineAlbums = onlineAlbums,
    onlineTopTracks = onlineTopTracks,
    currentItem = currentItem,
    activeDownloads = activeDownloads,
    onBack = onBack,
    onPlayAll = onPlayAll,
    onShuffle = onShuffle,
    onStartRadio = onStartRadio,
    onSelectLocalAlbum = onSelectLocalAlbum,
    onSelectOnlineAlbum = onSelectOnlineAlbum,
    onSaveOnlineAlbum = onSaveOnlineAlbum,
    onPlayTrack = onPlayTrack,
    onDownloadTrack = onDownloadTrack,
    getAlbumStatus = getAlbumStatus,
    getTrackStatus = getTrackStatus,
    modifier = modifier,
    localAppearedOn = localAppearedOn,
    onlineSinglesAndEps = onlineSinglesAndEps,
    onlineAppearedOn = onlineAppearedOn,
    isLoading = isLoading,
    headerTrailing = headerTrailing,
)
