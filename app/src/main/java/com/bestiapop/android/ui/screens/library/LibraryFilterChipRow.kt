package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.components.ResponsiveActionRow
import com.bestiapop.android.ui.components.ResponsiveFilterChip
import com.bestiapop.android.ui.state.LibraryBrowseFilter

@Composable
fun LibraryFilterChipRow(
    selected: LibraryBrowseFilter,
    onSelect: (LibraryBrowseFilter) -> Unit,
    modifier: Modifier = Modifier,
    filters: List<LibraryBrowseFilter> = LibraryBrowseFilter.entries,
) {
    ResponsiveActionRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        filters.forEach { filter ->
            ResponsiveFilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = filter.chipLabel(),
            )
        }
    }
}

fun LibraryBrowseFilter.chipLabel(): String =
    when (this) {
        LibraryBrowseFilter.SONGS -> "Canciones"
        LibraryBrowseFilter.ALBUMS -> "Álbumes"
        LibraryBrowseFilter.ARTISTS -> "Artistas"
        LibraryBrowseFilter.GENRES -> "Géneros"
        LibraryBrowseFilter.PLAYLISTS -> "Playlists"
        LibraryBrowseFilter.RECENT -> "Recientes"
    }
