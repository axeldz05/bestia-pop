package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.theme.ListDensity

@Composable
fun LibraryGenreList(
    genres: List<GenreGroup>,
    sortOption: SortOption = SortOption.TITLE,
    onGenreClick: (GenreGroup) -> Unit,
    onPlayGenre: (GenreGroup) -> Unit,
    onShuffleGenre: (GenreGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    if (genres.isEmpty()) {
        EmptyListHint(
            text = "Ningún género coincide",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(genres, key = { it.name }) { genre ->
            GenreListItem(
                genre = genre,
                sortOption = sortOption,
                onClick = { onGenreClick(genre) },
                onPlay = { onPlayGenre(genre) },
                onShuffle = { onShuffleGenre(genre) }
            )
        }
    }
}

@Composable
fun GenreListItem(
    genre: GenreGroup,
    sortOption: SortOption = SortOption.TITLE,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    val sortInfo = remember(genre.dateAdded, sortOption) {
        formatSortRelevantInfo(
            sortOption = sortOption,
            genre = null,
            dateAdded = genre.dateAdded,
            alreadyShowsArtist = false,
            alreadyShowsAlbum = false,
            alreadyShowsTitle = true
        )
    }
    val subtitle = remember(genre.songCount, sortInfo) {
        val base = "${genre.songCount} canciones"
        if (sortInfo.isNullOrBlank()) base else "$base • $sortInfo"
    }

    LibraryAggregateListItem(
        title = genre.name,
        subtitle = subtitle,
        artworkUri = genre.artworkUri,
        artworkCornerRadius = ListDensity.corner,
        fallbackIcon = Icons.Default.Audiotrack,
        playDescription = "Reproducir género",
        shuffleDescription = "Mezclar género",
        onClick = onClick,
        onPlay = onPlay,
        onShuffle = onShuffle
    )
}
