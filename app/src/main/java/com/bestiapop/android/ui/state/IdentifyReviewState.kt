package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups
import com.bestiapop.android.domain.util.gapApplyFields
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.knownAlbumsFromLibrary
import com.bestiapop.android.domain.util.promoteKnownAlbumMatches

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
        get() = applyFields.hasAny && remaining.any { it.proposal.hasMediumSuggestion }

    val canApplySelected: Boolean
        get() = applyFields.hasAny && visibleCandidates.isNotEmpty()

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

fun identifySearchDraft(item: IdentifyReviewItem): String {
    val title = item.proposal.queryTitle.trim()
        .takeUnless { it.isBlank() || looksLikeStoragePath(it) }
        ?: item.song.title.trim().takeUnless { it.isBlank() || looksLikeStoragePath(it) }
        .orEmpty()
    return title
}

fun identifySearchFilterArtist(item: IdentifyReviewItem): String =
    item.proposal.queryArtist.trim().takeUnless {
        it.isBlank() || IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
    } ?: item.song.artist.trim().takeUnless {
        IdentifyRanking.isPlaceholderArtist(it) || looksLikeStoragePath(it)
    }.orEmpty()

fun identifySearchFilterAlbum(item: IdentifyReviewItem): String =
    item.song.album.trim().takeUnless {
        it.isBlank() || IdentifyRanking.isGenericAlbum(it)
    }.orEmpty()

fun identifySearchFilterYear(item: IdentifyReviewItem): String =
    item.song.year.takeIf { it in 1000..9999 }?.toString().orEmpty()

/**
 * Title in the free-text box; artist/album/year in dedicated filters.
 * Opens filters when search is shown and any filter has a seed.
 */
fun IdentifyReviewState.seedIdentifySearch(
    item: IdentifyReviewItem,
    forceShowSearch: Boolean? = null
): IdentifyReviewState {
    val showSearch = forceShowSearch ?: item.proposal.candidates.isEmpty()
    val artist = identifySearchFilterArtist(item)
    val album = identifySearchFilterAlbum(item)
    val year = identifySearchFilterYear(item)
    val hasFilters = artist.isNotBlank() || album.isNotBlank() || year.isNotBlank()
    return copy(
        searchQueryDraft = identifySearchDraft(item),
        showSearchField = showSearch,
        showSearchFilters = showSearch && hasFilters,
        searchFilterArtist = artist,
        searchFilterAlbum = album,
        searchFilterYear = year
    )
}

fun IdentifyReviewState.withItemSearchChrome(
    item: IdentifyReviewItem,
    forceShowSearch: Boolean? = null
): IdentifyReviewState = seedIdentifySearch(item, forceShowSearch).copy(
    isSearching = false,
    isLoadingMore = false,
    visibleCandidateCount = IdentifyRanking.TOP_N,
    selectedCandidateIndex = 0
).withGapApplyFields(item)

fun IdentifyReviewState.withGapApplyFields(item: IdentifyReviewItem): IdentifyReviewState =
    if (item.proposal.fillGapsOnly) copy(applyFields = gapApplyFields(item.song)) else this

/**
 * Overlay already open: append newly persisted songs without resetting search chrome,
 * candidate selection, or the current item's in-memory proposal.
 */
fun IdentifyReviewState.mergeIncomingReviewItems(
    incoming: List<IdentifyReviewItem>,
    droppedIds: Set<Long>
): IdentifyReviewState {
    if (!isVisible || items.isEmpty()) return this
    val existingIds = items.map { it.song.id }.toSet()
    val extras = incoming.filter { item ->
        item.song.id !in existingIds && item.song.id !in droppedIds
    }
    if (extras.isEmpty()) return this
    return copy(items = items + extras)
}

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
    val attached = attachKnownAlbumMatches(items, songs)
    val requested = identifyReviewPhaseOrItem(phaseName)
    val phase = if (requested == IdentifyReviewPhase.Overview &&
        clusterIdentifyAlbumGroups(attached.map { it.proposal }).isEmpty()
    ) {
        IdentifyReviewPhase.Item
    } else {
        requested
    }
    return IdentifyReviewState(
        items = attached,
        currentIndex = 0,
        phase = phase,
        isVisible = false,
        applyFields = applyFields
    )
}

fun attachKnownAlbumMatches(
    items: List<IdentifyReviewItem>,
    librarySongs: List<Song>
): List<IdentifyReviewItem> {
    if (items.isEmpty()) return items
    val albums = knownAlbumsFromLibrary(librarySongs)
    if (albums.isEmpty()) return items
    val queries = items.map { knownAlbumQueryOf(it.song, queryTitle = it.proposal.queryTitle) }
    val promoted = promoteKnownAlbumMatches(items.map { it.proposal }, queries, albums)
    val byId = promoted.associateBy { it.songId }
    return items.map { item ->
        val proposal = byId[item.song.id] ?: return@map item
        if (proposal == item.proposal) item else item.copy(proposal = proposal)
    }
}
