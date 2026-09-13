package com.bestiapop.android.ui.screens.identify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.state.IdentifyReviewState

/** Level 2 action bundle for overview screen interactions. */
data class IdentifyOverviewActions(
    val onApplyGroup: (String) -> Unit,
    val onReviewGroup: (String) -> Unit,
    val onReviewAll: () -> Unit,
    val onSearchGroupCandidates: (String, String) -> Unit,
    val onSelectGroupCandidate: (String, Int) -> Unit
)

/** Level 2 action bundle for individual album group card interactions. */
data class IdentifyAlbumGroupActions(
    val onApplyAll: () -> Unit,
    val onReviewOneByOne: () -> Unit,
    val onSearchCandidates: (String) -> Unit,
    val onSelectCandidate: (Int) -> Unit
)

/** Level 2 compressed overview layout using [IdentifyOverviewActions]. */
@Composable
fun IdentifyReviewOverview(
    state: IdentifyReviewState,
    actions: IdentifyOverviewActions,
    modifier: Modifier = Modifier
) {
    val remaining = remember(state.items, state.currentIndex) { state.remaining }
    val remainingById = remember(remaining) { remaining.associateBy { it.song.id } }
    val groups = remember(
        state.items,
        state.currentIndex,
        state.albumGroupCandidates,
        state.albumGroupSelectedIndices
    ) { state.albumGroups }
    val ungrouped = remember(state.items, state.currentIndex, state.albumGroups) { state.ungroupedCount }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (groups.isNotEmpty()) {
            item {
                Text(
                    text = "Álbumes identificados",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(groups, key = { it.key }) { group ->
                val titles = group.songIds.mapNotNull { remainingById[it]?.song?.title }
                IdentifyAlbumGroupCard(
                    group = group,
                    titles = titles,
                    canApply = state.applyFields.hasAny && !state.isApplying,
                    applying = state.isApplying,
                    actions = IdentifyAlbumGroupActions(
                        onApplyAll = { actions.onApplyGroup(group.key) },
                        onReviewOneByOne = { actions.onReviewGroup(group.key) },
                        onSearchCandidates = { query -> actions.onSearchGroupCandidates(group.key, query) },
                        onSelectCandidate = { index -> actions.onSelectGroupCandidate(group.key, index) }
                    )
                )
            }
        }
        if (ungrouped > 0) {
            item {
                IdentifyUngroupedBlock(
                    count = ungrouped,
                    onReview = actions.onReviewAll
                )
            }
        }
    }
}

/** Level 1 primitive overload delegating to Level 2 action bundle. */
@Composable
fun IdentifyReviewOverview(
    state: IdentifyReviewState,
    onApplyGroup: (String) -> Unit,
    onReviewGroup: (String) -> Unit,
    onReviewAll: () -> Unit,
    onSearchGroupCandidates: (String, String) -> Unit,
    onSelectGroupCandidate: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) = IdentifyReviewOverview(
    state = state,
    actions = IdentifyOverviewActions(
        onApplyGroup = onApplyGroup,
        onReviewGroup = onReviewGroup,
        onReviewAll = onReviewAll,
        onSearchGroupCandidates = onSearchGroupCandidates,
        onSelectGroupCandidate = onSelectGroupCandidate
    ),
    modifier = modifier
)

/** Level 2 album group card using [IdentifyAlbumGroupActions]. */
@Composable
fun IdentifyAlbumGroupCard(
    group: IdentifyAlbumGroup,
    titles: List<String>,
    canApply: Boolean,
    applying: Boolean,
    actions: IdentifyAlbumGroupActions,
    modifier: Modifier = Modifier
) {
    var expandedTitles by remember(group.key) { mutableStateOf(false) }
    var expandedSearch by remember(group.key) { mutableStateOf(false) }
    var searchQuery by remember(group.key) {
        mutableStateOf("${group.artist} ${group.album}".trim())
    }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkThumbnail(
                artworkUri = group.artworkUri,
                size = 56.dp,
                contentDescription = group.album
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.album.ifBlank { group.currentAlbum.ifBlank { "Álbum desconocido" } },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = group.artist.ifBlank { group.currentArtist.ifBlank { "Artista desconocido" } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val yearText = if (group.year > 0) " · ${group.year}" else ""
                val countText = "${group.songIds.size} canciones"
                Text(
                    text = "$countText$yearText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        // Breakdown of songs with only artwork vs other gaps
        if (group.artworkOnlySongIds.isNotEmpty() || group.otherGapsSongIds.isNotEmpty()) {
            val statusLabel = when {
                group.otherGapsSongIds.isEmpty() ->
                    "${group.artworkOnlySongIds.size} se completarán con la portada del álbum"
                group.artworkOnlySongIds.isEmpty() ->
                    "${group.otherGapsSongIds.size} canciones tienen otros campos por revisar"
                else ->
                    "${group.artworkOnlySongIds.size} solo necesitan portada · ${group.otherGapsSongIds.size} con otros datos pendientes"
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Candidates row if available
        if (group.candidates.isNotEmpty()) {
            Text(
                text = "Candidatos de portada (${group.candidates.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(group.candidates) { idx, cand ->
                    val isSelected = idx == group.selectedCandidateIndex
                    val borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    }
                    val candShape = RoundedCornerShape(8.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(68.dp)
                            .clip(candShape)
                            .border(if (isSelected) 2.dp else 1.dp, borderColor, candShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { actions.onSelectCandidate(idx) }
                            .padding(4.dp)
                    ) {
                        ArtworkThumbnail(
                            artworkUri = cand.coverUrl,
                            size = 56.dp,
                            contentDescription = cand.title
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = cand.releaseYear.ifBlank { cand.title },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Toggle buttons for search and song list
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { expandedSearch = !expandedSearch },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(if (expandedSearch) "Ocultar buscador" else "Buscar otros candidatos")
            }
            TextButton(
                onClick = { expandedTitles = !expandedTitles },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(if (expandedTitles) "Ocultar canciones" else "Ver canciones (${titles.size})")
            }
        }

        // Expanded candidate search
        if (expandedSearch) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Álbum o artista") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.onSearchCandidates(searchQuery) }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { actions.onSearchCandidates(searchQuery) },
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Buscar candidatos")
                }
            }
        }

        // Expanded track list
        if (expandedTitles && titles.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                titles.forEach { title ->
                    Text(
                        text = "· $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = actions.onApplyAll,
                enabled = canApply,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (applying) "Aplicando…" else "Aplicar a álbum",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = actions.onReviewOneByOne,
                enabled = !applying,
                modifier = Modifier.weight(1f)
            ) {
                Text("Revisar canciones", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Level 1 primitive overload delegating to Level 2 action bundle. */
@Composable
fun IdentifyAlbumGroupCard(
    group: IdentifyAlbumGroup,
    titles: List<String>,
    canApply: Boolean,
    applying: Boolean,
    onApplyAll: () -> Unit,
    onReviewOneByOne: () -> Unit,
    onSearchCandidates: (String) -> Unit,
    onSelectCandidate: (Int) -> Unit,
    modifier: Modifier = Modifier
) = IdentifyAlbumGroupCard(
    group = group,
    titles = titles,
    canApply = canApply,
    applying = applying,
    actions = IdentifyAlbumGroupActions(
        onApplyAll = onApplyAll,
        onReviewOneByOne = onReviewOneByOne,
        onSearchCandidates = onSearchCandidates,
        onSelectCandidate = onSelectCandidate
    ),
    modifier = modifier
)

@Composable
fun IdentifyUngroupedBlock(
    count: Int,
    onReview: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (count == 1) "1 sin álbum claro" else "$count sin álbum claro",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onReview, contentPadding = PaddingValues(0.dp)) {
            Text("Revisar una a una")
        }
    }
}
