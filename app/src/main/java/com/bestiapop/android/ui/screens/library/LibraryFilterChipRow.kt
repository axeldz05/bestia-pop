package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.theme.ListDensity

@Composable
fun LibraryFilterChipRow(
    selected: LibraryBrowseFilter,
    onSelect: (LibraryBrowseFilter) -> Unit,
    modifier: Modifier = Modifier,
    filters: List<LibraryBrowseFilter> = LibraryBrowseFilter.entries
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.chipLabel()) },
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
        }
    }
}

fun LibraryBrowseFilter.chipLabel(): String = when (this) {
    LibraryBrowseFilter.SONGS -> "Canciones"
    LibraryBrowseFilter.ALBUMS -> "Álbumes"
    LibraryBrowseFilter.ARTISTS -> "Artistas"
    LibraryBrowseFilter.GENRES -> "Géneros"
    LibraryBrowseFilter.RECENT -> "Recientes"
}
