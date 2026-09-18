package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.components.CollectionDetailHero
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.SongItemActions
import com.bestiapop.android.ui.components.SongListItem
import com.bestiapop.android.ui.components.formatDuration

/**
 * Shared Level 2 collection detail layout for library submenus (Album, Genre, etc.).
 * Unifies ScreenBackHeader, CollectionDetailHero, and the scrollable songs list with
 * consistent metadata formatting, continuous granularity slots, and full action menus.
 */
@Composable
fun LibraryCollectionDetailView(
    title: String,
    onBack: () -> Unit,
    songs: List<Song>,
    currentSongId: Long?,
    songActions: SongItemActions,
    onPlaySong: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    modifier: Modifier = Modifier,
    heroSubtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    artworkUri: String? = null,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    showSongArtwork: Boolean = true,
    playEnabled: Boolean = songs.isNotEmpty(),
    shuffleEnabled: Boolean = songs.isNotEmpty(),
    metadataTextOverride: String? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    extraContent: (LazyListScope.() -> Unit)? = null,
) {
    val totalDurationMs = remember(songs) { songs.sumOf { it.durationMs } }
    val durationLabel = remember(totalDurationMs) { formatDuration(totalDurationMs) }
    val metadataText =
        metadataTextOverride ?: remember(songs.size, durationLabel) {
            buildString {
                append("${songs.size} canciones")
                if (durationLabel.isNotEmpty()) {
                    append(" • $durationLabel")
                }
            }
        }

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenBackHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            trailing = { headerTrailing?.invoke(this) },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "collection-hero-header") {
                CollectionDetailHero(
                    title = title,
                    subtitle = heroSubtitle,
                    metadata = metadataText,
                    artworkUri = artworkUri,
                    fallbackIcon = fallbackIcon,
                    onSubtitleClick = onSubtitleClick,
                    playEnabled = playEnabled,
                    shuffleEnabled = shuffleEnabled,
                    onPlay = onPlayAll,
                    onShuffle = onShuffleAll,
                )
            }

            itemsIndexed(
                items = songs,
                key = { _, song -> "collection-song-${song.id}" },
            ) { index, song ->
                val isPlaying = currentSongId == song.id
                val trackNum = song.trackNumber.takeIf { it > 0 } ?: (index + 1)
                SongListItem(
                    song = song,
                    actions = songActions,
                    isCurrentPlaying = isPlaying,
                    showArtwork = showSongArtwork,
                    leading =
                        if (!showSongArtwork) {
                            {
                                Text(
                                    text = "$trackNum",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color =
                                        if (isPlaying) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(28.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        } else {
                            null
                        },
                    onClick = { onPlaySong(index) },
                )
            }

            extraContent?.invoke(this)
        }
    }
}
