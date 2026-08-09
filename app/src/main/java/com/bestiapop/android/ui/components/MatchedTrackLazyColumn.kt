package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song

data class MatchedTrackListItem(
    val localSong: Song?,
    val title: String,
    val artist: String,
    val remote: PlayableItem.Remote?,
    val key: String
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
    queueActions: SongQueueActions,
    modifier: Modifier = Modifier,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    onEditMetadata: ((Song) -> Unit)? = null,
    onIdentify: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(
            items = matches,
            key = { _, match -> match.key }
        ) { index, match ->
            MatchedTrackRow(
                localSong = match.localSong,
                title = match.title,
                artist = match.artist,
                remoteBadge = remoteBadge,
                isCurrentPlaying = isCurrentPlaying(
                    currentItem,
                    match.localSong,
                    match.artist,
                    match.title
                ),
                remote = match.remote,
                download = activeDownloads.findByTrack(match.artist, match.title),
                onPlayAt = { onPlayAt(index) },
                onDownloadRemote = onDownloadRemote,
                onRetryDownload = onRetryDownload,
                queueActions = queueActions,
                onAddToPlaylist = onAddToPlaylist,
                onEditMetadata = onEditMetadata,
                onIdentify = onIdentify,
                onDelete = onDelete
            )
        }
    }
}
