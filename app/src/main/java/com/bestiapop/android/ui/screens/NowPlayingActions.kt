package com.bestiapop.android.ui.screens

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.components.SongOverflowMenuItems
import com.bestiapop.android.ui.screens.library.SongActionDialogsController

/**
 * Navigation actions available in Now Playing menu.
 */
@Immutable
data class NowPlayingNavigationActions(
    val onGoToAlbum: (String) -> Unit,
    val onGoToArtist: (String) -> Unit,
    val onGoToLocalPlaylist: (Long) -> Unit,
    val onGoToListenBrainz: (String) -> Unit,
    val onGoToCfRecommendations: () -> Unit
)

/**
 * Song and playback actions available in Now Playing menu.
 */
@Immutable
data class NowPlayingSongActions(
    val onAddToPlaylist: () -> Unit,
    val onIdentify: () -> Unit,
    val onEditSong: () -> Unit,
    val onEditLyrics: () -> Unit,
    val onEditAlbum: () -> Unit,
    val onStartRadio: () -> Unit
) {
    companion object {
        fun from(
            dialogs: SongActionDialogsController,
            localSong: Song?,
            onEditAlbum: () -> Unit,
            onStartRadio: () -> Unit
        ): NowPlayingSongActions = NowPlayingSongActions(
            onAddToPlaylist = { localSong?.let(dialogs.onAddToPlaylist) },
            onIdentify = { localSong?.let(dialogs.onIdentify) },
            onEditSong = { localSong?.let(dialogs.onEdit) },
            onEditLyrics = { localSong?.let(dialogs.onEditLyrics) },
            onEditAlbum = onEditAlbum,
            onStartRadio = onStartRadio
        )
    }
}

/**
 * Level 2: Bundled menu actions for [NowPlayingActionsMenu].
 */
@Immutable
data class NowPlayingMenuActions(
    val navigation: NowPlayingNavigationActions,
    val song: NowPlayingSongActions
)

/**
 * Level 2: Now Playing actions menu with bundled navigation and song actions.
 */
@Composable
fun NowPlayingActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    matchedAlbumName: String?,
    matchedArtistName: String?,
    containingPlaylists: List<Playlist>,
    discoverOrigin: DiscoverPlaybackOrigin,
    isLocal: Boolean,
    canEditAlbum: Boolean,
    actions: NowPlayingMenuActions
) {
    NowPlayingActionsMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        matchedAlbumName = matchedAlbumName,
        matchedArtistName = matchedArtistName,
        containingPlaylists = containingPlaylists,
        discoverOrigin = discoverOrigin,
        isLocal = isLocal,
        canEditAlbum = canEditAlbum,
        onGoToAlbum = actions.navigation.onGoToAlbum,
        onGoToArtist = actions.navigation.onGoToArtist,
        onGoToLocalPlaylist = actions.navigation.onGoToLocalPlaylist,
        onGoToListenBrainz = actions.navigation.onGoToListenBrainz,
        onGoToCfRecommendations = actions.navigation.onGoToCfRecommendations,
        onAddToPlaylist = actions.song.onAddToPlaylist,
        onIdentify = actions.song.onIdentify,
        onEditSong = actions.song.onEditSong,
        onEditLyrics = actions.song.onEditLyrics,
        onEditAlbum = actions.song.onEditAlbum,
        onStartRadio = actions.song.onStartRadio
    )
}

/**
 * Level 1: Now Playing actions menu with individual primitive callbacks.
 */
@Composable
fun NowPlayingActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    matchedAlbumName: String?,
    matchedArtistName: String?,
    containingPlaylists: List<Playlist>,
    discoverOrigin: DiscoverPlaybackOrigin,
    isLocal: Boolean,
    canEditAlbum: Boolean,
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToLocalPlaylist: (Long) -> Unit,
    onGoToListenBrainz: (String) -> Unit,
    onGoToCfRecommendations: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onIdentify: () -> Unit,
    onEditSong: () -> Unit,
    onEditLyrics: () -> Unit,
    onEditAlbum: () -> Unit,
    onStartRadio: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (matchedAlbumName != null) {
            DropdownMenuItem(
                text = { Text("Ir al álbum") },
                onClick = {
                    onDismiss()
                    onGoToAlbum(matchedAlbumName)
                }
            )
        }
        if (matchedArtistName != null) {
            DropdownMenuItem(
                text = { Text("Ir al artista") },
                onClick = {
                    onDismiss()
                    onGoToArtist(matchedArtistName)
                }
            )
        }
        containingPlaylists.forEach { playlist ->
            DropdownMenuItem(
                text = { Text("Ir a ${playlist.name}") },
                onClick = {
                    onDismiss()
                    onGoToLocalPlaylist(playlist.id)
                }
            )
        }
        when (val origin = discoverOrigin) {
            is DiscoverPlaybackOrigin.ListenBrainz -> {
                val label = origin.title.trim().ifBlank { "Para Ti" }
                DropdownMenuItem(
                    text = { Text("Ir a $label") },
                    onClick = {
                        onDismiss()
                        onGoToListenBrainz(origin.mbid)
                    }
                )
            }
            DiscoverPlaybackOrigin.CfRecommendations -> {
                DropdownMenuItem(
                    text = { Text("Ir a Recomendados") },
                    onClick = {
                        onDismiss()
                        onGoToCfRecommendations()
                    }
                )
            }
            DiscoverPlaybackOrigin.None -> Unit
        }
        if (isLocal) {
            SongOverflowMenuItems(
                onDismiss = onDismiss,
                onAddToPlaylist = onAddToPlaylist,
                onIdentify = onIdentify,
                onEditMetadata = onEditSong,
                onEditLyrics = onEditLyrics
            )
            if (canEditAlbum) {
                DropdownMenuItem(
                    text = { Text("Editar álbum") },
                    onClick = {
                        onDismiss()
                        onEditAlbum()
                    }
                )
            }
        }
        DropdownMenuItem(
            text = { Text("Iniciar radio") },
            onClick = {
                onDismiss()
                onStartRadio()
            }
        )
    }
}
