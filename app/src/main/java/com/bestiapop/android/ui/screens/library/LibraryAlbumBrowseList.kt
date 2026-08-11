package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.ui.components.EmptyListHint

/**
 * Browse projection for albums: dense [TauonAlbumHeader] rows (no big grid cards).
 */
@Composable
fun LibraryAlbumBrowseList(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) {
        EmptyListHint(
            text = "Ningún álbum coincide",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(albums, key = { it.name }) { album ->
            TauonAlbumHeader(
                albumName = album.displayName,
                artistName = album.artist,
                artworkUri = album.artworkUri,
                songCount = album.songCount,
                showCollapseToggle = false,
                onPlayAlbum = { onPlayAlbum(album) },
                onShuffleAlbum = { onShuffleAlbum(album) },
                onEditAlbum = { onEditAlbum(album) },
                onChangeAlbumCover = { onChangeAlbumCover(album) },
                onOpenAlbum = { onAlbumClick(album) }
            )
        }
    }
}
