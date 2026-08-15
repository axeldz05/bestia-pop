package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta

data class MatchedTrackListItem(
    val localSong: Song?,
    val meta: TrackMeta,
    val remote: PlayableItem.Remote?,
    val key: String
)

fun MatchedRemoteTrack.toListItem(index: Int): MatchedTrackListItem {
    val playable = toPlayableItem()
    return MatchedTrackListItem(
        localSong = localSong,
        meta = this,
        remote = if (localSong == null) playable as? PlayableItem.Remote else null,
        key = "${index}|${recordingMbid ?: title}|${artist}|${localSong?.id}"
    )
}

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
    onDelete: ((Song) -> Unit)? = null
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(
            items = matches,
            key = { _, match -> match.key }
        ) { index, match ->
            MatchedTrackRow(
                localSong = match.localSong,
                meta = match.meta,
                remoteBadge = remoteBadge,
                isCurrentPlaying = isCurrentPlaying(
                    currentItem,
                    match.localSong,
                    match.meta.artist,
                    match.meta.title
                ),
                remote = match.remote,
                download = activeDownloads.findUiDownloadByTrack(
                    match.meta.artist,
                    match.meta.title
                ),
                onPlayAt = { onPlayAt(index) },
                onDownloadRemote = onDownloadRemote,
                onRetryDownload = onRetryDownload,
                onCancelDownload = onCancelDownload,
                queueActions = queueActions,
                onAddToPlaylist = onAddToPlaylist,
                onEditMetadata = onEditMetadata,
                onEditLyrics = onEditLyrics,
                onIdentify = onIdentify,
                onDelete = onDelete
            )
        }
    }
}
