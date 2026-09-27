package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.preferences.DiscoverSourcePreference
import com.bestiapop.android.domain.usecase.DiscoverFeed
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.RelatedArtistItem
import com.bestiapop.android.domain.usecase.RelatedTrackItem
import com.bestiapop.android.domain.usecase.TopRelatedFeed
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.ItemSwipeBox
import com.bestiapop.android.ui.components.MediaCardDownloadSpinner
import com.bestiapop.android.ui.components.ResponsiveActionRow
import com.bestiapop.android.ui.components.ResponsiveFilterChip
import com.bestiapop.android.ui.components.artistAlbumLabel
import com.bestiapop.android.ui.components.isAlbumDownloading

/**
 * Level 2: Actions for the top related section (artists, albums, tracks).
 */
@Immutable
data class DiscoverTopRelatedActions(
    val onSelectArtist: (String) -> Unit = {},
    val onStartRadioForArtist: (String) -> Unit = {},
    val onSelectAlbum: (RelatedAlbumItem) -> Unit = {},
    val onStartRadioForAlbum: (RelatedAlbumItem) -> Unit = {},
    val onPlayTrack: (RelatedTrackItem) -> Unit = {},
    val onStartRadioForTrack: (RelatedTrackItem) -> Unit = {},
    val onRefresh: () -> Unit = {},
)

/**
 * Level 2: Actions for ListenBrainz discover playlists and CF recommendations.
 */
@Immutable
data class DiscoverListenBrainzActions(
    val onOpenPlaylist: (String) -> Unit = {},
    val onOpenCfRecommendations: () -> Unit = {},
)

/** Level 2: Home feed view using bundled actions. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    source: DiscoverSourcePreference = DiscoverSourcePreference.BOTH,
    onSourceChange: (DiscoverSourcePreference) -> Unit = {},
    topRelatedFeed: TopRelatedFeed = TopRelatedFeed(),
    isLoadingTopRelated: Boolean = false,
    topRelatedActions: DiscoverTopRelatedActions = DiscoverTopRelatedActions(),
    lbDiscoverPlaylists: List<LbPlaylistSummary> = emptyList(),
    cfRecommendations: MatchedCfRecommendations? = null,
    lbActions: DiscoverListenBrainzActions = DiscoverListenBrainzActions(),
    showLbSections: Boolean = false,
    actions: DiscoverCatalogActions,
    scrollState: ScrollState? = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
    modifier: Modifier = Modifier,
) {
    DiscoverHomeFeedView(
        feed = feed,
        isLoading = isLoading,
        onRefresh = onRefresh,
        source = source,
        onSourceChange = onSourceChange,
        topRelatedFeed = topRelatedFeed,
        isLoadingTopRelated = isLoadingTopRelated,
        topRelatedActions = topRelatedActions,
        lbDiscoverPlaylists = lbDiscoverPlaylists,
        cfRecommendations = cfRecommendations,
        lbActions = lbActions,
        showLbSections = showLbSections,
        onPlayTrack = actions.onPlayTrack,
        onPlayTrackInCollection = actions.onPlayTrackInCollection,
        onDownloadTrack = actions.onDownloadTrack,
        onSelectAlbum = actions.onSelectAlbum,
        onSaveAlbum = actions.onSaveAlbum,
        scrollState = scrollState,
        modifier = modifier,
    )
}

/** Level 1: Low-level primitive home feed view with individual callbacks. */
@Composable
fun DiscoverHomeFeedView(
    feed: DiscoverFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    source: DiscoverSourcePreference = DiscoverSourcePreference.BOTH,
    onSourceChange: (DiscoverSourcePreference) -> Unit = {},
    topRelatedFeed: TopRelatedFeed = TopRelatedFeed(),
    isLoadingTopRelated: Boolean = false,
    topRelatedActions: DiscoverTopRelatedActions = DiscoverTopRelatedActions(),
    lbDiscoverPlaylists: List<LbPlaylistSummary> = emptyList(),
    cfRecommendations: MatchedCfRecommendations? = null,
    lbActions: DiscoverListenBrainzActions = DiscoverListenBrainzActions(),
    showLbSections: Boolean = false,
    onPlayTrack: (OnlineCatalogTrack) -> Unit,
    onPlayTrackInCollection: (OnlineCatalogTrack, List<OnlineCatalogTrack>) -> Unit = { track, _ -> onPlayTrack(track) },
    onDownloadTrack: (OnlineCatalogTrack) -> Unit,
    onSelectAlbum: (CatalogAlbum) -> Unit,
    onSaveAlbum: (CatalogAlbum) -> Unit,
    scrollState: ScrollState? = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
    modifier: Modifier = Modifier,
) {
    val isFeedEmpty = feed.recommendedTracks.isEmpty() && feed.recommendedAlbums.isEmpty() && feed.chartTracks.isEmpty()

    val scrollModifier =
        if (scrollState != null) {
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 80.dp)
        } else {
            Modifier.fillMaxWidth()
        }

    Column(
        modifier =
            modifier
                .then(scrollModifier),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Section: Fuente del catálogo (Ambos / Deezer / ListenBrainz)
        ResponsiveActionRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Fuente:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ResponsiveFilterChip(
                selected = source == DiscoverSourcePreference.BOTH,
                onClick = { onSourceChange(DiscoverSourcePreference.BOTH) },
                label = "Ambos",
            )
            ResponsiveFilterChip(
                selected = source == DiscoverSourcePreference.DEEZER,
                onClick = { onSourceChange(DiscoverSourcePreference.DEEZER) },
                label = "Deezer",
            )
            ResponsiveFilterChip(
                selected = source == DiscoverSourcePreference.LISTENBRAINZ,
                onClick = { onSourceChange(DiscoverSourcePreference.LISTENBRAINZ) },
                label = "ListenBrainz",
            )
        }

        // Section: Buscar más relacionados (Local + ListenBrainz)
        DiscoverTopRelatedSection(
            feed = topRelatedFeed,
            isLoading = isLoadingTopRelated,
            actions = topRelatedActions,
        )

        // Section: Recomendados para vos (CF)
        if (showLbSections && cfRecommendations != null && cfRecommendations.matches.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                DiscoverSectionHeader(
                    title = "Recomendados",
                    badgeText = "ListenBrainz CF",
                    badgeColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                CfRecommendationsCardItem(
                    matched = cfRecommendations,
                    onClick = lbActions.onOpenCfRecommendations,
                )
            }
        }

        // Section: Playlists Para Ti (ListenBrainz Discover playlists)
        if (showLbSections && lbDiscoverPlaylists.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Playlists Para Ti",
                badgeText = "ListenBrainz",
                badgeColor = MaterialTheme.colorScheme.tertiary,
            ) {
                items(
                    items = lbDiscoverPlaylists,
                    key = { "feed-lb-${it.mbid}" },
                    contentType = { "feed-lb-playlist-card" },
                ) { playlist ->
                    Box(modifier = Modifier.width(280.dp)) {
                        LbPlaylistCardItem(
                            playlist = playlist,
                            onClick = { lbActions.onOpenPlaylist(playlist.mbid) },
                        )
                    }
                }
            }
        }

        // Section: Canciones recomendadas (Deezer flow/recommendations)
        if (feed.recommendedTracks.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Canciones recomendadas",
            ) {
                items(
                    items = feed.recommendedTracks,
                    key = { "rec-track-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                    contentType = { "rec-track-card" },
                ) { track ->
                    DiscoverTrackCard(
                        track = track,
                        onPlay = { onPlayTrackInCollection(track, feed.recommendedTracks) },
                        onDownload = { onDownloadTrack(track) },
                    )
                }
            }
        }

        // Section: Recommended Albums
        if (feed.recommendedAlbums.isNotEmpty()) {
            DiscoverFeedHorizontalSection(
                title = "Álbumes recomendados",
            ) {
                items(
                    items = feed.recommendedAlbums,
                    key = { "rec-album-${it.id.ifEmpty { "${it.artist}|${it.title}" }}" },
                    contentType = { "rec-album-card" },
                ) { album ->
                    DiscoverAlbumCard(
                        album = album,
                        onClick = { onSelectAlbum(album) },
                        onSave = { onSaveAlbum(album) },
                    )
                }
            }
        }

        // Section: Top Charts
        if (feed.chartTracks.isNotEmpty()) {
            Text(
                text = "Tendencias y Charts",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            feed.chartTracks.take(8).forEach { track ->
                key("chart-track-${track.id}") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DiscoverTrackListItem(
                            track = track,
                            onPlay = { onPlayTrackInCollection(track, feed.chartTracks) },
                            onDownload = { onDownloadTrack(track) },
                        )
                    }
                }
            }
        }

        if (isLoading && isFeedEmpty) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = "Buscando recomendaciones…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Level 2: Top related section using bundled [DiscoverTopRelatedActions]. */
@Composable
fun DiscoverTopRelatedSection(
    feed: TopRelatedFeed,
    isLoading: Boolean,
    actions: DiscoverTopRelatedActions,
    modifier: Modifier = Modifier,
) {
    DiscoverTopRelatedSection(
        feed = feed,
        isLoading = isLoading,
        onRefresh = actions.onRefresh,
        onSelectArtist = actions.onSelectArtist,
        onStartRadioForArtist = actions.onStartRadioForArtist,
        onSelectAlbum = actions.onSelectAlbum,
        onStartRadioForAlbum = actions.onStartRadioForAlbum,
        onPlayTrack = actions.onPlayTrack,
        onStartRadioForTrack = actions.onStartRadioForTrack,
        modifier = modifier,
    )
}

/** Level 1: Primitive top related section with individual callbacks. */
@Composable
fun DiscoverTopRelatedSection(
    feed: TopRelatedFeed,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSelectArtist: (String) -> Unit,
    onStartRadioForArtist: (String) -> Unit,
    onSelectAlbum: (RelatedAlbumItem) -> Unit,
    onStartRadioForAlbum: (RelatedAlbumItem) -> Unit = {},
    onPlayTrack: (RelatedTrackItem) -> Unit,
    onStartRadioForTrack: (RelatedTrackItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = "Buscar más relacionados",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Lo más escuchado localmente y en ListenBrainz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar relacionados",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tabs
            ResponsiveActionRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                ResponsiveFilterChip(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    label = "Artistas (${feed.topArtists.size})",
                )
                ResponsiveFilterChip(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    label = "Álbumes (${feed.topAlbums.size})",
                )
                ResponsiveFilterChip(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    label = "Canciones (${feed.topTracks.size})",
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTabIndex) {
                0 -> {
                    if (feed.topArtists.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando artistas más escuchados..." else "Sin estadísticas de artistas aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    } else {
                        DiscoverCarouselRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = feed.topArtists,
                                key = { "rel-artist-${it.name}" },
                                contentType = { "rel-artist-card" },
                            ) { artist ->
                                RelatedArtistCard(
                                    artist = artist,
                                    onSelect = { onSelectArtist(artist.name) },
                                    onRadio = { onStartRadioForArtist(artist.name) },
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (feed.topAlbums.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando álbumes más escuchados..." else "Sin estadísticas de álbumes aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    } else {
                        DiscoverCarouselRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = feed.topAlbums,
                                key = { "rel-album-${it.artist}|${it.title}" },
                                contentType = { "rel-album-card" },
                            ) { album ->
                                RelatedAlbumCard(
                                    album = album,
                                    onSelect = { onSelectAlbum(album) },
                                    onRadio = { onStartRadioForAlbum(album) },
                                )
                            }
                        }
                    }
                }

                2 -> {
                    if (feed.topTracks.isEmpty()) {
                        EmptyListHint(
                            text = if (isLoading) "Cargando canciones más escuchadas..." else "Sin estadísticas de canciones aún.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            feed.topTracks.take(8).forEach { track ->
                                RelatedTrackRow(
                                    track = track,
                                    onPlay = { onPlayTrack(track) },
                                    onRadio = { onStartRadioForTrack(track) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceBadge(
    source: String,
    modifier: Modifier = Modifier,
) {
    val (bgColor, textColor) =
        when {
            source.contains("+") -> {
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            }

            source.contains("ListenBrainz", ignoreCase = true) -> {
                MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
            }

            else -> {
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
        }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = textColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

/** Level 1: Unified radio creation button for top related candidates. */
@Composable
internal fun CandidateRadioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Radio",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Level 2: Related artist card for discovery sections. */
@Composable
internal fun RelatedArtistCard(
    artist: RelatedArtistItem,
    onSelect: () -> Unit,
    onRadio: () -> Unit,
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cardContent = @Composable {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier =
                Modifier
                    .width(136.dp)
                    .clickable(onClick = onSelect),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!artist.artworkUri.isNullOrBlank()) {
                        ArtworkThumbnail(
                            artworkUri = artist.artworkUri,
                            contentDescription = artist.name,
                            size = 64.dp,
                            cornerRadius = 32.dp,
                            modifier = Modifier.clip(CircleShape),
                        )
                    } else {
                        Text(
                            text =
                                artist.name
                                    .firstOrNull()
                                    ?.uppercase()
                                    .orEmpty(),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(2.dp))
                SourceBadge(source = artist.source)
                Spacer(modifier = Modifier.height(8.dp))
                CandidateRadioButton(
                    onClick = onRadio,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val resolvedSwipeAction =
        onSwipeAction ?: LocalDiscoverContext.current.swipeActions.onSwipeArtist
            ?.let { cb -> { cb(artist.name) } }
    if (resolvedSwipeAction != null) {
        ItemSwipeBox(
            onSwipeAction = resolvedSwipeAction,
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.width(136.dp),
        ) {
            cardContent()
        }
    } else {
        Box(modifier = modifier.width(136.dp)) {
            cardContent()
        }
    }
}

/** Level 2: Related album card with cover, metadata, and radio creation button. */
@Composable
internal fun RelatedAlbumCard(
    album: RelatedAlbumItem,
    onSelect: () -> Unit,
    onRadio: () -> Unit,
    activeDownloads: List<ActiveDownload>? = LocalDiscoverContext.current.activeDownloads,
    isDownloading: Boolean = activeDownloads?.isAlbumDownloading(album) ?: false,
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val resolvedSwipeAction =
        onSwipeAction ?: LocalDiscoverContext.current.swipeActions.onSwipeAlbum?.let { cb ->
            {
                cb(
                    CatalogAlbum(
                        id = "",
                        title = album.title,
                        artist = album.artist,
                        coverUrl = album.artworkUri,
                    ),
                )
            }
        }
    val cardContent = @Composable {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier =
                Modifier
                    .width(136.dp)
                    .clickable(onClick = onSelect),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(116.dp),
                ) {
                    ArtworkThumbnail(
                        artworkUri = album.artworkUri,
                        size = 116.dp,
                        cornerRadius = 8.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    SourceBadge(
                        source = album.source,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp),
                    )
                    if (isDownloading) {
                        MediaCardDownloadSpinner(
                            size = 28.dp,
                            indicatorSize = 14.dp,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                CandidateRadioButton(
                    onClick = onRadio,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (resolvedSwipeAction != null) {
        ItemSwipeBox(
            onSwipeAction = resolvedSwipeAction,
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.width(136.dp),
        ) {
            cardContent()
        }
    } else {
        Box(modifier = modifier.width(136.dp)) {
            cardContent()
        }
    }
}

/** Level 2: Related track row composing [DiscoverTrackListItem]. */
@Composable
internal fun RelatedTrackRow(
    track: RelatedTrackItem,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    DiscoverTrackListItem(
        track = track,
        onPlay = onPlay,
        subtitle = track.artistAlbumLabel(" · "),
        onSwipeAction = onSwipeAction,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SourceBadge(source = track.source)
                CandidateRadioButton(
                    onClick = onRadio,
                )
            }
        },
        modifier = modifier,
    )
}
