package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta

data class MatchedTrackListItem(
    val localSong: Song?,
    val meta: TrackMeta,
    val remote: PlayableItem.Remote?,
    val key: String,
)

fun MatchedRemoteTrack.toListItem(index: Int): MatchedTrackListItem {
    val playable = toPlayableItem()
    return MatchedTrackListItem(
        localSong = localSong,
        meta = this,
        remote = if (localSong == null) playable as? PlayableItem.Remote else null,
        key = "$index|${recordingMbid ?: title}|$artist|${localSong?.id}",
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
    val onEditLyrics: (Song) -> Unit,
    val songActions: SongItemActions =
        SongItemActions.from(
            queueActions = queueActions,
            onEditLyrics = onEditLyrics,
        ),
    val onSwipeRemote: ((PlayableItem.Remote) -> Unit)? = null,
)

/**
 * Level 2: Matched tracks lazy column accepting bundled [DiscoverMatchedTrackActions].
 */
@Composable
fun MatchedTrackLazyColumn(
    matches: List<MatchedTrackListItem>,
    remoteBadge: String,
    actions: DiscoverMatchedTrackActions,
    modifier: Modifier = Modifier,
    headerContent: (@Composable () -> Unit)? = null,
) = MatchedTrackLazyColumn(
    matches = matches,
    remoteBadge = remoteBadge,
    currentItem = actions.currentItem,
    activeDownloads = actions.activeDownloads,
    onPlayAt = actions.onPlayAt,
    onDownloadRemote = actions.onDownloadRemote,
    onRetryDownload = actions.onRetryDownload,
    onCancelDownload = actions.onCancelDownload,
    songActions = actions.songActions,
    modifier = modifier,
    onSwipeRemote = actions.onSwipeRemote,
    headerContent = headerContent,
)

@Composable
fun MatchedTrackLazyColumn(
    matches: List<MatchedTrackListItem>,
    remoteBadge: String,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onPlayAt: (Int) -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    songActions: SongItemActions,
    modifier: Modifier = Modifier,
    onSwipeRemote: ((PlayableItem.Remote) -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (headerContent != null) {
            item(key = "matched-tracks-header") {
                headerContent()
            }
        }
        itemsIndexed(
            items = matches,
            key = { _, match -> match.key },
        ) { index, match ->
            MatchedTrackRow(
                localSong = match.localSong,
                meta = match.meta,
                remoteBadge = remoteBadge,
                isCurrentPlaying =
                    isCurrentPlaying(
                        currentItem,
                        match.localSong,
                        match.meta.artist,
                        match.meta.title,
                    ),
                remote = match.remote,
                download =
                    activeDownloads.findUiDownloadByTrack(
                        match.meta.artist,
                        match.meta.title,
                    ),
                onPlayAt = { onPlayAt(index) },
                onDownloadRemote = onDownloadRemote,
                onRetryDownload = onRetryDownload,
                onCancelDownload = onCancelDownload,
                songActions = songActions,
                onSwipeRemote = onSwipeRemote,
            )
        }
    }
}

/**
 * Level 1: Individual primitive callbacks overload delegating to bundled [SongItemActions].
 */
@Composable
fun MatchedTrackLazyColumn(
    matches: List<MatchedTrackListItem>,
    remoteBadge: String,
    currentItem: PlayableItem?,
    activeDownloads: List<ActiveDownload>,
    onPlayAt: (Int) -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    queueActions: SongQueueActions,
    modifier: Modifier = Modifier,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    onEditMetadata: ((Song) -> Unit)? = null,
    onEditLyrics: ((Song) -> Unit)? = null,
    onIdentify: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null,
    onSwipeRemote: ((PlayableItem.Remote) -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
) = MatchedTrackLazyColumn(
    matches = matches,
    remoteBadge = remoteBadge,
    currentItem = currentItem,
    activeDownloads = activeDownloads,
    onPlayAt = onPlayAt,
    onDownloadRemote = onDownloadRemote,
    onRetryDownload = onRetryDownload,
    onCancelDownload = onCancelDownload,
    songActions =
        SongItemActions.from(
            queueActions = queueActions,
            onAddToPlaylist = onAddToPlaylist,
            onEditMetadata = onEditMetadata,
            onEditLyrics = onEditLyrics,
            onIdentify = onIdentify,
            onDelete = onDelete,
        ),
    modifier = modifier,
    onSwipeRemote = onSwipeRemote,
    headerContent = headerContent,
)
