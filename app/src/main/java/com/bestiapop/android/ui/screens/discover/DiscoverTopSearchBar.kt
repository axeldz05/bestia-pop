package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.ui.components.CatalogAdvancedFiltersPanel
import com.bestiapop.android.ui.components.SearchRecentChipsRow

@Composable
fun DiscoverTopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    showFilters: Boolean,
    onToggleFilters: () -> Unit,
    hasActiveFilters: Boolean,
    onRefreshFeed: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onOpenHistory: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            placeholder = {
                Text(
                    text = "Buscar canciones, álbumes, artistas…",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpiar"
                            )
                        }
                    } else if (onOpenHistory != null) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Historial de búsqueda"
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onToggleFilters,
            modifier = Modifier.background(
                color = if (hasActiveFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            )
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Filtros",
                tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onRefreshFeed
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refrescar recomendaciones",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DiscoverRecentSearchesView(
    recentSearches: List<String>,
    onSelectQuery: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFullHistory: (() -> Unit)? = null
) {
    SearchRecentChipsRow(
        recentSearches = recentSearches,
        onSelectQuery = onSelectQuery,
        onRemoveQuery = onRemoveQuery,
        onClearAll = onClearAll,
        onOpenFullHistory = onOpenFullHistory,
        modifier = modifier
    )
}

/** Level 2: Advanced filters panel accepting bundled [IdentifySearchFilters]. */
@Composable
fun DiscoverAdvancedFiltersPanel(
    filters: IdentifySearchFilters,
    onFiltersChange: (IdentifySearchFilters) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    CatalogAdvancedFiltersPanel(
        filters = filters,
        onFiltersChange = onFiltersChange,
        onApply = onApply,
        onClear = onClear,
        modifier = modifier
    )
}

/** Level 1: Advanced filters panel with individual primitive fields and callbacks. */
@Composable
fun DiscoverAdvancedFiltersPanel(
    artist: String,
    onArtistChange: (String) -> Unit,
    album: String,
    onAlbumChange: (String) -> Unit,
    year: String,
    onYearChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    CatalogAdvancedFiltersPanel(
        artist = artist,
        onArtistChange = onArtistChange,
        album = album,
        onAlbumChange = onAlbumChange,
        year = year,
        onYearChange = onYearChange,
        onApply = onApply,
        onClear = onClear,
        modifier = modifier
    )
}
