package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups

enum class IdentifyReviewPhase {
    Overview,
    Item
}

/** One song awaiting manual identify review. */
data class IdentifyReviewItem(
    val song: Song,
    val proposal: IdentifyProposal
)

/**
 * Identify review queue. [items] can stay while [isVisible] is false
 * (WiFi button / resume / cold start). Overlay is open only when both are set.
 */
data class IdentifyReviewState(
    val items: List<IdentifyReviewItem> = emptyList(),
    val currentIndex: Int = 0,
    val selectedCandidateIndex: Int = 0,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val searchQueryDraft: String = "",
    val searchFilterArtist: String = "",
    val searchFilterAlbum: String = "",
    val searchFilterYear: String = "",
    val showSearchField: Boolean = false,
    /** Extra refine fields (artist/album/year); only when user opens “Filtros”. */
    val showSearchFilters: Boolean = false,
    /** How many of [IdentifyProposal.candidates] are shown (prefix); grows via “mostrar más”. */
    val visibleCandidateCount: Int = IdentifyRanking.TOP_N,
    val sessionApplied: Int = 0,
    val sessionSkipped: Int = 0,
    val isVisible: Boolean = false,
    val phase: IdentifyReviewPhase = IdentifyReviewPhase.Item,
    val openedFromOverview: Boolean = false,
    val applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL
) {
    val current: IdentifyReviewItem?
        get() = items.getOrNull(currentIndex)

    val isOpen: Boolean
        get() = isVisible && items.isNotEmpty()

    val remaining: List<IdentifyReviewItem>
        get() = items.drop(currentIndex)

    val pendingCount: Int
        get() = remaining.size

    val reviewOrdinal: Int
        get() = if (items.isEmpty()) 0 else (currentIndex + 1).coerceAtMost(items.size)

    val reviewTotal: Int
        get() = items.size

    val albumGroups: List<IdentifyAlbumGroup>
        get() = clusterIdentifyAlbumGroups(remaining.map { it.proposal })

    val ungroupedCount: Int
        get() {
            val groupedIds = albumGroups.flatMap { it.songIds }.toSet()
            return remaining.count { it.song.id !in groupedIds }
        }

    val headerSubtitle: String
        get() = when (phase) {
            IdentifyReviewPhase.Overview ->
                if (pendingCount == 1) "1 para revisar" else "$pendingCount para revisar"
            IdentifyReviewPhase.Item ->
                "Revisar $reviewOrdinal de $reviewTotal"
        }

    val canApplyRemaining: Boolean
        get() = remaining.any { it.proposal.hasMediumSuggestion }

    val pendingSongIds: Set<Long>
        get() = remaining.map { it.song.id }.toSet()

    val searchFilters: IdentifySearchFilters
        get() = IdentifySearchFilters(
            artist = searchFilterArtist,
            album = searchFilterAlbum,
            year = searchFilterYear.toIntOrNull() ?: 0
        )

    val visibleCandidates: List<IdentifyCandidate>
        get() {
            val all = current?.proposal?.candidates.orEmpty()
            return all.take(visibleCandidateCount.coerceIn(0, all.size))
        }

    val canShowMoreCandidates: Boolean
        get() {
            val proposal = current?.proposal ?: return false
            val all = proposal.candidates
            return visibleCandidateCount < all.size || proposal.catalogMayHaveMore
        }
}

val IdentifyProposal.hasMediumSuggestion: Boolean
    get() = confidence == IdentifyConfidence.MEDIUM && suggested != null

fun identifyReviewPhaseOrItem(name: String): IdentifyReviewPhase =
    runCatching { IdentifyReviewPhase.valueOf(name) }.getOrDefault(IdentifyReviewPhase.Item)

fun identifyReviewFromPersisted(
    proposals: List<IdentifyProposal>,
    phaseName: String,
    songs: List<Song>,
    applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL
): IdentifyReviewState {
    if (proposals.isEmpty()) return IdentifyReviewState(applyFields = applyFields)
    val byId = songs.associateBy { it.id }
    val items = proposals.mapNotNull { proposal ->
        byId[proposal.songId]?.let { IdentifyReviewItem(it, proposal) }
    }
    if (items.isEmpty()) return IdentifyReviewState(applyFields = applyFields)
    val requested = identifyReviewPhaseOrItem(phaseName)
    val phase = if (requested == IdentifyReviewPhase.Overview &&
        clusterIdentifyAlbumGroups(items.map { it.proposal }).isEmpty()
    ) {
        IdentifyReviewPhase.Item
    } else {
        requested
    }
    return IdentifyReviewState(
        items = items,
        currentIndex = 0,
        phase = phase,
        isVisible = false,
        applyFields = applyFields
    )
}
