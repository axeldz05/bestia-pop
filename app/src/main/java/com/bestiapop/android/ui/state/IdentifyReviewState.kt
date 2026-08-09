package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.Song

/** One song awaiting manual identify review. */
data class IdentifyReviewItem(
    val song: Song,
    val proposal: IdentifyProposal
)

/**
 * Identify review queue. [items] can stay while [isVisible] is false
 * (WiFi button / resume). Overlay is open only when both are set.
 */
data class IdentifyReviewState(
    val items: List<IdentifyReviewItem> = emptyList(),
    val currentIndex: Int = 0,
    val selectedCandidateIndex: Int = 0,
    val isSearching: Boolean = false,
    val searchQueryDraft: String = "",
    val showSearchField: Boolean = false,
    val sessionApplied: Int = 0,
    val sessionSkipped: Int = 0,
    val isVisible: Boolean = false
) {
    val current: IdentifyReviewItem?
        get() = items.getOrNull(currentIndex)

    val isOpen: Boolean
        get() = isVisible && items.isNotEmpty()

    val pendingCount: Int
        get() = (items.size - currentIndex).coerceAtLeast(0)

    val reviewOrdinal: Int
        get() = if (items.isEmpty()) 0 else (currentIndex + 1).coerceAtMost(items.size)

    val reviewTotal: Int
        get() = items.size

    val canApplyRemaining: Boolean
        get() = items.drop(currentIndex).any { item ->
            item.proposal.suggested != null || item.proposal.candidates.isNotEmpty()
        }
}
