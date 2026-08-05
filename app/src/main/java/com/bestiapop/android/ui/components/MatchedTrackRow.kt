package com.bestiapop.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import com.bestiapop.android.ui.components.RemoteTrackPlaceholderRow

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
    val currentKey = MatchListenBrainzTracksUseCase.matchKey(currentItem.artist, currentItem.title)
    val matchKey = MatchListenBrainzTracksUseCase.matchKey(artist, title)
    return currentKey.isNotEmpty() && currentKey == matchKey
}

/**
 * L2: local → [SongListItem], remote → [RemoteTrackPlaceholderRow].
 * Keep [RemoteTrackPlaceholderRow] / [SongListItem] public for one-off layouts.
 */
@Composable
fun MatchedTrackRow(
    localSong: Song?,
    title: String,
    artist: String,
    remoteBadge: String,
    isCurrentPlaying: Boolean,
    remote: PlayableItem.Remote?,
    downloadBusy: Boolean,
    onPlayAt: () -> Unit,
    onDownloadRemote: (PlayableItem.Remote) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (Song) -> Unit,
    leadingIcon: ImageVector = Icons.Default.PlayArrow
) {
    val local = localSong
    if (local != null) {
        SongListItem(
            song = local,
            isCurrentPlaying = isCurrentPlaying,
            onClick = onPlayAt,
            onPlayNext = { onPlayNext(local) },
            onAddToQueue = { onAddToQueue(local) },
            onStartRadio = { onStartRadio(local) }
        )
    } else if (remote != null) {
        RemoteTrackPlaceholderRow(
            title = title,
            artist = artist,
            badge = remoteBadge,
            leadingIcon = leadingIcon,
            highlighted = isCurrentPlaying,
            onClick = onPlayAt,
            onDownload = { onDownloadRemote(remote) },
            downloadBusy = downloadBusy
        )
    }
}
