package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.FastScrollLazyColumn
import com.bestiapop.android.ui.components.FastScrollSections
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.theme.ListDensity

/** Level 2: High-level LibraryArtistList accepting bundled [AggregateBrowseActions]. */
@Composable
fun LibraryArtistList(
    artists: List<Artist>,
    actions: AggregateBrowseActions<Artist>,
    sortOption: SortOption = SortOption.TITLE,
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val sections = remember(artists, sortOption) {
        FastScrollSections.fromArtists(artists, sortOption)
    }

    FastScrollLazyColumn(
        items = artists,
        key = { it.name },
        sections = sections,
        emptyText = "Ningún artista coincide",
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    ) { artist ->
        ArtistListItem(
            artist = artist,
            sortOption = sortOption,
            onClick = { actions.onClick(artist) },
            onPlay = { actions.onPlay(artist) },
            onShuffle = { actions.onShuffle(artist) },
            swipeAction = actions.swipeAction,
            onSwipeAction = actions.onSwipeAction?.let { cb -> { cb(artist) } }
        )
    }
}

/** Level 1: Low-level LibraryArtistList with individual primitive callbacks. */
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
    val actions = remember(onArtistClick, onPlayArtist, onShuffleArtist) {
        AggregateBrowseActions(
            onClick = onArtistClick,
            onPlay = onPlayArtist,
            onShuffle = onShuffleArtist
        )
    }
    LibraryArtistList(
        artists = artists,
        actions = actions,
        sortOption = sortOption,
        fastScrollSettings = fastScrollSettings,
        listState = listState,
        modifier = modifier
    )
}

@Composable
fun ArtistListItem(
    artist: Artist,
    sortOption: SortOption = SortOption.TITLE,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    swipeAction: SubmenuSwipeAction = SubmenuSwipeAction.DISABLED,
    onSwipeAction: (() -> Unit)? = null
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
        onShuffle = onShuffle,
        swipeAction = swipeAction,
        onSwipeAction = onSwipeAction
    )
}
