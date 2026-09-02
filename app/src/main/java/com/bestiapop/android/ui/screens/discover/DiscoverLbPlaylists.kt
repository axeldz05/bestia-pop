package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.MatchedCfRecommendations
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toDiscoverOrigin
import com.bestiapop.android.ui.components.LabeledPlayShuffleButtons
import com.bestiapop.android.ui.components.MatchedTrackLazyColumn
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.SongQueueActions
import com.bestiapop.android.ui.components.toListItem
import com.bestiapop.android.ui.screens.PlaylistSurfaceCard
import com.bestiapop.android.ui.state.LoadPhase
import com.bestiapop.android.ui.state.LoadableUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun matchedStreamCountLabel(matched: Int, stream: Int): String = when {
    matched > 0 && stream > 0 -> "$matched en biblioteca · $stream para escuchar online"
    matched > 0 -> "$matched en tu biblioteca"
    stream > 0 -> "$stream canciones para escuchar online"
    else -> "Sin canciones"
}

@Composable
internal fun MatchedPlaylistDetailScaffold(
    title: String,
    onBack: () -> Unit,
    loading: Boolean,
    errorMessage: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            ScreenBackHeader(title = title, onBack = onBack)
            Spacer(modifier = Modifier.height(12.dp))
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                errorMessage != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> content()
            }
        }
    }
}

@Composable
internal fun CfRecommendationsCardItem(
    matched: MatchedCfRecommendations,
    onClick: () -> Unit
) {
    val lastUpdatedLabel = matched.payload.lastUpdatedEpochSec?.let { epochSec ->
        val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        " · actualizado ${formatter.format(Date(epochSec * 1000L))}"
    }.orEmpty()

    PlaylistSurfaceCard(
        title = "Recomendados para vos",
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Recommend,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        lines = {
            Text(
                text = matchedStreamCountLabel(matched.matchedCount, matched.streamCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${matched.totalCount} tracks · CF$lastUpdatedLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/**
 * Level 2: Bundled callbacks for track playback and download actions across matched playlists.
 */
data class DiscoverMatchedTrackActions(
    val currentItem: PlayableItem?,
    val activeDownloads: List<ActiveDownload>,
    val onPlayAt: (Int) -> Unit,
    val onDownloadRemote: (PlayableItem.Remote) -> Unit,
    val onRetryDownload: (String) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val queueActions: SongQueueActions,
    val onEditLyrics: (Song) -> Unit
)

/**
 * Level 2: Shared content layout for matched playlist and CF recommendation detail screens.
 */
@Composable
internal fun MatchedPlaylistContent(
    matchedCount: Int,
    streamCount: Int,
    matches: List<MatchedRemoteTrack>,
    remoteBadge: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    actions: DiscoverMatchedTrackActions,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    if (matches.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        headerContent?.invoke(this)

        Text(
            text = matchedStreamCountLabel(matchedCount, streamCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LabeledPlayShuffleButtons(
            onPlay = onPlay,
            onShuffle = onShuffle,
            enabled = matches.isNotEmpty()
        )

        Spacer(modifier = Modifier.height(16.dp))

        MatchedTrackLazyColumn(
            matches = matches.mapIndexed { index, match -> match.toListItem(index) },
            remoteBadge = remoteBadge,
            currentItem = actions.currentItem,
            activeDownloads = actions.activeDownloads,
            onPlayAt = actions.onPlayAt,
            onDownloadRemote = actions.onDownloadRemote,
            onRetryDownload = actions.onRetryDownload,
            onCancelDownload = actions.onCancelDownload,
            queueActions = actions.queueActions,
            onEditLyrics = actions.onEditLyrics
        )
    }
}

/** Level 2: CF recommendations detail screen with bundled actions. */
@Composable
internal fun CfRecommendationsDetailScreen(
    state: LoadableUiState<MatchedCfRecommendations?>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    actions: DiscoverMatchedTrackActions
) {
    val matched = state.data
    MatchedPlaylistDetailScaffold(
        title = "Recomendados",
        onBack = onBack,
        loading = matched == null && (state.phase is LoadPhase.Loading || state.phase is LoadPhase.Idle),
        errorMessage = state.errorMessage
    ) {
        MatchedPlaylistContent(
            matchedCount = matched?.matchedCount ?: 0,
            streamCount = matched?.streamCount ?: 0,
            matches = matched?.matches.orEmpty(),
            remoteBadge = "Stream",
            onPlay = onPlay,
            onShuffle = onShuffle,
            actions = actions,
            emptyMessage = "Aún no hay recomendaciones CF para tu cuenta."
        )
    }
}

/** Level 1: CF recommendations detail screen with individual callbacks (continuous granularity). */
@Composable
internal fun CfRecommendationsDetailScreen(
    state: LoadableUiState<MatchedCfRecommendations?>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAt: (Int) -> Unit,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    queueActions: SongQueueActions,
    onEditLyrics: (Song) -> Unit
) = CfRecommendationsDetailScreen(
    state = state,
    onBack = onBack,
    onPlay = onPlay,
    onShuffle = onShuffle,
    actions = DiscoverMatchedTrackActions(
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        onPlayAt = onPlayAt,
        onDownloadRemote = onDownloadRemote,
        onRetryDownload = onRetryDownload,
        onCancelDownload = onCancelDownload,
        queueActions = queueActions,
        onEditLyrics = onEditLyrics
    )
)

@Composable
internal fun LbPlaylistCardItem(
    playlist: LbPlaylistSummary,
    onClick: () -> Unit
) {
    PlaylistSurfaceCard(
        title = playlist.title,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        lines = {
            if (!playlist.description.isNullOrBlank()) {
                Text(
                    text = playlist.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (playlist.trackCount > 0) {
                    "${playlist.trackCount} tracks · ListenBrainz"
                } else {
                    "ListenBrainz"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
            )
        }
    )
}

/** Level 2: ListenBrainz playlist detail screen with bundled actions. */
@Composable
internal fun LbPlaylistDetailScreen(
    state: LoadableUiState<MatchedLbPlaylist?>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSaveAsLocal: () -> Unit,
    onImportWithDownloads: () -> Unit,
    actions: DiscoverMatchedTrackActions
) {
    val matchedPlaylist = state.data
    MatchedPlaylistDetailScaffold(
        title = matchedPlaylist?.detail?.summary?.title ?: "Para Ti",
        onBack = onBack,
        loading = state.phase is LoadPhase.Loading || state.phase is LoadPhase.Idle,
        errorMessage = state.errorMessage
    ) {
        val matched = matchedPlaylist
        if (matched == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No se pudo cargar la playlist",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@MatchedPlaylistDetailScaffold
        }

        val description = matched.detail.summary.description
        val hasMatched = matched.matchedCount > 0
        val hasUnmatched = matched.streamCount > 0

        MatchedPlaylistContent(
            matchedCount = matched.matchedCount,
            streamCount = matched.streamCount,
            matches = matched.matches,
            remoteBadge = "No en biblioteca · stream",
            onPlay = onPlay,
            onShuffle = onShuffle,
            actions = actions,
            emptyMessage = "Esta playlist no tiene tracks",
            headerContent = {
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (hasMatched || hasUnmatched) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSaveAsLocal,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Guardar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (hasUnmatched) {
                            OutlinedButton(
                                onClick = onImportWithDownloads,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Descargar faltantes",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

/** Level 1: ListenBrainz playlist detail screen with individual callbacks (continuous granularity). */
@Composable
internal fun LbPlaylistDetailScreen(
    state: LoadableUiState<MatchedLbPlaylist?>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onSaveAsLocal: () -> Unit,
    onImportWithDownloads: () -> Unit,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    queueActions: SongQueueActions,
    onEditLyrics: (Song) -> Unit
) = LbPlaylistDetailScreen(
    state = state,
    onBack = onBack,
    onPlay = onPlay,
    onShuffle = onShuffle,
    onSaveAsLocal = onSaveAsLocal,
    onImportWithDownloads = onImportWithDownloads,
    actions = DiscoverMatchedTrackActions(
        currentItem = currentItem,
        activeDownloads = activeDownloads,
        onPlayAt = onPlayAt,
        onDownloadRemote = onDownloadRemote,
        onRetryDownload = onRetryDownload,
        onCancelDownload = onCancelDownload,
        queueActions = queueActions,
        onEditLyrics = onEditLyrics
    )
)

/**
 * Level 2: Host for displaying either ListenBrainz Discover or CF Recommendations detail screen,
 * collapsing duplicate parameter plumbing while keeping underlying detail screens accessible.
 */
@Composable
fun DiscoverPlaylistDetailHost(
    selectedLbPlaylistMbid: String?,
    cfDetailOpen: Boolean,
    lbPlaylistDetail: LoadableUiState<MatchedLbPlaylist?>,
    cfRecommendationsState: LoadableUiState<MatchedCfRecommendations?>,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    songActions: SongQueueActions,
    onEditLyrics: (Song) -> Unit,
    onBack: () -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPlayMatched: (List<PlayableItem>, DiscoverPlaybackOrigin, Int) -> Unit,
    onShuffleMatched: (List<PlayableItem>, DiscoverPlaybackOrigin) -> Unit,
    onSaveLbAsLocal: ((Long) -> Unit) -> Unit,
    onOpenLocalPlaylist: (Long) -> Unit,
    onImportLbWithDownloads: () -> Unit
) {
    val makeActions: ((Int) -> Unit) -> DiscoverMatchedTrackActions = { onPlayAt ->
        DiscoverMatchedTrackActions(
            currentItem = currentItem,
            activeDownloads = activeDownloads,
            onPlayAt = onPlayAt,
            onDownloadRemote = onDownloadRemote,
            onRetryDownload = onRetryDownload,
            onCancelDownload = onCancelDownload,
            queueActions = songActions,
            onEditLyrics = onEditLyrics
        )
    }

    if (selectedLbPlaylistMbid != null) {
        val matched = lbPlaylistDetail.data
        LbPlaylistDetailScreen(
            state = lbPlaylistDetail,
            onBack = onBack,
            onPlay = {
                if (matched != null) {
                    onPlayMatched(matched.toPlayableItems(), matched.toDiscoverOrigin(), 0)
                }
            },
            onShuffle = {
                if (matched != null) {
                    onShuffleMatched(matched.toPlayableItems(), matched.toDiscoverOrigin())
                }
            },
            onSaveAsLocal = { onSaveLbAsLocal(onOpenLocalPlaylist) },
            onImportWithDownloads = onImportLbWithDownloads,
            actions = makeActions { index ->
                if (matched != null) {
                    onPlayMatched(matched.toPlayableItems(), matched.toDiscoverOrigin(), index)
                }
            }
        )
    } else if (cfDetailOpen) {
        val matched = cfRecommendationsState.data
        CfRecommendationsDetailScreen(
            state = cfRecommendationsState,
            onBack = onBack,
            onPlay = {
                if (matched != null) {
                    onPlayMatched(matched.toPlayableItems(), DiscoverPlaybackOrigin.CfRecommendations, 0)
                }
            },
            onShuffle = {
                if (matched != null) {
                    onShuffleMatched(matched.toPlayableItems(), DiscoverPlaybackOrigin.CfRecommendations)
                }
            },
            actions = makeActions { index ->
                if (matched != null) {
                    onPlayMatched(matched.toPlayableItems(), DiscoverPlaybackOrigin.CfRecommendations, index)
                }
            }
        )
    }
}


