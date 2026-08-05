package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
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
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.PlayShuffleIconPair
import com.bestiapop.android.ui.components.formatSortRelevantInfo

@Composable
fun LibraryArtistList(
    artists: List<Artist>,
    sortOption: SortOption = SortOption.TITLE,
    onArtistClick: (Artist) -> Unit,
    onPlayArtist: (Artist) -> Unit,
    onShuffleArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier
) {
    if (artists.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No se encontraron artistas",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbnail(
                artworkUri = artist.photoUri,
                size = 52.dp,
                cornerRadius = 26.dp, // circular
                fallbackIcon = Icons.Default.Person
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            PlayShuffleIconPair(
                onPlay = onPlay,
                onShuffle = onShuffle,
                playDescription = "Reproducir artista",
                shuffleDescription = "Mezclar artista"
            )
        }
    }
}
