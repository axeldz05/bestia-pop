package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyReviewStateTest {

    private fun song(
        id: Long = 7L,
        title: String = "Creep",
        artist: String = "Radiohead",
        album: String = "Pablo Honey",
        year: Int = 1993,
        durationMs: Long = 238_000L
    ) = Song(
        id = id,
        uriString = "file:///tmp/$id.mp3",
        title = title,
        artist = artist,
        album = album,
        year = year,
        durationMs = durationMs
    )

    private fun item(
        song: Song = song(),
        queryTitle: String = song.title,
        queryArtist: String = song.artist,
        hasCandidates: Boolean = true
    ): IdentifyReviewItem {
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                id = "1",
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                audioUrl = "",
                provider = "Deezer"
            ),
            score = 0.7f
        )
        return IdentifyReviewItem(
            song = song,
            proposal = IdentifyProposal(
                songId = song.id,
                queryArtist = queryArtist,
                queryTitle = queryTitle,
                candidates = if (hasCandidates) listOf(candidate) else emptyList(),
                confidence = if (hasCandidates) IdentifyConfidence.MEDIUM else IdentifyConfidence.NONE,
                suggested = candidate.takeIf { hasCandidates }
            )
        )
    }

    @Test
    fun searchDraft_isTitleOnly() {
        val reviewItem = item(queryTitle = "Creep", queryArtist = "Radiohead")
        assertEquals("Creep", identifySearchDraft(reviewItem))
        assertEquals("Radiohead", identifySearchFilterArtist(reviewItem))
        assertEquals("Pablo Honey", identifySearchFilterAlbum(reviewItem))
        assertEquals("1993", identifySearchFilterYear(reviewItem))
    }

    @Test
    fun searchFilters_skipPlaceholderArtistAndGenericAlbum() {
        val reviewItem = item(
            song = song(artist = "Unknown Artist", album = "YouTube Music", year = 0),
            queryArtist = "Unknown Artist"
        )
        assertEquals("", identifySearchFilterArtist(reviewItem))
        assertEquals("", identifySearchFilterAlbum(reviewItem))
        assertEquals("", identifySearchFilterYear(reviewItem))
    }

    @Test
    fun withItemSearchChrome_seedsFiltersAndOpensThemWhenSearching() {
        val reviewItem = item(hasCandidates = false)
        val seeded = IdentifyReviewState(items = listOf(reviewItem)).withItemSearchChrome(reviewItem)
        assertEquals("Creep", seeded.searchQueryDraft)
        assertEquals("Radiohead", seeded.searchFilterArtist)
        assertEquals("Pablo Honey", seeded.searchFilterAlbum)
        assertEquals("1993", seeded.searchFilterYear)
        assertTrue(seeded.showSearchField)
        assertTrue(seeded.showSearchFilters)
    }

    @Test
    fun withItemSearchChrome_keepsFiltersCollapsedWhenCandidatesExist() {
        val reviewItem = item(hasCandidates = true)
        val seeded = IdentifyReviewState(items = listOf(reviewItem)).withItemSearchChrome(reviewItem)
        assertFalse(seeded.showSearchField)
        assertFalse(seeded.showSearchFilters)
        assertEquals("Radiohead", seeded.searchFilterArtist)
    }

    @Test
    fun withItemSearchChrome_fillGapsOnly_seedsGapFields() {
        val tagged = song(artist = "Unknown Artist", year = 1993)
        val reviewItem = item(song = tagged).let { current ->
            current.copy(proposal = current.proposal.copy(fillGapsOnly = true))
        }
        val seeded = IdentifyReviewState(
            items = listOf(reviewItem),
            applyFields = IdentifyApplyFields.ALL
        ).withItemSearchChrome(reviewItem)
        assertTrue(seeded.applyFields.artist)
        assertFalse(seeded.applyFields.album)
        assertFalse(seeded.applyFields.title)
        assertFalse(seeded.applyFields.year)
        assertTrue(seeded.applyFields.artwork)
        assertFalse(seeded.applyFields.trackNumber)
    }

    @Test
    fun canApplySelected_requiresTickedFieldsAndCandidates() {
        val reviewItem = item(hasCandidates = true)
        val ready = IdentifyReviewState(items = listOf(reviewItem), applyFields = IdentifyApplyFields.ALL)
        assertTrue(ready.canApplySelected)
        val none = ready.copy(applyFields = IdentifyApplyFields.NONE)
        assertFalse(none.canApplySelected)
        assertFalse(none.canApplyRemaining)
    }

    @Test
    fun canApplySelected_isFalseWhileApplying() {
        val reviewItem = item(hasCandidates = true)
        val applying = IdentifyReviewState(
            items = listOf(reviewItem),
            applyFields = IdentifyApplyFields.ALL,
            isApplying = true
        )
        assertFalse(applying.canApplySelected)
        assertFalse(applying.canApplyRemaining)
    }

    @Test
    fun mergeIncomingReviewItems_appendsNewSongsWithoutResettingChrome() {
        val currentItem = item(song = song(id = 1L, title = "Creep"))
        val incomingNew = item(song = song(id = 2L, title = "Karma Police"))
        val open = IdentifyReviewState(
            items = listOf(currentItem),
            isVisible = true,
            showSearchField = true,
            showSearchFilters = true,
            searchQueryDraft = "consulta custom",
            searchFilterArtist = "Radiohead",
            selectedCandidateIndex = 2,
            visibleCandidateCount = 8
        )
        val merged = open.mergeIncomingReviewItems(
            incoming = listOf(currentItem, incomingNew),
            droppedIds = emptySet()
        )
        assertEquals(listOf(1L, 2L), merged.items.map { it.song.id })
        assertTrue(merged.showSearchField)
        assertTrue(merged.showSearchFilters)
        assertEquals("consulta custom", merged.searchQueryDraft)
        assertEquals("Radiohead", merged.searchFilterArtist)
        assertEquals(2, merged.selectedCandidateIndex)
        assertEquals(8, merged.visibleCandidateCount)
        assertTrue(merged.items.first() === currentItem)
    }

    @Test
    fun mergeIncomingReviewItems_ignoresExistingAndDropped() {
        val currentItem = item(song = song(id = 1L))
        val dropped = item(song = song(id = 3L, title = "Exit Music"))
        val open = IdentifyReviewState(
            items = listOf(currentItem),
            isVisible = true,
            showSearchField = true,
            searchQueryDraft = "keep"
        )
        val merged = open.mergeIncomingReviewItems(
            incoming = listOf(currentItem, dropped),
            droppedIds = setOf(3L)
        )
        assertEquals(listOf(1L), merged.items.map { it.song.id })
        assertTrue(merged.showSearchField)
        assertEquals("keep", merged.searchQueryDraft)
        assertTrue(merged === open)
    }

    @Test
    fun attachKnownAlbumMatches_promotesUnknownToMediumGroup() {
        val library = listOf(
            song(id = 1L, title = "Antes y Después", artist = "Ciro y Los Persas", album = "Espejos"),
            song(id = 2L, title = "Míralo", artist = "Ciro y Los Persas", album = "Espejos")
        )
        val unknown = item(
            song = song(
                id = 20L,
                title = "Astros",
                artist = "Unknown Artist",
                album = "Unknown Album"
            ),
            queryTitle = "Astros",
            queryArtist = "Unknown Artist",
            hasCandidates = false
        )
        val attached = attachKnownAlbumMatches(
            items = listOf(unknown),
            librarySongs = library + listOf(
                song(
                    id = 3L,
                    title = "Astros",
                    artist = "Ciro y Los Persas",
                    album = "Espejos",
                    durationMs = 230_000L
                )
            )
        )
        assertEquals(IdentifyConfidence.MEDIUM, attached.single().proposal.confidence)
        assertEquals("Espejos", attached.single().proposal.suggested?.album)
        assertEquals("Ciro y Los Persas", attached.single().proposal.suggested?.artist)
    }

    @Test
    fun identifyReviewFromPersisted_dropsLyricsBlobs() {
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                id = "1",
                title = "Creep",
                artist = "Radiohead",
                album = "Pablo Honey",
                durationMs = 238_000L,
                audioUrl = "",
                provider = "Deezer"
            ),
            score = 0.7f
        )
        val hydrated = identifyReviewFromPersisted(
            proposals = listOf(
                IdentifyProposal(
                    songId = 7L,
                    queryArtist = "Radiohead",
                    queryTitle = "Creep",
                    candidates = listOf(candidate),
                    confidence = IdentifyConfidence.MEDIUM,
                    suggested = candidate
                )
            ),
            phaseName = IdentifyReviewPhase.Item.name,
            songs = listOf(song().copy(lyrics = "[00:01.00]blob"))
        )
        assertNull(hydrated.items.single().song.lyrics)
        assertEquals("Creep", hydrated.items.single().song.title)
    }

    @Test
    fun identifyHydrationSongIds_emptyQueueSkipsRoom() {
        assertEquals(null, identifyHydrationSongIds(emptyList()))
        val proposal = IdentifyProposal(
            songId = 7L,
            queryArtist = "Radiohead",
            queryTitle = "Creep"
        )
        assertEquals(listOf(7L), identifyHydrationSongIds(listOf(proposal)))
    }

    @Test
    fun leftoverIdentifyReview_keepsOverviewWhenAnotherGroupRemains() {
        val groupA = listOf(
            mediumItem(1L, "Antes y Después", "Ciro y Los Persas", "Espejos"),
            mediumItem(2L, "Míralo", "Ciro y Los Persas", "Espejos")
        )
        val groupB = listOf(
            mediumItem(3L, "Hysteria", "Muse", "Absolution"),
            mediumItem(4L, "Time Is Running Out", "Muse", "Absolution")
        )
        val leftover = leftoverIdentifyReview(
            leftover = groupA + groupB.drop(1),
            sessionApplied = 1,
            sessionSkipped = 0,
            applyFields = IdentifyApplyFields.ALL
        )
        assertEquals(IdentifyReviewPhase.Overview, leftover.phase)
        assertEquals(1, leftover.albumGroups.size)
        assertEquals("Espejos", leftover.albumGroups.single().album)
        assertEquals(3, leftover.pendingCount)
        assertEquals(1, leftover.sessionApplied)
        assertTrue(leftover.isVisible)
        assertFalse(leftover.isApplying)
    }

    @Test
    fun leftoverIdentifyReview_applyLeavesQueueClickable() {
        val leftover = leftoverIdentifyReview(
            leftover = listOf(
                mediumItem(1L, "Antes y Después", "Ciro y Los Persas", "Espejos"),
                mediumItem(2L, "Míralo", "Ciro y Los Persas", "Espejos")
            ),
            sessionApplied = 3,
            sessionSkipped = 0,
            applyFields = IdentifyApplyFields.ALL
        )
        assertFalse(leftover.isApplying)
        assertTrue(leftover.canApplyRemaining)
        assertEquals(IdentifyReviewPhase.Overview, leftover.phase)
    }

    @Test
    fun leftoverIdentifyReview_itemPhaseKeepsSessionApplyFields() {
        val tagged = song(id = 9L, artist = "Unknown Artist", album = "Unknown Album")
        val reviewItem = item(song = tagged).let { current ->
            current.copy(proposal = current.proposal.copy(fillGapsOnly = true))
        }
        val leftover = leftoverIdentifyReview(
            leftover = listOf(reviewItem),
            sessionApplied = 1,
            sessionSkipped = 0,
            applyFields = IdentifyApplyFields.ALL
        )
        assertEquals(IdentifyReviewPhase.Item, leftover.phase)
        assertEquals(IdentifyApplyFields.ALL, leftover.applyFields)
        assertTrue(leftover.canApplySelected)
    }

    @Test
    fun identifyPersistEcho_skipsHydrateWhenOverlayRemainingMatches() {
        assertEquals(
            IdentifyPersistEcho.Skip,
            identifyPersistEcho(
                overlayOpen = true,
                itemIds = setOf(2L, 3L),
                snapSongIds = listOf(2L, 3L),
                droppedIds = setOf(1L)
            )
        )
    }

    @Test
    fun identifyPersistEcho_mergesRuntimeExtrasOnly() {
        assertEquals(
            IdentifyPersistEcho.MergeExtras(listOf(4L)),
            identifyPersistEcho(
                overlayOpen = true,
                itemIds = setOf(2L, 3L),
                snapSongIds = listOf(2L, 3L, 4L),
                droppedIds = setOf(1L)
            )
        )
    }

    @Test
    fun identifyPersistEcho_hydratesWhenOverlayClosed() {
        assertEquals(
            IdentifyPersistEcho.Hydrate(listOf(1L, 2L)),
            identifyPersistEcho(
                overlayOpen = false,
                itemIds = emptySet(),
                snapSongIds = listOf(1L, 2L),
                droppedIds = emptySet()
            )
        )
        assertEquals(
            IdentifyPersistEcho.Hydrate(emptyList()),
            identifyPersistEcho(
                overlayOpen = false,
                itemIds = emptySet(),
                snapSongIds = emptyList(),
                droppedIds = setOf(1L)
            )
        )
    }

    @Test
    fun leftoverIdentifyReview_secondPassPrunesFanOutExtras() {
        val groupA = listOf(
            mediumItem(1L, "Antes y Después", "Ciro y Los Persas", "Espejos"),
            mediumItem(2L, "Míralo", "Ciro y Los Persas", "Espejos")
        )
        val extras = listOf(
            mediumItem(3L, "Hysteria", "Muse", "Absolution"),
            mediumItem(4L, "Time Is Running Out", "Muse", "Absolution")
        )
        val afterExplicit = leftoverIdentifyReview(
            leftover = groupA + extras,
            sessionApplied = 2,
            sessionSkipped = 0,
            applyFields = IdentifyApplyFields.ALL
        )
        assertFalse(afterExplicit.isApplying)
        assertEquals(4, afterExplicit.pendingCount)
        val afterFanOut = leftoverIdentifyReview(
            leftover = afterExplicit.items.filter { it.song.id !in setOf(3L, 4L) },
            sessionApplied = afterExplicit.sessionApplied + 2,
            sessionSkipped = 0,
            applyFields = IdentifyApplyFields.ALL
        )
        assertEquals(2, afterFanOut.pendingCount)
        assertEquals(4, afterFanOut.sessionApplied)
        assertEquals("Espejos", afterFanOut.albumGroups.single().album)
        assertFalse(afterFanOut.isApplying)
    }

    @Test
    fun leftoverIdentifyReview_goesToItemWhenNoGroupsRemain() {
        val leftover = leftoverIdentifyReview(
            leftover = listOf(item(song = song(id = 9L, title = "Creep"))),
            sessionApplied = 2,
            sessionSkipped = 1,
            applyFields = IdentifyApplyFields.ALL
        )
        assertEquals(IdentifyReviewPhase.Item, leftover.phase)
        assertTrue(leftover.albumGroups.isEmpty())
        assertEquals(9L, leftover.current?.song?.id)
    }

    private fun mediumItem(
        id: Long,
        title: String,
        artist: String,
        album: String
    ): IdentifyReviewItem {
        val tagged = song(id = id, title = title, artist = artist, album = album)
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                id = "dz-$id",
                title = title,
                artist = artist,
                album = album,
                durationMs = tagged.durationMs,
                audioUrl = "",
                provider = "Deezer"
            ),
            score = 0.7f
        )
        return IdentifyReviewItem(
            song = tagged,
            proposal = IdentifyProposal(
                songId = id,
                queryArtist = artist,
                queryTitle = title,
                candidates = listOf(candidate),
                confidence = IdentifyConfidence.MEDIUM,
                suggested = candidate
            )
        )
    }
}
