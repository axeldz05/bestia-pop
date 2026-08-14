package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.formatSortRelevantInfo

/**
 * Browse projection for albums: dense [TauonAlbumHeader] rows (no big grid cards).
 */
@Composable
fun LibraryAlbumBrowseList(
    albums: List<Album>,
    sortOption: SortOption = SortOption.TITLE,
    onAlbumClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onShuffleAlbum: (Album) -> Unit,
    onEditAlbum: (Album) -> Unit,
    onChangeAlbumCover: (Album) -> Unit,
    onIdentifyAlbum: (Album) -> Unit = {},
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
            val sortHint = remember(album.genre, album.dateAdded, sortOption) {
                formatSortRelevantInfo(
                    sortOption = sortOption,
                    genre = album.genre,
                    dateAdded = album.dateAdded,
                    alreadyShowsArtist = true,
                    alreadyShowsAlbum = true,
                    alreadyShowsTitle = true
                )
            }
            TauonAlbumHeader(
                title = album.displayName,
                artistName = album.artist,
                artworkUri = album.artworkUri,
                songCount = album.songCount,
                sortHint = sortHint,
                showCollapseToggle = false,
                onPlayAlbum = { onPlayAlbum(album) },
                onShuffleAlbum = { onShuffleAlbum(album) },
                onEditAlbum = { onEditAlbum(album) },
                onChangeAlbumCover = { onChangeAlbumCover(album) },
                onIdentifyAlbum = { onIdentifyAlbum(album) },
                onOpenAlbum = { onAlbumClick(album) }
            )
        }
    }
}
