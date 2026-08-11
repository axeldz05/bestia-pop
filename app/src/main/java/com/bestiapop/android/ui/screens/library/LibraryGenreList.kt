package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.EmptyListHint
import com.bestiapop.android.ui.components.PlayShuffleIconPair
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ListDensity.rowHorizontalPadding,
                vertical = ListDensity.rowVerticalPadding
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(ListDensity.corner),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(ListDensity.rowInnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = genre.artworkUri,
                size = ListDensity.artworkChipRow,
                cornerRadius = ListDensity.corner,
                fallbackIcon = Icons.Default.Audiotrack
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = genre.name,
                    style = ListDensity.titleStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = ListDensity.subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            PlayShuffleIconPair(
                onPlay = onPlay,
                onShuffle = onShuffle,
                playDescription = "Reproducir género",
                shuffleDescription = "Mezclar género"
            )
        }
    }
}
