package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.FastScrollContainer
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.theme.ListDensity

import com.bestiapop.android.data.preferences.FastScrollSettings

@Composable
fun LibraryArtistList(
    artists: List<Artist>,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    onArtistClick: (Artist) -> Unit,
    onPlayArtist: (Artist) -> Unit,
    onShuffleArtist: (Artist) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    if (artists.isEmpty()) {
        EmptyListHint(
            text = "Ningún artista coincide",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val sections = remember(artists, sortOption) {
        FastScrollSections.fromArtists(artists, sortOption)
    }

    FastScrollContainer(
        sections = sections,
        listState = listState,
        settings = fastScrollSettings,
        modifier = modifier.fillMaxSize()
    ) { listModifier ->
        LazyColumn(state = listState, modifier = listModifier) {
            items(artists, key = { it.name }) { artist ->
                ArtistListItem(
                    artist = artist,
                    sortOption = sortOption,
                    onClick = { onArtistClick(artist) },
                    onPlay = { onPlayArtist(artist) },
                    onShuffle = { onShuffleArtist(artist) }
                )
            }
        }
    }
}

@Composable
fun ArtistListItem(
    artist: Artist,
    sortOption: SortOption = SortOption.TITLE,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    val sortInfo = remember(artist.genre, artist.dateAdded, sortOption) {
        formatSortRelevantInfo(
            sortOption = sortOption,
            genre = artist.genre,
            dateAdded = artist.dateAdded,
            alreadyShowsArtist = true,
            alreadyShowsAlbum = false,
            alreadyShowsTitle = false
        )
    }
    val subtitle = remember(artist.albumCount, artist.songCount, sortInfo) {
        val base = "${artist.albumCount} álbumes • ${artist.songCount} canciones"
        if (sortInfo.isNullOrBlank()) base else "$base • $sortInfo"
    }

    LibraryAggregateListItem(
        title = artist.name,
        subtitle = subtitle,
        artworkUri = artist.photoUri,
        artworkCornerRadius = ListDensity.artworkChipRow / 2,
        fallbackIcon = Icons.Default.Person,
        playDescription = "Reproducir artista",
        shuffleDescription = "Mezclar artista",
        onClick = onClick,
        onPlay = onPlay,
        onShuffle = onShuffle
    )
}
