package com.bestiapop.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.matchesItem
import com.bestiapop.android.data.model.matchesSong
import com.bestiapop.android.domain.util.TrackMatchKeys

/** L2: whether a matched (local or remote) track is the current playable. */
fun isMatchedTrackPlaying(
    localSong: Song?,
    artist: String,
    title: String,
    currentItem: PlayableItem?
): Boolean {
    if (currentItem == null) return false
    if (localSong != null && currentItem is PlayableItem.Local) {
        return currentItem.song.uriString == localSong.uriString ||
            currentItem.mediaId == localSong.uriString
    }
    val currentKey = TrackMatchKeys.matchKey(currentItem.artist, currentItem.title)
    val matchKey = TrackMatchKeys.matchKey(artist, title)
    return currentKey.isNotEmpty() && currentKey == matchKey
}

fun isCurrentPlaying(current: PlayableItem?, song: Song): Boolean =
    current?.matchesSong(song) == true

fun isCurrentPlaying(current: PlayableItem?, item: PlayableItem): Boolean {
    if (current == null) return false
    if (current.matchesItem(item)) return true
    return isMatchedTrackPlaying(
        localSong = (item as? PlayableItem.Local)?.song,
        artist = item.artist,
        title = item.title,
        currentItem = current
    )
}

fun isCurrentPlaying(
    current: PlayableItem?,
    localSong: Song?,
    artist: String,
    title: String
): Boolean {
    if (localSong != null && current?.matchesSong(localSong) == true) return true
    return isMatchedTrackPlaying(localSong, artist, title, current)
}

/**
 * L2: local → [SongListItem], remote → [RemoteTrackPlaceholderRow].
 * Keep [RemoteTrackPlaceholderRow] / [SongListItem] public for one-off layouts.
 * Flat [title]/[artist] overload stays as L1 step-down.
 */
@Composable
fun MatchedTrackRow(
    localSong: Song?,
    meta: TrackMeta,
    remoteBadge: String,
    isCurrentPlaying: Boolean,
    remote: PlayableItem.Remote?,
    download: ActiveDownload? = null,
    onPlayAt: () -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: ((String) -> Unit)? = null,
    onCancelDownload: ((String) -> Unit)? = null,
    queueActions: SongQueueActions,
    leadingIcon: ImageVector = Icons.Default.PlayArrow,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    onEditMetadata: ((Song) -> Unit)? = null,
    onIdentify: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null
) {
    val local = localSong
    if (local != null) {
        SongListItem(
            song = local,
            isCurrentPlaying = isCurrentPlaying,
            onClick = onPlayAt,
            onPlayNext = { queueActions.onPlayNext(local) },
            onAddToQueue = { queueActions.onAddToQueue(local) },
            onStartRadio = { queueActions.onStartRadio(local) },
            onAddToPlaylist = onAddToPlaylist?.let { cb -> { cb(local) } },
            onEditMetadata = onEditMetadata?.let { cb -> { cb(local) } },
            onIdentify = onIdentify?.let { cb -> { cb(local) } },
            onDelete = onDelete?.let { cb -> { cb(local) } }
        )
    } else if (remote != null) {
        RemoteTrackPlaceholderRow(
            title = meta.title,
            artist = meta.artist,
            badge = remoteBadge,
            leadingIcon = leadingIcon,
            highlighted = isCurrentPlaying,
            onClick = onPlayAt,
            onDownload = { onDownloadRemote(remote) },
            download = download,
            onRetry = download?.id?.let { id -> onRetryDownload?.let { retry -> { retry(id) } } },
            onCancelDownload = download?.id?.let { id ->
                onCancelDownload?.let { cancel -> { cancel(id) } }
            }
        )
    }
}

/** L1: flat title/artist when a call site lacks a [TrackMeta] wrapper. */
@Composable
fun MatchedTrackRow(
    localSong: Song?,
    title: String,
    artist: String,
    remoteBadge: String,
    isCurrentPlaying: Boolean,
    remote: PlayableItem.Remote?,
    download: ActiveDownload? = null,
    onPlayAt: () -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onRetryDownload: ((String) -> Unit)? = null,
    queueActions: SongQueueActions,
    leadingIcon: ImageVector = Icons.Default.PlayArrow,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    onEditMetadata: ((Song) -> Unit)? = null,
    onIdentify: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null
) = MatchedTrackRow(
    localSong = localSong,
    meta = TrackIdentity(title = title, artist = artist),
    remoteBadge = remoteBadge,
    isCurrentPlaying = isCurrentPlaying,
    remote = remote,
    download = download,
    onPlayAt = onPlayAt,
    onDownloadRemote = onDownloadRemote,
    onRetryDownload = onRetryDownload,
    queueActions = queueActions,
    leadingIcon = leadingIcon,
    onAddToPlaylist = onAddToPlaylist,
    onEditMetadata = onEditMetadata,
    onIdentify = onIdentify,
    onDelete = onDelete
)
