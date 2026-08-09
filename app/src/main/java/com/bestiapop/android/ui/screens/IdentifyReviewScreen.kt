package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.PreviewPlayPauseButton
import com.bestiapop.android.ui.components.ScreenBackHeader
import com.bestiapop.android.ui.components.TrackTextColumn
import com.bestiapop.android.ui.components.formatDuration
import com.bestiapop.android.ui.components.joinMeta
import com.bestiapop.android.ui.components.previewFlags
import com.bestiapop.android.ui.state.IdentifyReviewState

@Composable
fun IdentifyReviewScreen(
    viewModel: MusicPlayerViewModel
) {
    val state by viewModel.identifyReview.collectAsState()
    if (!state.isOpen) return

    val isPlaying by viewModel.isPlaying.collectAsState()
    val resolvingRemote by viewModel.resolvingRemote.collectAsState()
    val catalogPreviewKey by viewModel.catalogPreviewKey.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()

    BackHandler { viewModel.dismissIdentifyReview() }

    Surface(
        modifier = Modifier.fillMaxSize(),
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
                onClose = { viewModel.dismissIdentifyReview() },
                onApplyRemaining = { viewModel.applyRemainingIdentifySuggestions() },
                onSkipAll = { viewModel.skipAllIdentifyReview() }
            )
            HorizontalDivider()

            val item = state.current
            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nada por revisar")
                }
            } else {
                val localPlaying = currentItem is PlayableItem.Local &&
                    (currentItem as PlayableItem.Local).song.id == item.song.id &&
                    isPlaying
                val showSearch = state.showSearchField || item.proposal.candidates.isEmpty()
                val searchPlaceholder = item.song.title.trim()
                    .takeUnless { it.isBlank() || looksLikeStoragePath(it) }
                    ?: item.song.artist.trim().takeUnless {
                        it.isBlank() || looksLikeStoragePath(it)
                    }
                    ?: "Título o artista"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "Tu archivo",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    IdentifySourceBlock(
                        song = item.song,
                        sourceHints = item.proposal.sourceHints,
                        confidence = item.proposal.confidence,
                        isPlaying = localPlaying,
                        onPreview = { viewModel.previewIdentifyLocalSong(item.song) }
                    )
                    if (showSearch) {
                        Spacer(Modifier.height(12.dp))
                        IdentifySearchBlock(
                            query = state.searchQueryDraft,
                            placeholder = searchPlaceholder,
                            isSearching = state.isSearching,
                            onQueryChange = viewModel::setIdentifySearchDraft,
                            onSearch = viewModel::searchIdentifyCandidates
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = if (item.proposal.candidates.isEmpty()) {
                                "Sin candidatos — buscá otro"
                            } else {
                                "Candidatos"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    itemsIndexed(
                        items = item.proposal.candidates,
                        key = { index, c -> "${c.provider}|${c.artist}|${c.title}|${c.album}|$index" }
                    ) { index, candidate ->
                        val track = candidate.track
                        val flags = previewFlags(
                            catalogPreviewKey,
                            viewModel.catalogPreviewKeyFor(track),
                            isPlaying,
                            resolvingRemote
                        )
                        IdentifyCandidateRow(
                            candidate = candidate,
                            fileDurationMs = item.song.durationMs,
                            selected = index == state.selectedCandidateIndex,
                            isPlaying = flags.isPlaying,
                            isResolving = flags.isResolving,
                            onClick = { viewModel.selectIdentifyCandidate(index) },
                            onPreview = { viewModel.previewIdentifyCandidate(candidate) }
                        )
                    }
                }

                IdentifyReviewFooter(
                    hasCandidates = item.proposal.candidates.isNotEmpty(),
                    showSearchField = state.showSearchField,
                    isSearching = state.isSearching,
                    onUse = viewModel::applySelectedIdentifyCandidate,
                    onSkip = viewModel::skipIdentifyReviewItem,
                    onToggleSearch = { viewModel.toggleIdentifySearchField() }
                )
            }
        }
    }
}

@Composable
private fun IdentifyReviewHeader(
    state: IdentifyReviewState,
    onClose: () -> Unit,
    onApplyRemaining: () -> Unit,
    onSkipAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenBackHeader(
            title = "Revisar identidad",
            subtitle = "Revisar ${state.reviewOrdinal} de ${state.reviewTotal}",
            onBack = onClose,
            backContentDescription = "Cerrar",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar")
            }
        }
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
                    Text("Aplicar automático a restantes", maxLines = 2)
                }
                TextButton(
                    onClick = onSkipAll,
                    enabled = !state.isSearching,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Omitir todas", maxLines = 2)
                }
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
                if (!sourceHints.isNullOrBlank()) add("Origen: $sourceHints")
                add(confidenceLabel(confidence))
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
            val durationPart = when {
                candidate.durationMs > 0 && fileDurationMs > 0 ->
                    "${formatDuration(candidate.durationMs)} (archivo ${formatDuration(fileDurationMs)})"
                candidate.durationMs > 0 -> formatDuration(candidate.durationMs)
                else -> null
            }
            Text(
                text = joinMeta(albumLabel, durationPart, sep = " · "),
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
    val label = when {
        score >= IdentifyRanking.HIGH_SCORE -> "Alta"
        score >= IdentifyRanking.MEDIUM_SCORE -> "Posible"
        else -> "Baja"
    }
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
    placeholder: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            enabled = !isSearching
        )
    }
}

@Composable
private fun IdentifyReviewFooter(
    hasCandidates: Boolean,
    showSearchField: Boolean,
    isSearching: Boolean,
    onUse: () -> Unit,
    onSkip: () -> Unit,
    onToggleSearch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onUse,
            enabled = hasCandidates && !isSearching,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Usar este")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !isSearching,
                modifier = Modifier.weight(1f)
            ) {
                Text("Omitir")
            }
            TextButton(
                onClick = onToggleSearch,
                enabled = !isSearching,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showSearchField) "Ocultar búsqueda" else "Buscar otro…")
            }
        }
    }
}

private fun confidenceLabel(confidence: IdentifyConfidence): String = when (confidence) {
    IdentifyConfidence.HIGH -> "Alta"
    IdentifyConfidence.MEDIUM -> "Posible"
    IdentifyConfidence.LOW -> "Baja"
    IdentifyConfidence.NONE -> "Sin match"
}
