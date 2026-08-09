package com.bestiapop.android.ui.screens

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.ui.components.SongOverflowMenuItems
import com.bestiapop.android.ui.state.DiscoverPlaybackOrigin

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
                onEditMetadata = onEditSong
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
