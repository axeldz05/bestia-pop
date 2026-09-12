package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.ui.components.PlayShuffleIconPair

@Composable
fun LibraryTopBar(
    hasNestedDetail: Boolean,
    onBackClick: () -> Unit,
    nestedTitle: String?,
    isPlaylistAdditionMode: Boolean,
    filterButtonLabel: String,
    orderSummary: String,
    onOpenSortSheet: () -> Unit,
    searchExpanded: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchExpand: () -> Unit,
    onSearchCollapse: () -> Unit,
    recentSearches: List<String>,
    onOpenSearchHistory: () -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchSubmit: (String) -> Unit,
    selectedAlbumName: String?,
    onEditAlbum: () -> Unit,
    isMultiSelectMode: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasNestedDetail) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
        }

        if (searchExpanded) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Buscar…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isEmpty() && recentSearches.isNotEmpty()) {
                            IconButton(onClick = onOpenSearchHistory) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Historial de búsqueda"
                                )
                            }
                        }
                        IconButton(onClick = onSearchCollapse) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar búsqueda")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSearchSubmit(searchQuery)
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                ),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            )
        } else {
            if (nestedTitle != null) {
                Text(
                    text = nestedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                if (!isPlaylistAdditionMode && !hasNestedDetail) {
                    LibraryFilterButton(
                        label = filterButtonLabel,
                        contentDescription = libraryTuneContentDescription(orderSummary),
                        onClick = onOpenSortSheet
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = onSearchExpand) {
                Icon(Icons.Default.Search, contentDescription = "Buscar")
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (selectedAlbumName != null) {
            IconButton(onClick = onEditAlbum) {
                Icon(Icons.Default.Edit, contentDescription = "Editar álbum")
            }
        }

        if (!searchExpanded && !isMultiSelectMode && !isPlaylistAdditionMode) {
            PlayShuffleIconPair(
                onPlay = onPlayAll,
                onShuffle = onShuffleAll,
                playDescription = "Reproducir todo",
                shuffleDescription = "Mezclar"
            )
        }
    }
}

@Composable
fun LibraryViewModeToggleRow(
    showAlbumHeaders: Boolean,
    hasAlbums: Boolean,
    allAlbumsCollapsed: Boolean,
    onToggleCollapseAllAlbums: () -> Unit,
    onToggleLibraryViewMode: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showAlbumHeaders && hasAlbums) {
            IconButton(onClick = onToggleCollapseAllAlbums) {
                Icon(
                    imageVector = if (allAlbumsCollapsed) {
                        Icons.Default.UnfoldMore
                    } else {
                        Icons.Default.UnfoldLess
                    },
                    contentDescription = if (allAlbumsCollapsed) {
                        "Expandir todos los álbumes"
                    } else {
                        "Colapsar todos los álbumes"
                    }
                )
            }
        }
        IconButton(onClick = onToggleLibraryViewMode) {
            Icon(
                imageVector = Icons.Default.ViewAgenda,
                contentDescription = "Cambiar vista",
                tint = if (showAlbumHeaders) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
