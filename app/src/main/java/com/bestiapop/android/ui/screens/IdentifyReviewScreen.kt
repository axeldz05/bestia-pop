package com.bestiapop.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.screens.identify.IdentifyCandidateList
import com.bestiapop.android.ui.screens.identify.IdentifyOverviewActions
import com.bestiapop.android.ui.screens.identify.IdentifyReviewFooter
import com.bestiapop.android.ui.screens.identify.IdentifyReviewHeader
import com.bestiapop.android.ui.screens.identify.IdentifyReviewOverview
import com.bestiapop.android.ui.state.IdentifyReviewPhase

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

/**
 * Public forwarding overload for [IdentifyCandidateRow] providing backward compatibility
 * for tests and external callers that import from [com.bestiapop.android.ui.screens].
 */
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
) = com.bestiapop.android.ui.screens.identify.IdentifyCandidateRow(
    candidate = candidate,
    fileDurationMs = fileDurationMs,
    song = song,
    applyFields = applyFields,
    selected = selected,
    isPlaying = isPlaying,
    isResolving = isResolving,
    onClick = onClick,
    onPreview = onPreview
)
