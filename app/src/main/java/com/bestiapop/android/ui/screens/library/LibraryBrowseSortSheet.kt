package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.theme.ListDensity

/**
 * Single place for library shape (browse) + order. Chips remain the fast path for shape;
 * this sheet clarifies “Ver como” vs “Ordenar por”.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryBrowseSortSheet(
    browseFilter: LibraryBrowseFilter,
    sortOption: SortOption,
    sortDirection: SortDirection,
    sortEnabled: Boolean,
    onBrowseFilterChange: (LibraryBrowseFilter) -> Unit,
    onSortOptionChange: (SortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Vista y orden",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Ver como cambia la lista; ordenar solo reordena lo que estás viendo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Ver como",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics {
                    contentDescription = "Ver como — forma de la lista"
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            LibraryBrowseFilter.entries.chunked(3).forEach { rowFilters ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowFilters.forEach { filter ->
                        FilterChip(
                            selected = browseFilter == filter,
                            onClick = { onBrowseFilterChange(filter) },
                            label = { Text(filter.chipLabel()) },
                            modifier = Modifier
                                .height(ListDensity.filterChipHeight)
                                .semantics {
                                    contentDescription = "Ver como ${filter.chipLabel()}"
                                }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (sortEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sortSectionTitle(browseFilter),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = sortSectionTitle(browseFilter)
                            }
                    )
                    TextButton(onClick = onToggleSortDirection) {
                        Icon(
                            imageVector = if (sortDirection == SortDirection.ASC) {
                                Icons.Default.ArrowUpward
                            } else {
                                Icons.Default.ArrowDownward
                            },
                            contentDescription = if (sortDirection == SortDirection.ASC) {
                                "Orden ascendente"
                            } else {
                                "Orden descendente"
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (sortDirection == SortDirection.ASC) "Asc" else "Desc")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                SortOption.entries.forEach { option ->
                    val selected = sortOption == option
                    val label = option.sortLabel(browseFilter)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSortOptionChange(option) }
                            .padding(vertical = 10.dp)
                            .semantics {
                                contentDescription = "Ordenar por $label"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Seleccionado",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recientes usa la fecha de última reproducción (más recientes primero).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun SortOption.sortLabel(browseFilter: LibraryBrowseFilter = LibraryBrowseFilter.SONGS): String =
    when (browseFilter) {
        LibraryBrowseFilter.ALBUMS -> when (this) {
            SortOption.TITLE, SortOption.ALBUM -> "Nombre del álbum"
            SortOption.ARTIST -> "Artista del álbum"
            SortOption.GENRE -> "Género"
            SortOption.DATE_ADDED -> "Fecha de adición"
        }
        LibraryBrowseFilter.ARTISTS -> when (this) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM -> "Nombre del artista"
            SortOption.GENRE -> "Género"
            SortOption.DATE_ADDED -> "Fecha de adición"
        }
        LibraryBrowseFilter.GENRES -> when (this) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM, SortOption.GENRE -> "Nombre del género"
            SortOption.DATE_ADDED -> "Fecha de adición"
        }
        else -> when (this) {
            SortOption.TITLE -> "Título"
            SortOption.ARTIST -> "Artista"
            SortOption.ALBUM -> "Álbum"
            SortOption.GENRE -> "Género"
            SortOption.DATE_ADDED -> "Fecha de adición"
        }
    }

fun SortOption.shortSortLabel(): String = when (this) {
    SortOption.TITLE -> "título"
    SortOption.ARTIST -> "artista"
    SortOption.ALBUM -> "álbum"
    SortOption.GENRE -> "género"
    SortOption.DATE_ADDED -> "fecha"
}

fun libraryOrderSummary(
    browseFilter: LibraryBrowseFilter,
    sortOption: SortOption,
    sortDirection: SortDirection
): String {
    val shape = browseFilter.chipLabel()
    if (browseFilter == LibraryBrowseFilter.RECENT) return shape
    val arrow = if (sortDirection == SortDirection.ASC) "↑" else "↓"
    return "$shape · por ${sortOption.shortSortLabel()} $arrow"
}

private fun sortSectionTitle(browseFilter: LibraryBrowseFilter): String = when (browseFilter) {
    LibraryBrowseFilter.ALBUMS -> "Ordenar álbumes por"
    LibraryBrowseFilter.ARTISTS -> "Ordenar artistas por"
    LibraryBrowseFilter.GENRES -> "Ordenar géneros por"
    else -> "Ordenar canciones por"
}

/** Compact contentDescription / a11y for the Tune header button. */
fun libraryTuneContentDescription(summary: String): String =
    "Vista y orden. $summary"
