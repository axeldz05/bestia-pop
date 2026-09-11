package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.IdentifyApplyField
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isEnabled
import com.bestiapop.android.data.model.withField
import com.bestiapop.android.data.util.albumTrackDisplayNumber
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.formatIdentifyApplyChanges
import com.bestiapop.android.domain.util.identifyApplyChanges
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.PreviewPlayPauseButton
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.TrackTextColumn
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.components.joinMeta
import com.bestiapop.android.ui.components.previewFlags
import com.bestiapop.android.ui.state.IdentifyReviewItem
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.IdentifyReviewState

@Composable
fun IdentifyReviewScreen(
    viewModel: MusicPlayerViewModel
) {
    val state by viewModel.identifyReview.collectAsStateWithLifecycle()
    if (!state.isOpen) return

    BackHandler {
        if (state.phase == IdentifyReviewPhase.Item && state.openedFromOverview) {
            viewModel.returnIdentifyReviewOverview()
        } else {
            viewModel.dismissIdentifyReview()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("identify-review"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            IdentifyReviewHeader(
                state = state,
                onBack = {
                    if (state.phase == IdentifyReviewPhase.Item && state.openedFromOverview) {
                        viewModel.returnIdentifyReviewOverview()
                    } else {
                        viewModel.dismissIdentifyReview()
                    }
                },
                onClose = { viewModel.dismissIdentifyReview() },
                onApplyRemaining = { viewModel.applyRemainingIdentifySuggestions() },
                onSkipAll = { viewModel.skipAllIdentifyReview() },
                onApplyFieldsChanged = viewModel::setIdentifyReviewApplyFields
            )
            HorizontalDivider()

            if (state.phase == IdentifyReviewPhase.Overview) {
                IdentifyReviewOverview(
                    state = state,
                    actions = IdentifyOverviewActions(
                        onApplyGroup = viewModel::applyIdentifyAlbumGroup,
                        onReviewGroup = { viewModel.startIdentifyItemReview(it) },
                        onReviewAll = { viewModel.startIdentifyItemReview(null) },
                        onSearchGroupCandidates = viewModel::searchAlbumCandidates,
                        onSelectGroupCandidate = viewModel::selectAlbumCandidate
                    ),
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            val item = state.current
            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nada por revisar")
                }
            } else {
                val showSearch = state.showSearchField || item.proposal.candidates.isEmpty()
                val searchPlaceholder = item.proposal.queryTitle.trim()
                    .takeUnless { it.isBlank() || looksLikeStoragePath(it) }
                    ?: item.song.title.trim()
                        .takeUnless { it.isBlank() || looksLikeStoragePath(it) }
                    ?: item.proposal.queryArtist.trim().takeUnless {
                        it.isBlank() || looksLikeStoragePath(it) ||
                            IdentifyRanking.isPlaceholderArtist(it)
                    }
                    ?: "Título o artista"
                val candidates = remember(item.proposal.candidates, state.visibleCandidateCount) {
                    val all = item.proposal.candidates
                    all.take(state.visibleCandidateCount.coerceIn(0, all.size))
                }

                IdentifyCandidateList(
                    viewModel = viewModel,
                    item = item,
                    candidates = candidates,
                    showSearch = showSearch,
                    searchPlaceholder = searchPlaceholder,
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                IdentifyReviewFooter(
                    canApply = state.canApplySelected,
                    showSearchField = state.showSearchField,
                    showSearchFilters = state.showSearchFilters,
                    isSearching = state.isSearching || state.isLoadingMore,
                    isApplying = state.isApplying,
                    onUse = viewModel::applySelectedIdentifyCandidate,
                    onSkip = viewModel::skipIdentifyReviewItem,
                    onToggleSearch = { viewModel.toggleIdentifySearchField() },
                    onToggleFilters = { viewModel.toggleIdentifySearchFilters() }
                )
            }
        }
    }
}

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
private fun IdentifyReviewOverview(
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
private fun IdentifyReviewOverview(
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
private fun IdentifyAlbumGroupCard(
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
private fun IdentifyAlbumGroupCard(
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
private fun IdentifyUngroupedBlock(
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

@Composable
private fun IdentifyReviewHeader(
    state: IdentifyReviewState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onApplyRemaining: () -> Unit,
    onSkipAll: () -> Unit,
    onApplyFieldsChanged: (IdentifyApplyFields) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenBackHeader(
            title = "Revisar identidad",
            subtitle = state.headerSubtitle,
            onBack = onBack,
            backContentDescription = if (state.phase == IdentifyReviewPhase.Item && state.openedFromOverview) {
                "Volver"
            } else {
                "Cerrar"
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("identify-review-close")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar")
            }
        }
        IdentifyApplyFieldsChips(
            applyFields = state.applyFields,
            onApplyFieldsChanged = onApplyFieldsChanged,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (state.pendingCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onApplyRemaining,
                    enabled = state.canApplyRemaining && !state.isSearching,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (state.isApplying) "Aplicando…" else "Aplicar automático a restantes",
                        maxLines = 2
                    )
                }
                TextButton(
                    onClick = onSkipAll,
                    enabled = !state.isSearching && !state.isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Omitir todas", maxLines = 2)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentifyApplyFieldsChips(
    applyFields: IdentifyApplyFields,
    onApplyFieldsChanged: (IdentifyApplyFields) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Se aplicará",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IdentifyApplyField.entries.forEach { field ->
                val selected = applyFields.isEnabled(field)
                FilterChip(
                    selected = selected,
                    onClick = { onApplyFieldsChanged(applyFields.withField(field, !selected)) },
                    label = { Text(field.chipLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

@Composable
private fun IdentifySourcePlaying(
    viewModel: MusicPlayerViewModel,
    song: Song,
    sourceHints: String?,
    confidence: IdentifyConfidence
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val localPlaying = currentItem is PlayableItem.Local &&
        (currentItem as PlayableItem.Local).song.id == song.id &&
        isPlaying
    IdentifySourceBlock(
        song = song,
        sourceHints = sourceHints,
        confidence = confidence,
        isPlaying = localPlaying,
        onPreview = { viewModel.previewIdentifyLocalSong(song) }
    )
}

@Composable
private fun IdentifyCandidateList(
    viewModel: MusicPlayerViewModel,
    item: IdentifyReviewItem,
    candidates: List<IdentifyCandidate>,
    showSearch: Boolean,
    searchPlaceholder: String,
    state: IdentifyReviewState,
    modifier: Modifier = Modifier
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val resolvingRemote by viewModel.resolvingRemote.collectAsStateWithLifecycle()
    val catalogPreviewKey by viewModel.catalogPreviewKey.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "source_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tu archivo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                IdentifySourcePlaying(
                    viewModel = viewModel,
                    song = item.song,
                    sourceHints = item.proposal.sourceHints,
                    confidence = item.proposal.confidence
                )
            }
        }
        if (showSearch) {
            item(key = "search_block") {
                IdentifySearchBlock(
                    query = state.searchQueryDraft,
                    filterArtist = state.searchFilterArtist,
                    filterAlbum = state.searchFilterAlbum,
                    filterYear = state.searchFilterYear,
                    showFilters = state.showSearchFilters,
                    placeholder = searchPlaceholder,
                    isSearching = state.isSearching,
                    onQueryChange = viewModel::setIdentifySearchDraft,
                    onFilterArtistChange = viewModel::setIdentifySearchFilterArtist,
                    onFilterAlbumChange = viewModel::setIdentifySearchFilterAlbum,
                    onFilterYearChange = viewModel::setIdentifySearchFilterYear,
                    onSearch = viewModel::searchIdentifyCandidates
                )
            }
        }
        item(key = "candidates_header") {
            Text(
                text = if (candidates.isEmpty()) {
                    "Sin candidatos — buscá otro"
                } else {
                    "Candidatos"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        itemsIndexed(
            items = candidates,
            key = { index, c ->
                val stableId = c.track.id.ifBlank { "${c.artist}|${c.title}|${c.album}" }
                "${c.provider}|$stableId|$index"
            }
        ) { index, candidate ->
            val flags = previewFlags(
                catalogPreviewKey,
                viewModel.catalogPreviewKeyFor(candidate.track),
                isPlaying,
                resolvingRemote
            )
            IdentifyCandidateRow(
                candidate = candidate,
                fileDurationMs = item.song.durationMs,
                song = item.song,
                applyFields = state.applyFields,
                selected = index == state.selectedCandidateIndex,
                isPlaying = flags.isPlaying,
                isResolving = flags.isResolving,
                onClick = { viewModel.selectIdentifyCandidate(index) },
                onPreview = { viewModel.previewIdentifyCandidate(candidate) }
            )
        }
        if (state.canShowMoreCandidates) {
            item(key = "load_more") {
                IdentifyLoadMoreButton(
                    isLoading = state.isLoadingMore,
                    enabled = !state.isSearching,
                    onClick = viewModel::loadMoreIdentifyCandidates
                )
            }
        }
    }
}

@Composable
private fun IdentifySourceBlock(
    song: Song,
    sourceHints: String?,
    confidence: IdentifyConfidence,
    isPlaying: Boolean,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkThumbnail(
            artworkUri = song.artworkUri,
            size = 56.dp,
            contentDescription = song.title
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            TrackTextColumn(
                title = song.title,
                subtitle = joinMeta(song.artist, song.album, sep = " · "),
                titleWeight = FontWeight.SemiBold,
                maxTitleLines = 2
            )
            val meta = buildList {
                if (song.durationMs > 0) add(formatDuration(song.durationMs))
                song.year.takeIf { it in 1000..9999 }?.let { add(it.toString()) }
                albumTrackDisplayNumber(song.trackNumber).takeIf { it > 0 }?.let { add("Pista $it") }
                if (!sourceHints.isNullOrBlank()) add("Origen: $sourceHints")
                add(confidence.label)
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (IdentifyRanking.titleCollidesWithArtistOrAlbum(song.title, song.artist, song.album)) {
                Text(
                    text = "El título coincide con el artista/álbum — revisá por duración",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )
            }
        }
        PreviewPlayPauseButton(
            isResolving = false,
            isPlaying = isPlaying,
            onClick = onPreview
        )
    }
}

@Composable
fun IdentifyCandidateRow(
    candidate: IdentifyCandidate,
    fileDurationMs: Long,
    song: Song,
    applyFields: IdentifyApplyFields,
    selected: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkThumbnail(
            artworkUri = candidate.artworkUri,
            size = 52.dp,
            contentDescription = candidate.title
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            TrackTextColumn(
                title = candidate.title,
                subtitle = candidate.artist,
                titleStyle = MaterialTheme.typography.titleSmall,
                titleWeight = FontWeight.SemiBold
            )
            val albumLabel = when {
                candidate.album.isBlank() || IdentifyRanking.isGenericAlbum(candidate.album) ->
                    "Single / sin álbum"
                else -> candidate.album
            }
            val yearPart = candidate.year.takeIf { it in 1000..9999 }?.toString()
            val durationPart = when {
                candidate.durationMs > 0 && fileDurationMs > 0 ->
                    "${formatDuration(candidate.durationMs)} (archivo ${formatDuration(fileDurationMs)})"
                candidate.durationMs > 0 -> formatDuration(candidate.durationMs)
                else -> null
            }
            Text(
                text = joinMeta(albumLabel, yearPart, durationPart, sep = " · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val reason = candidate.reasons.firstOrNull()
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConfidenceChip(score = candidate.score)
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val changes = remember(song.id, candidate.track.id, candidate.title, candidate.artist, candidate.album, applyFields) {
                identifyApplyChanges(song, candidate, applyFields)
            }
            Text(
                text = formatIdentifyApplyChanges(changes),
                style = MaterialTheme.typography.labelSmall,
                color = if (changes.isEmpty()) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        PreviewPlayPauseButton(
            isResolving = isResolving,
            isPlaying = isPlaying,
            onClick = onPreview
        )
    }
}

@Composable
private fun ConfidenceChip(score: Float) {
    val label = IdentifyRanking.scoreToConfidence(score).label
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun IdentifySearchBlock(
    query: String,
    filterArtist: String,
    filterAlbum: String,
    filterYear: String,
    showFilters: Boolean,
    placeholder: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onFilterArtistChange: (String) -> Unit,
    onFilterAlbumChange: (String) -> Unit,
    onFilterYearChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    val searchActions = KeyboardActions(
        onSearch = { onSearch() },
        onDone = { onSearch() },
        onGo = { onSearch() }
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Buscar otro") },
            placeholder = {
                Text(
                    text = placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(12.dp)
                            .height(24.dp)
                            .width(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = searchActions,
            enabled = !isSearching
        )
        if (showFilters) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = filterArtist,
                    onValueChange = onFilterArtistChange,
                    modifier = Modifier.weight(1.35f),
                    singleLine = true,
                    label = { Text("Artista") },
                    enabled = !isSearching,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = searchActions
                )
                OutlinedTextField(
                    value = filterYear,
                    onValueChange = onFilterYearChange,
                    modifier = Modifier.weight(0.65f),
                    singleLine = true,
                    label = { Text("Año") },
                    enabled = !isSearching,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = searchActions
                )
            }
            OutlinedTextField(
                value = filterAlbum,
                onValueChange = onFilterAlbumChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Álbum") },
                enabled = !isSearching,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = searchActions
            )
        }
    }
}

@Composable
private fun IdentifyLoadMoreButton(
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(18.dp)
                    .width(18.dp),
                strokeWidth = 2.dp
            )
        }
        Text(if (isLoading) "Cargando…" else "Mostrar más candidatos")
    }
}

@Composable
private fun IdentifyReviewFooter(
    canApply: Boolean,
    showSearchField: Boolean,
    showSearchFilters: Boolean,
    isSearching: Boolean,
    isApplying: Boolean,
    onUse: () -> Unit,
    onSkip: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleFilters: () -> Unit
) {
    val actionsEnabled = !isSearching && !isApplying
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onUse,
            enabled = canApply && actionsEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isApplying) "Aplicando…" else "Usar este")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSkip,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Omitir")
            }
            TextButton(
                onClick = onToggleSearch,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showSearchField) "Ocultar búsqueda" else "Buscar otro…")
            }
        }
        if (showSearchField) {
            TextButton(
                onClick = onToggleFilters,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showSearchFilters) "Ocultar filtros" else "Filtros adicionales…")
            }
        }
    }
}

