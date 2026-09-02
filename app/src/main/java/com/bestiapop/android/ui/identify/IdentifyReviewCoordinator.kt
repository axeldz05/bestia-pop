package com.bestiapop.android.ui.identify

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyApplyRequest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.PersistedIdentifyReviewQueue
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.util.IdentifyAlbumGroup
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.KnownAlbumMatch
import com.bestiapop.android.domain.util.albumGroupKey
import com.bestiapop.android.domain.util.assignUniqueKnownAlbumMatches
import com.bestiapop.android.domain.util.clusterIdentifyAlbumGroups
import com.bestiapop.android.domain.util.gapApplyFields
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.needsGapIdentify
import com.bestiapop.android.domain.util.toIdentifyCandidate
import com.bestiapop.android.service.ProcessIdentifyEvent
import com.bestiapop.android.service.ProcessIdentifyRuntime
import com.bestiapop.android.ui.state.IdentifyPersistEcho
import com.bestiapop.android.ui.state.IdentifyReviewItem
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.IdentifyReviewState
import com.bestiapop.android.ui.state.IdentifySetupState
import com.bestiapop.android.ui.state.attachKnownAlbumMatches
import com.bestiapop.android.ui.state.hasMediumSuggestion
import com.bestiapop.android.ui.state.identifyPersistEcho
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import com.bestiapop.android.ui.state.identifySearchDraft
import com.bestiapop.android.ui.state.identifySearchFilterAlbum
import com.bestiapop.android.ui.state.identifySearchFilterArtist
import com.bestiapop.android.ui.state.identifySearchFilterYear
import com.bestiapop.android.ui.state.leftoverIdentifyReview
import com.bestiapop.android.ui.state.mergeIncomingReviewItems
import com.bestiapop.android.ui.state.seedIdentifySearch
import com.bestiapop.android.ui.state.withGapApplyFields
import com.bestiapop.android.ui.state.withItemSearchChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Encapsulates the UI state and orchestration for song metadata identification and review.
 * Extracted from [com.bestiapop.android.ui.MusicPlayerViewModel] as part of architectural decoupling.
 */
@OptIn(FlowPreview::class)
class IdentifyReviewCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val identifyReviewStore: IdentifyReviewStore,
    private val processIdentifyRuntime: ProcessIdentifyRuntime,
    private val rawSongs: StateFlow<List<Song>>,
    private val awaitCatalogLoaded: suspend () -> Unit,
    private val clearCatalogPreview: () -> Unit,
    private val toast: (String) -> Unit,
    private val uiAttached: () -> Boolean
) {
    private val _identifyReview = MutableStateFlow(IdentifyReviewState())
    val identifyReview: StateFlow<IdentifyReviewState> = _identifyReview.asStateFlow()

    private val _identifySetup = MutableStateFlow<IdentifySetupState?>(null)
    val identifySetup: StateFlow<IdentifySetupState?> = _identifySetup.asStateFlow()

    private val identifyMutex = Mutex()
    private val identifyDroppedIds = mutableSetOf<Long>()

    init {
        scope.launch {
            identifyReviewStore.queueFlow.collect { snap ->
                if (snap.proposals.isNotEmpty()) {
                    awaitCatalogLoaded()
                }
                applyPersistedIdentifyQueue(snap)
            }
        }

        scope.launch {
            identifyReview
                .map { state ->
                    Triple(
                        PersistedIdentifyReviewQueue(
                            proposals = state.items.drop(state.currentIndex).map { it.proposal },
                            phase = state.phase.name,
                            applyFields = state.applyFields
                        ),
                        state.items.map { it.song.id }.toSet(),
                        identifyDroppedIds.toSet()
                    )
                }
                .distinctUntilChanged()
                .debounce(300)
                .collect { (snap, knownIds, dropped) ->
                    withContext(Dispatchers.IO) {
                        identifyReviewStore.mergeUiRemaining(
                            remaining = snap.proposals,
                            knownSongIds = knownIds,
                            droppedIds = dropped,
                            phase = snap.phase,
                            applyFields = snap.applyFields
                        )
                    }
                }
        }

        scope.launch {
            processIdentifyRuntime.events.collect { event ->
                when (event) {
                    is ProcessIdentifyEvent.Completed -> {
                        toast(event.summary.toastMessage())
                        if (event.summary.showReview &&
                            event.summary.reviewCount > 0 &&
                            uiAttached()
                        ) {
                            showIdentifyReview()
                        }
                    }

                    is ProcessIdentifyEvent.AlreadyQueued -> {
                        toast(
                            if (event.count == 1) "1 ya está en revisión"
                            else "${event.count} ya están en revisión"
                        )
                        if (event.showReview && uiAttached()) showIdentifyReview()
                    }
                }
            }
        }
    }

    /**
     * Submit songs for online metadata identification. Automatic triggers (import/WiFi) only
     * enqueue MEDIUM/LOW/NONE. [force] always looks up (manual setup). [showReview] opens the overlay.
     * Songs already pending review are skipped (no network).
     */
    fun identifySongs(
        songs: List<Song>,
        force: Boolean = false,
        showReview: Boolean = true,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL,
        fillGapsOnly: Boolean = false
    ) {
        if (songs.isEmpty()) return
        identifyDroppedIds.removeAll(songs.map { it.id }.toSet())
        processIdentifyRuntime.submit(songs, force, showReview, fields, fillGapsOnly)
    }

    /** Import/WiFi: identify only songs with missing/placeholder tags; do not open overlay. */
    fun identifyImportedGaps(songs: List<Song>) {
        val targets = songs.filter { needsGapIdentify(it) }
        identifySongs(
            songs = targets,
            force = false,
            showReview = false,
            fillGapsOnly = true
        )
    }

    /** Single-song identify: open existing pending item, or open setup configuration dialog. */
    fun identifySongForReview(song: Song) {
        val state = _identifyReview.value
        val pendingIndex = state.remaining.indexOfFirst { it.song.id == song.id }
        if (pendingIndex >= 0) {
            val absIndex = state.currentIndex + pendingIndex
            val item = state.items[absIndex]
            _identifyReview.value = state.copy(
                currentIndex = absIndex,
                phase = IdentifyReviewPhase.Item,
                openedFromOverview = state.phase == IdentifyReviewPhase.Overview ||
                        state.openedFromOverview,
                isVisible = true
            ).withItemSearchChrome(item)
            return
        }
        openIdentifySetup(listOf(song), contextTitle = song.title)
    }

    fun openIdentifySetup(songs: List<Song>, contextTitle: String = "") {
        if (songs.isEmpty()) return
        _identifySetup.value = IdentifySetupState(
            songs = songs,
            applyFields = IdentifyApplyFields.ALL,
            contextTitle = contextTitle
        )
    }

    fun setIdentifySetupFields(fields: IdentifyApplyFields) {
        _identifySetup.update { it?.copy(applyFields = fields) }
    }

    fun setIdentifyReviewApplyFields(fields: IdentifyApplyFields) {
        _identifyReview.update { it.copy(applyFields = fields) }
    }

    fun dismissIdentifySetup() {
        _identifySetup.value = null
    }

    fun confirmIdentifySetup() {
        val current = _identifySetup.value ?: return
        _identifySetup.value = null
        identifySongs(
            songs = current.songs,
            force = true,
            showReview = true,
            fields = current.applyFields
        )
    }

    private suspend fun applyPersistedIdentifyQueue(snap: PersistedIdentifyReviewQueue) {
        val current = _identifyReview.value
        val echo = identifyPersistEcho(
            overlayOpen = current.isOpen,
            itemIds = current.items.map { it.song.id }.toSet(),
            snapSongIds = snap.proposals.map { it.songId },
            droppedIds = identifyDroppedIds.toSet()
        )
        when (echo) {
            IdentifyPersistEcho.Skip -> {
                if (snap.applyFields != current.applyFields) {
                    identifyMutex.withLock { applyPersistedIdentifyFields(snap.applyFields) }
                }
            }

            is IdentifyPersistEcho.MergeExtras -> {
                val extraItems = hydratePersistedIdentifyItems(
                    proposals = snap.proposals.filter { it.songId in echo.songIds.toSet() },
                    phaseName = snap.phase,
                    songIds = echo.songIds,
                    applyFields = snap.applyFields
                )
                identifyMutex.withLock {
                    _identifyReview.value = _identifyReview.value.mergeIncomingReviewItems(
                        extraItems,
                        identifyDroppedIds
                    )
                }
            }

            is IdentifyPersistEcho.Hydrate -> {
                val queued = songsForIdentifyEcho(echo.songIds)
                val library = rawSongs.value.ifEmpty { queued }
                val hydrated = identifyReviewFromPersisted(
                    snap.proposals,
                    snap.phase,
                    library,
                    snap.applyFields
                )
                val items = hydrated.items.map { item ->
                    IdentifyReviewItem(songForIdentifyReview(item.song, item.proposal), item.proposal)
                }
                identifyMutex.withLock {
                    publishHydratedIdentifyQueue(snap, hydrated.copy(items = items))
                }
            }
        }
    }

    private suspend fun hydratePersistedIdentifyItems(
        proposals: List<IdentifyProposal>,
        phaseName: String,
        songIds: List<Long>,
        applyFields: IdentifyApplyFields
    ): List<IdentifyReviewItem> {
        if (songIds.isEmpty() || proposals.isEmpty()) return emptyList()
        val queued = songsForIdentifyEcho(songIds)
        val library = rawSongs.value.ifEmpty { queued }
        return identifyReviewFromPersisted(
            proposals,
            phaseName,
            library,
            applyFields
        ).items.map { item ->
            IdentifyReviewItem(songForIdentifyReview(item.song, item.proposal), item.proposal)
        }
    }

    private suspend fun songsForIdentifyEcho(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val cached = rawSongs.value.associateBy { it.id }
        val fromCache = ids.mapNotNull { cached[it] }
        if (fromCache.size == ids.size) return fromCache
        return withContext(Dispatchers.IO) { repository.getSongsByIds(ids) }
    }

    private fun applyPersistedIdentifyFields(applyFields: IdentifyApplyFields) {
        val current = _identifyReview.value
        if (applyFields != current.applyFields) {
            _identifyReview.value = current.copy(applyFields = applyFields)
        }
    }

    private fun publishHydratedIdentifyQueue(
        snap: PersistedIdentifyReviewQueue,
        hydrated: IdentifyReviewState
    ) {
        val current = _identifyReview.value
        if (hydrated.items.isEmpty()) {
            if (current.items.isEmpty()) {
                applyPersistedIdentifyFields(snap.applyFields)
                return
            }
            _identifyReview.value = current.copy(
                items = emptyList(),
                currentIndex = 0,
                isVisible = false,
                phase = IdentifyReviewPhase.Item,
                applyFields = snap.applyFields
            )
            clearCatalogPreview()
            return
        }
        val items = hydrated.items
        if (current.isVisible && current.items.isNotEmpty()) {
            _identifyReview.value = current.mergeIncomingReviewItems(items, identifyDroppedIds)
            return
        }
        val currentSongId = current.current?.song?.id
        val newIndex = currentSongId?.let { id -> items.indexOfFirst { it.song.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val phase = if (current.isVisible) current.phase else hydrated.phase
        val next = current.copy(
            items = items,
            currentIndex = newIndex,
            phase = phase,
            applyFields = snap.applyFields,
            isVisible = current.isVisible && items.isNotEmpty()
        )
        _identifyReview.value = if (phase == IdentifyReviewPhase.Item) {
            val focused = items.getOrNull(newIndex) ?: items.first()
            next.withItemSearchChrome(focused)
        } else {
            next
        }
    }

    private fun reviewPhaseFor(items: List<IdentifyReviewItem>): IdentifyReviewPhase =
        if (clusterIdentifyAlbumGroups(items.map { it.proposal }).isNotEmpty()) {
            IdentifyReviewPhase.Overview
        } else {
            IdentifyReviewPhase.Item
        }

    suspend fun presentIdentifyQueue(
        items: List<IdentifyReviewItem>,
        showReview: Boolean,
        sessionApplied: Int = 0,
        sessionSkipped: Int = 0,
        openedFromOverview: Boolean = false,
        applyFields: IdentifyApplyFields = _identifyReview.value.applyFields
    ) {
        if (items.isEmpty()) {
            _identifyReview.value = IdentifyReviewState(applyFields = applyFields)
            clearCatalogPreview()
            return
        }
        val library = rawSongs.value.ifEmpty {
            withContext(Dispatchers.IO) { repository.getAllSongsSync() }
        }
        val attached = attachKnownAlbumMatches(items, library)
        val phase = reviewPhaseFor(attached)
        val first = attached.first()
        val showSearch = phase == IdentifyReviewPhase.Item && first.proposal.candidates.isEmpty()
        _identifyReview.value = IdentifyReviewState(
            items = attached,
            currentIndex = 0,
            sessionApplied = sessionApplied,
            sessionSkipped = sessionSkipped,
            isVisible = showReview,
            phase = phase,
            openedFromOverview = openedFromOverview && phase == IdentifyReviewPhase.Item,
            applyFields = applyFields
        ).let { base ->
            if (phase == IdentifyReviewPhase.Item) {
                base.withItemSearchChrome(first, forceShowSearch = showSearch)
            } else {
                base
            }
        }
    }

    private suspend fun applyKnownAlbumFanOut(
        artist: String,
        album: String,
        remaining: List<IdentifyReviewItem>
    ): Set<Long> {
        if (remaining.isEmpty()) return emptySet()
        if (IdentifyRanking.isGenericAlbum(album) || IdentifyRanking.isPlaceholderArtist(artist)) {
            return emptySet()
        }
        val queries = remaining.map { knownAlbumQueryOf(it.song, queryTitle = it.proposal.queryTitle) }
        val libraryKnown = withContext(Dispatchers.IO) {
            repository.loadKnownAlbumTracks(artist, album, fetchCatalog = false)
        }
        val libraryMatches = if (libraryKnown != null) {
            assignUniqueKnownAlbumMatches(
                queries = queries,
                albums = listOf(libraryKnown),
                scoped = true
            )
        } else {
            emptyMap()
        }
        val unmatched = remaining.filter { it.song.id !in libraryMatches }
        val catalogMatches = if (unmatched.isEmpty()) {
            emptyMap()
        } else {
            val catalogKnown = withContext(Dispatchers.IO) {
                repository.loadKnownAlbumTracks(artist, album, fetchCatalog = true)
            } ?: return applyKnownAlbumMatches(remaining, libraryMatches)
            assignUniqueKnownAlbumMatches(
                queries = unmatched.map {
                    knownAlbumQueryOf(it.song, queryTitle = it.proposal.queryTitle)
                },
                albums = listOf(catalogKnown),
                scoped = true
            )
        }
        return applyKnownAlbumMatches(remaining, libraryMatches + catalogMatches)
    }

    private suspend fun applyKnownAlbumMatches(
        remaining: List<IdentifyReviewItem>,
        matches: Map<Long, KnownAlbumMatch>
    ): Set<Long> {
        if (matches.isEmpty()) return emptySet()
        val targets = remaining.filter { it.song.id in matches }
        return applyIdentifyCandidates(targets) { item ->
            matches[item.song.id]?.toIdentifyCandidate()
        }
    }

    private fun knownAlbumFanOutLabel(album: String, extra: Int): String? = when {
        extra <= 0 -> null
        extra == 1 -> "1 más de $album aplicada"
        else -> "$extra más de $album aplicadas"
    }

    private fun appliedInReviewLabel(count: Int): String =
        if (count == 1) "1 aplicada en revisión" else "$count aplicadas en revisión"

    private fun publishIdentifyLeftover(
        leftover: List<IdentifyReviewItem>,
        sessionApplied: Int,
        sessionSkipped: Int,
        persist: Boolean
    ) {
        val next = leftoverIdentifyReview(
            leftover = leftover,
            sessionApplied = sessionApplied,
            sessionSkipped = sessionSkipped,
            applyFields = _identifyReview.value.applyFields,
            isVisible = leftover.isNotEmpty(),
            isApplying = false
        )
        if (leftover.isEmpty()) {
            clearCatalogPreview()
        }
        _identifyReview.value = next
        if (persist) persistIdentifyReviewNow(next)
    }

    private fun identifyApplyRequests(
        targets: List<IdentifyReviewItem>,
        fieldsOverride: IdentifyApplyFields? = null,
        pick: (IdentifyReviewItem) -> IdentifyCandidate?
    ): List<IdentifyApplyRequest> {
        val defaultFields = fieldsOverride ?: _identifyReview.value.applyFields
        return targets.mapNotNull { item ->
            val candidate = pick(item) ?: return@mapNotNull null
            IdentifyApplyRequest(
                songId = item.song.id,
                candidate = candidate,
                fields = fieldsOverride ?: if (item.proposal.fillGapsOnly) {
                    gapApplyFields(item.song)
                } else {
                    defaultFields
                }
            )
        }
    }

    private data class IdentifyApplyCommit(
        val remainingBefore: List<IdentifyReviewItem>,
        val leftover: List<IdentifyReviewItem>,
        val requests: List<IdentifyApplyRequest>,
        val targetIds: Set<Long>,
        val sessionApplied: Int,
        val sessionSkipped: Int
    )

    /** Publish leftover on the calling thread. Caller must not hold work after this. */
    private fun beginOptimisticIdentifyApply(
        remaining: List<IdentifyReviewItem>,
        targets: List<IdentifyReviewItem>,
        sessionApplied: Int,
        sessionSkipped: Int,
        fieldsOverride: IdentifyApplyFields? = null,
        pick: (IdentifyReviewItem) -> IdentifyCandidate?
    ): IdentifyApplyCommit? {
        val requests = identifyApplyRequests(targets, fieldsOverride, pick)
        if (requests.isEmpty()) return null
        val targetIds = requests.map { it.songId }.toSet()
        identifyDroppedIds += targetIds
        val leftover = remaining.filter { it.song.id !in targetIds }
        publishIdentifyLeftover(
            leftover = leftover,
            sessionApplied = sessionApplied + targetIds.size,
            sessionSkipped = sessionSkipped,
            persist = false
        )
        return IdentifyApplyCommit(
            remainingBefore = remaining,
            leftover = leftover,
            requests = requests,
            targetIds = targetIds,
            sessionApplied = sessionApplied,
            sessionSkipped = sessionSkipped
        )
    }

    /** Room + DataStore after leftover is already visible. Null if the queue was restored. */
    private suspend fun confirmOptimisticIdentifyApply(
        commit: IdentifyApplyCommit
    ): List<IdentifyReviewItem>? {
        val applied = try {
            withContext(Dispatchers.IO) {
                repository.applySongIdentities(commit.requests)
            }
        } catch (cancelled: CancellationException) {
            restoreOptimisticIdentifyApply(commit)
            throw cancelled
        } catch (_: Exception) {
            restoreOptimisticIdentifyApply(commit)
            toast("No se pudo aplicar la identidad")
            return null
        }
        if (applied.isEmpty()) {
            restoreOptimisticIdentifyApply(commit)
            toast("No se pudo aplicar la identidad")
            return null
        }
        val leftover = commit.leftover
        val committed = if (applied.size == commit.targetIds.size) {
            leftover
        } else {
            identifyDroppedIds -= (commit.targetIds - applied)
            commit.remainingBefore.filter { it.song.id !in applied }
        }
        if (committed !== leftover) {
            publishIdentifyLeftover(
                leftover = committed,
                sessionApplied = commit.sessionApplied + applied.size,
                sessionSkipped = commit.sessionSkipped,
                persist = committed.isEmpty()
            )
        } else if (committed.isEmpty()) {
            persistIdentifyReviewNow(_identifyReview.value)
        }
        return committed
    }

    private fun restoreOptimisticIdentifyApply(commit: IdentifyApplyCommit) {
        identifyDroppedIds -= commit.targetIds
        publishIdentifyLeftover(
            leftover = commit.remainingBefore,
            sessionApplied = commit.sessionApplied,
            sessionSkipped = commit.sessionSkipped,
            persist = true
        )
    }

    private fun launchKnownAlbumFanOut(
        albums: List<Pair<String, String>>,
        sessionSkipped: Int,
        extrasMessage: (extras: Int, leftover: List<IdentifyReviewItem>) -> String?
    ) {
        if (albums.isEmpty()) return
        scope.launch {
            val extraIds = LinkedHashSet<Long>()
            for ((artist, album) in albums) {
                val remaining = _identifyReview.value.remaining
                if (remaining.isEmpty()) break
                extraIds += applyKnownAlbumFanOut(artist, album, remaining)
            }
            if (extraIds.isEmpty()) return@launch
            identifyMutex.withLock {
                val state = _identifyReview.value
                val leftover = state.remaining.filter { it.song.id !in extraIds }
                identifyDroppedIds += extraIds
                publishIdentifyLeftover(
                    leftover = leftover,
                    sessionApplied = state.sessionApplied + extraIds.size,
                    sessionSkipped = sessionSkipped,
                    persist = leftover.isEmpty()
                )
                extrasMessage(extraIds.size, leftover)?.let { toast(it) }
            }
        }
    }

    private fun persistIdentifyReviewNow(state: IdentifyReviewState) {
        val remaining = state.items.drop(state.currentIndex).map { it.proposal }
        val knownIds = state.items.map { it.song.id }.toSet()
        val dropped = identifyDroppedIds.toSet()
        val phase = state.phase.name
        val fields = state.applyFields
        scope.launch {
            withContext(Dispatchers.IO) {
                identifyReviewStore.mergeUiRemaining(
                    remaining = remaining,
                    knownSongIds = knownIds,
                    droppedIds = dropped,
                    phase = phase,
                    applyFields = fields
                )
            }
        }
    }

    fun showIdentifyReview() {
        val state = _identifyReview.value
        if (state.items.isEmpty()) return
        if (state.isVisible) return
        val current = state.current ?: state.items.first()
        val visible = state.copy(isVisible = true)
        _identifyReview.value = if (state.phase == IdentifyReviewPhase.Item) {
            visible.seedIdentifySearch(
                current,
                forceShowSearch = state.showSearchField || current.proposal.candidates.isEmpty()
            ).withGapApplyFields(current)
        } else {
            visible
        }
    }

    fun startIdentifyItemReview(groupKey: String? = null) {
        val state = _identifyReview.value
        val remaining = state.remaining
        if (remaining.isEmpty()) return
        val reordered = if (groupKey != null) {
            val groupIds = state.albumGroups.find { it.key == groupKey }?.songIds?.toSet()
                ?: return
            remaining.filter { it.song.id in groupIds } +
                    remaining.filter { it.song.id !in groupIds }
        } else {
            remainingGroupedFirst(remaining, state.albumGroups)
        }
        val first = reordered.first()
        _identifyReview.value = state.copy(
            items = reordered,
            currentIndex = 0,
            phase = IdentifyReviewPhase.Item,
            openedFromOverview = true,
            isVisible = true
        ).withItemSearchChrome(first)
    }

    fun returnIdentifyReviewOverview() {
        val state = _identifyReview.value
        val remaining = state.remaining
        if (remaining.isEmpty()) {
            _identifyReview.value = IdentifyReviewState()
            clearCatalogPreview()
            return
        }
        val phase = reviewPhaseFor(remaining)
        val first = remaining.first()
        val base = state.copy(
            items = remaining,
            currentIndex = 0,
            phase = phase,
            openedFromOverview = false,
            selectedCandidateIndex = 0,
            isSearching = false
        )
        _identifyReview.value = if (phase == IdentifyReviewPhase.Item) {
            base.withItemSearchChrome(first)
        } else {
            base.copy(
                searchQueryDraft = "",
                showSearchField = false,
                showSearchFilters = false,
                searchFilterArtist = "",
                searchFilterAlbum = "",
                searchFilterYear = ""
            )
        }
    }

    fun applyIdentifyAlbumGroup(key: String) {
        scope.launch {
            val pending = identifyMutex.withLock {
                val state = _identifyReview.value
                if (state.isApplying || !state.applyFields.hasAny) return@withLock null
                val group = state.albumGroups.find { it.key == key } ?: return@withLock null
                val groupIds = group.songIds.toSet()
                val remaining = state.remaining
                val targets = remaining.filter { it.song.id in groupIds }
                if (targets.isEmpty()) return@withLock null
                val commit = beginOptimisticIdentifyApply(
                    remaining = remaining,
                    targets = targets,
                    sessionApplied = state.sessionApplied,
                    sessionSkipped = state.sessionSkipped
                ) { it.proposal.suggested } ?: return@withLock null
                toast(
                    if (targets.size == 1) "1 aplicada al álbum"
                    else "${targets.size} aplicadas al álbum"
                )
                val fanOut = if (commit.leftover.isEmpty()) {
                    null
                } else {
                    Triple(group.artist, group.album, state.sessionSkipped)
                }
                commit to fanOut
            } ?: return@launch
            val leftover = confirmOptimisticIdentifyApply(pending.first) ?: return@launch
            val fanOut = pending.second ?: return@launch
            if (leftover.isEmpty()) return@launch
            launchKnownAlbumFanOut(
                albums = listOf(fanOut.first to fanOut.second),
                sessionSkipped = fanOut.third
            ) { extras, _ -> knownAlbumFanOutLabel(fanOut.second, extras) }
        }
    }

    private fun remainingGroupedFirst(
        remaining: List<IdentifyReviewItem>,
        groups: List<IdentifyAlbumGroup>
    ): List<IdentifyReviewItem> {
        if (groups.isEmpty()) return remaining
        val groupedIds = groups.flatMap { it.songIds }.toSet()
        val grouped = groups.flatMap { group ->
            val ids = group.songIds.toSet()
            remaining.filter { it.song.id in ids }
        }
        val ungrouped = remaining.filter { it.song.id !in groupedIds }
        return grouped + ungrouped
    }

    fun pruneIdentifyReview(ids: Set<Long>) {
        if (ids.isEmpty()) return
        identifyDroppedIds += ids
        scope.launch {
            withContext(Dispatchers.IO) { identifyReviewStore.removeSongIds(ids) }
        }
        val state = _identifyReview.value
        if (state.items.none { it.song.id in ids }) return
        val before = state.items.take(state.currentIndex).filter { it.song.id !in ids }
        val after = state.items.drop(state.currentIndex).filter { it.song.id !in ids }
        if (after.isEmpty()) {
            _identifyReview.value = IdentifyReviewState()
            clearCatalogPreview()
            return
        }
        val next = after.first()
        val phase = if (state.phase == IdentifyReviewPhase.Overview) {
            reviewPhaseFor(after)
        } else {
            state.phase
        }
        val base = state.copy(
            items = before + after,
            currentIndex = before.size,
            phase = phase,
            selectedCandidateIndex = 0
        )
        _identifyReview.value = if (phase == IdentifyReviewPhase.Item) {
            base.withItemSearchChrome(next)
        } else {
            base
        }
    }

    private suspend fun applyIdentifyCandidates(
        targets: List<IdentifyReviewItem>,
        fieldsOverride: IdentifyApplyFields? = null,
        pick: (IdentifyReviewItem) -> IdentifyCandidate?
    ): Set<Long> {
        if (targets.isEmpty()) return emptySet()
        val defaultFields = fieldsOverride ?: _identifyReview.value.applyFields
        val requests = targets.mapNotNull { item ->
            val candidate = pick(item) ?: return@mapNotNull null
            IdentifyApplyRequest(
                songId = item.song.id,
                candidate = candidate,
                fields = fieldsOverride ?: if (item.proposal.fillGapsOnly) {
                    gapApplyFields(item.song)
                } else {
                    defaultFields
                }
            )
        }
        if (requests.isEmpty()) return emptySet()
        val appliedIds = withContext(Dispatchers.IO) {
            repository.applySongIdentities(requests)
        }
        if (appliedIds.isNotEmpty()) {
            identifyDroppedIds += appliedIds
        }
        return appliedIds
    }

    private fun songForIdentifyReview(song: Song, proposal: IdentifyProposal): Song {
        val qTitle = proposal.queryTitle.trim()
        val qArtist = proposal.queryArtist.trim()
        return song.copy(
            title = qTitle.takeUnless { it.isEmpty() || looksLikeStoragePath(it) } ?: song.title,
            artist = when {
                qArtist.isNotEmpty() && !IdentifyRanking.isPlaceholderArtist(qArtist) -> qArtist
                IdentifyRanking.isPlaceholderArtist(song.artist) || isTrackNumberLabel(song.artist) ->
                    "Unknown Artist"

                else -> song.artist
            },
            lyrics = null
        )
    }

    fun selectIdentifyCandidate(index: Int) {
        val state = _identifyReview.value
        val candidates = state.visibleCandidates
        if (index !in candidates.indices) return
        _identifyReview.value = state.copy(selectedCandidateIndex = index)
    }

    fun setIdentifySearchDraft(query: String) {
        _identifyReview.value = _identifyReview.value.copy(searchQueryDraft = query)
    }

    fun setIdentifySearchFilterArtist(value: String) {
        _identifyReview.value = _identifyReview.value.copy(searchFilterArtist = value)
    }

    fun setIdentifySearchFilterAlbum(value: String) {
        _identifyReview.value = _identifyReview.value.copy(searchFilterAlbum = value)
    }

    fun setIdentifySearchFilterYear(value: String) {
        _identifyReview.value = _identifyReview.value.copy(
            searchFilterYear = value.filter { it.isDigit() }.take(4)
        )
    }

    fun toggleIdentifySearchField(show: Boolean? = null) {
        val state = _identifyReview.value
        val next = show ?: !state.showSearchField
        if (!next) {
            _identifyReview.value = state.copy(showSearchField = false, showSearchFilters = false)
            return
        }
        val item = state.current
        val draft = state.searchQueryDraft
        val shouldSeedDraft = draft.isBlank() || looksLikeStoragePath(draft)
        val shouldSeedFilters = state.searchFilterArtist.isBlank() &&
                state.searchFilterAlbum.isBlank() &&
                state.searchFilterYear.isBlank()
        val artist = if (shouldSeedFilters && item != null) {
            identifySearchFilterArtist(item)
        } else {
            state.searchFilterArtist
        }
        val album = if (shouldSeedFilters && item != null) {
            identifySearchFilterAlbum(item)
        } else {
            state.searchFilterAlbum
        }
        val year = if (shouldSeedFilters && item != null) {
            identifySearchFilterYear(item)
        } else {
            state.searchFilterYear
        }
        val hasFilters = artist.isNotBlank() || album.isNotBlank() || year.isNotBlank()
        _identifyReview.value = state.copy(
            showSearchField = true,
            showSearchFilters = hasFilters || state.showSearchFilters,
            searchQueryDraft = if (shouldSeedDraft) {
                item?.let { identifySearchDraft(it) }.orEmpty()
            } else {
                draft
            },
            searchFilterArtist = artist,
            searchFilterAlbum = album,
            searchFilterYear = year
        )
    }

    fun toggleIdentifySearchFilters(show: Boolean? = null) {
        val state = _identifyReview.value
        if (!state.showSearchField && show != false) {
            toggleIdentifySearchField(show = true)
        }
        val latest = _identifyReview.value
        val next = show ?: !latest.showSearchFilters
        val item = latest.current
        val seed = next && item != null &&
                latest.searchFilterArtist.isBlank() &&
                latest.searchFilterAlbum.isBlank() &&
                latest.searchFilterYear.isBlank()
        _identifyReview.value = latest.copy(
            showSearchFilters = next,
            searchFilterArtist = if (seed) identifySearchFilterArtist(item!!) else latest.searchFilterArtist,
            searchFilterAlbum = if (seed) identifySearchFilterAlbum(item!!) else latest.searchFilterAlbum,
            searchFilterYear = if (seed) identifySearchFilterYear(item!!) else latest.searchFilterYear
        )
    }

    fun searchIdentifyCandidates() {
        val state = _identifyReview.value
        val item = state.current ?: return
        val query = state.searchQueryDraft.trim()
        val filters = state.searchFilters.normalized()
        if (query.isEmpty() && !filters.hasAny) {
            toast("Escribí una búsqueda o un filtro")
            return
        }
        scope.launch {
            _identifyReview.value = _identifyReview.value.copy(isSearching = true, isLoadingMore = false)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(
                    song = item.song,
                    customQuery = query.ifBlank { null },
                    force = true,
                    filters = filters
                )
            }
            val latest = _identifyReview.value
            if (latest.current?.song?.id != item.song.id) {
                _identifyReview.value = latest.copy(isSearching = false)
                return@launch
            }
            val items = latest.items.toMutableList()
            items[latest.currentIndex] = item.copy(proposal = proposal)
            _identifyReview.value = latest.copy(
                items = items,
                selectedCandidateIndex = 0,
                isSearching = false,
                showSearchField = latest.showSearchField || proposal.candidates.isEmpty(),
                visibleCandidateCount = minOf(IdentifyRanking.TOP_N, proposal.candidates.size)
            )
            if (proposal.candidates.isEmpty()) {
                val label = query.ifBlank { "esos filtros" }
                toast("Sin resultados para \"$label\"")
            }
        }
    }

    fun loadMoreIdentifyCandidates() {
        val state = _identifyReview.value
        val item = state.current ?: return
        if (state.isSearching || state.isLoadingMore) return
        val all = item.proposal.candidates
        val visible = state.visibleCandidateCount
        if (visible < all.size) {
            _identifyReview.value = state.copy(
                visibleCandidateCount = (visible + IdentifyRanking.PAGE_SIZE)
                    .coerceAtMost(all.size)
            )
            return
        }
        if (!item.proposal.catalogMayHaveMore) {
            toast("No hay más candidatos")
            return
        }
        val query = state.searchQueryDraft.trim()
        val filters = state.searchFilters.normalized()
        scope.launch {
            _identifyReview.value = _identifyReview.value.copy(isLoadingMore = true)
            val proposal = withContext(Dispatchers.IO) {
                repository.proposeSongIdentity(
                    song = item.song,
                    customQuery = query.ifBlank { null },
                    force = true,
                    filters = filters,
                    catalogIndex = item.proposal.nextCatalogIndex,
                    existingCandidates = all
                )
            }
            val latest = _identifyReview.value
            if (latest.current?.song?.id != item.song.id) {
                _identifyReview.value = latest.copy(isLoadingMore = false)
                return@launch
            }
            val items = latest.items.toMutableList()
            items[latest.currentIndex] = item.copy(proposal = proposal)
            val grew = proposal.candidates.size > all.size
            _identifyReview.value = latest.copy(
                items = items,
                isLoadingMore = false,
                visibleCandidateCount = if (grew) {
                    (visible + IdentifyRanking.PAGE_SIZE).coerceAtMost(proposal.candidates.size)
                } else {
                    proposal.candidates.size
                }
            )
            if (!grew) {
                toast("No hay más candidatos")
            }
        }
    }

    fun applySelectedIdentifyCandidate() {
        val state = _identifyReview.value
        if (state.isApplying || !state.applyFields.hasAny) return
        val item = state.current ?: return
        val candidate = state.visibleCandidates.getOrNull(state.selectedCandidateIndex)
            ?: item.proposal.suggested
        if (candidate == null) {
            toast("Elegí un candidato o buscá otro")
            return
        }
        scope.launch {
            val pending = identifyMutex.withLock {
                val latest = _identifyReview.value
                if (latest.isApplying) return@withLock null
                val current = latest.current ?: return@withLock null
                if (current.song.id != item.song.id) return@withLock null
                val commit = beginOptimisticIdentifyApply(
                    remaining = latest.remaining,
                    targets = listOf(current),
                    sessionApplied = latest.sessionApplied,
                    sessionSkipped = latest.sessionSkipped,
                    fieldsOverride = latest.applyFields
                ) { _ -> candidate } ?: return@withLock null
                val skippedSuffix = when {
                    latest.sessionSkipped <= 0 -> ""
                    latest.sessionSkipped == 1 -> ", 1 omitida"
                    else -> ", ${latest.sessionSkipped} omitidas"
                }
                toast(appliedInReviewLabel(1) + skippedSuffix)
                val fanOut = if (commit.leftover.isEmpty()) {
                    null
                } else {
                    Triple(candidate.artist, candidate.album, latest.sessionSkipped)
                }
                commit to fanOut
            } ?: return@launch
            val leftover = confirmOptimisticIdentifyApply(pending.first) ?: return@launch
            val fanOut = pending.second ?: return@launch
            if (leftover.isEmpty()) return@launch
            launchKnownAlbumFanOut(
                albums = listOf(fanOut.first to fanOut.second),
                sessionSkipped = fanOut.third
            ) { extras, _ -> knownAlbumFanOutLabel(fanOut.second, extras) }
        }
    }

    fun skipIdentifyReviewItem() {
        advanceIdentifyReview(applied = false)
    }

    fun dismissIdentifyReview() {
        val state = _identifyReview.value
        if (!state.isOpen) return
        _identifyReview.value = state.copy(isVisible = false)
        clearCatalogPreview()
    }

    fun skipAllIdentifyReview() {
        val remainingIds = _identifyReview.value.remaining.map { it.song.id }.toSet()
        identifyDroppedIds += remainingIds
        val pending = remainingIds.size
        val next = IdentifyReviewState()
        _identifyReview.value = next
        clearCatalogPreview()
        persistIdentifyReviewNow(next)
        if (pending > 0) {
            toast(if (pending == 1) "1 omitida" else "$pending omitidas")
        }
    }

    fun applyRemainingIdentifySuggestions() {
        scope.launch {
            val pending = identifyMutex.withLock {
                val state = _identifyReview.value
                if (state.isApplying || !state.applyFields.hasAny) return@withLock null
                val remaining = state.remaining
                if (remaining.isEmpty()) return@withLock null
                val applyable = remaining.filter { it.proposal.hasMediumSuggestion }
                if (applyable.isEmpty()) {
                    toast("No hay sugerencias automáticas")
                    return@withLock null
                }
                val commit = beginOptimisticIdentifyApply(
                    remaining = remaining,
                    targets = applyable,
                    sessionApplied = state.sessionApplied,
                    sessionSkipped = state.sessionSkipped
                ) { it.proposal.suggested } ?: return@withLock null
                val appliedCount = applyable.size
                val leftover = commit.leftover
                if (leftover.isEmpty()) {
                    toast(appliedInReviewLabel(appliedCount))
                } else {
                    toast(
                        buildString {
                            append(if (appliedCount == 1) "1 aplicada" else "$appliedCount aplicadas")
                            append(
                                if (leftover.size == 1) ", 1 sin sugerencia"
                                else ", ${leftover.size} sin sugerencia"
                            )
                        }
                    )
                }
                val albums = if (leftover.isEmpty()) {
                    emptyList()
                } else {
                    applyable.mapNotNull { it.proposal.suggested }
                        .distinctBy { albumGroupKey(it.artist, it.album) }
                        .map { it.artist to it.album }
                }
                commit to (albums to state.sessionSkipped)
            } ?: return@launch
            val leftover = confirmOptimisticIdentifyApply(pending.first) ?: return@launch
            val albums = pending.second.first
            if (leftover.isEmpty() || albums.isEmpty()) return@launch
            launchKnownAlbumFanOut(
                albums = albums,
                sessionSkipped = pending.second.second
            ) { extras, _ ->
                when {
                    extras <= 0 -> null
                    extras == 1 -> "1 más aplicada"
                    else -> "$extras más aplicadas"
                }
            }
        }
    }

    private fun advanceIdentifyReview(applied: Boolean) {
        val state = _identifyReview.value
        val droppedId = state.current?.song?.id
        if (droppedId != null) {
            identifyDroppedIds += droppedId
        }
        val nextApplied = state.sessionApplied + if (applied) 1 else 0
        val nextSkipped = state.sessionSkipped + if (applied) 0 else 1
        val nextIndex = state.currentIndex + 1
        if (nextIndex >= state.items.size) {
            val next = IdentifyReviewState()
            _identifyReview.value = next
            persistIdentifyReviewNow(next)
            toast(
                buildString {
                    append(appliedInReviewLabel(nextApplied))
                    if (nextSkipped > 0) {
                        append(if (nextSkipped == 1) ", 1 omitida" else ", $nextSkipped omitidas")
                    }
                }
            )
            return
        }
        val nextItem = state.items[nextIndex]
        val next = state.copy(
            currentIndex = nextIndex,
            sessionApplied = nextApplied,
            sessionSkipped = nextSkipped
        ).withItemSearchChrome(nextItem)
        _identifyReview.value = next
        persistIdentifyReviewNow(next)
    }
}
