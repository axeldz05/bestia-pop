package com.bestiapop.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.IdentifySearchFilters

/**
 * Reusable advanced search filters panel for online catalog and discovery search.
 * Designed with Continuous Granularity:
 * - Level 2: Bundled model [IdentifySearchFilters]
 * - Level 1: Individual primitive fields for fine-grained control
 */

/** Level 2: Advanced filters panel accepting bundled [IdentifySearchFilters]. */
@Composable
fun CatalogAdvancedFiltersPanel(
    filters: IdentifySearchFilters,
    onFiltersChange: (IdentifySearchFilters) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = "Filtros de búsqueda avanzada",
    showActionButtons: Boolean = true
) {
    CatalogAdvancedFiltersPanel(
        artist = filters.artist,
        onArtistChange = { onFiltersChange(filters.copy(artist = it)) },
        album = filters.album,
        onAlbumChange = { onFiltersChange(filters.copy(album = it)) },
        year = if (filters.year > 0) filters.year.toString() else "",
        onYearChange = { onFiltersChange(filters.copy(year = it.toIntOrNull() ?: 0)) },
        onApply = onApply,
        onClear = onClear,
        modifier = modifier,
        title = title,
        showActionButtons = showActionButtons
    )
}

/** Level 1: Advanced filters panel with individual primitive fields and callbacks. */
@Composable
fun CatalogAdvancedFiltersPanel(
    artist: String,
    onArtistChange: (String) -> Unit,
    album: String,
    onAlbumChange: (String) -> Unit,
    year: String,
    onYearChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = "Filtros de búsqueda avanzada",
    showActionButtons: Boolean = true
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            val searchActions = KeyboardActions(
                onSearch = { onApply() },
                onDone = { onApply() }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = artist,
                    onValueChange = onArtistChange,
                    label = { Text("Artista") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("catalog-filter-artist"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = searchActions
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = onAlbumChange,
                    label = { Text("Álbum") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("catalog-filter-album"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = searchActions
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = onYearChange,
                    label = { Text("Año") },
                    modifier = Modifier
                        .width(76.dp)
                        .testTag("catalog-filter-year"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = searchActions
                )
            }

            if (showActionButtons) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("catalog-filter-clear")
                    ) {
                        Text("Limpiar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onApply,
                        modifier = Modifier.testTag("catalog-filter-apply")
                    ) {
                        Text("Aplicar")
                    }
                }
            }
        }
    }
}
