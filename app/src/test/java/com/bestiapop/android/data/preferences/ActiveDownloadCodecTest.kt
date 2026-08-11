package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDownloadCodecTest {

    private fun track(id: String = "vid1") = OnlineCatalogTrack(
        id = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        artworkUri = "https://example.com/a.jpg",
        durationMs = 120_000L,
        audioUrl = id,
        provider = "YouTube"
    )

    private fun download(
        state: CandidateDownloadState,
        id: String = "job-1"
    ) = ActiveDownload(
        id = id,
        source = ActiveDownloadSource.CATALOG,
        candidates = listOf(track()),
        currentCandidateIndex = 0,
        state = state,
        progressMessage = "Descargando…",
        progressPercent = 50,
        errorMessage = if (state == CandidateDownloadState.ERROR) "boom" else null
    )

    @Test
    fun roundTrip_preservesErrorJobs() {
        val original = listOf(download(CandidateDownloadState.ERROR))
        val restored = ActiveDownloadCodec.decode(ActiveDownloadCodec.encode(original))
        assertEquals(1, restored.size)
        assertEquals("job-1", restored[0].id)
        assertEquals(CandidateDownloadState.ERROR, restored[0].state)
        assertEquals("boom", restored[0].errorMessage)
        assertEquals("vid1", restored[0].currentTrack?.id)
        assertEquals(ActiveDownloadSource.CATALOG, restored[0].source)
    }

    @Test
    fun forPersistence_convertsDownloadingToInterruptedError() {
        val persisted = ActiveDownloadCodec.forPersistence(
            listOf(download(CandidateDownloadState.DOWNLOADING))
        )
        assertEquals(1, persisted.size)
        assertEquals(CandidateDownloadState.ERROR, persisted[0].state)
        assertEquals(DownloadMessages.interrupted, persisted[0].errorMessage)
        assertEquals(0, persisted[0].progressPercent)
    }

    @Test
    fun forPersistence_convertsQueuedToInterruptedError() {
        val persisted = ActiveDownloadCodec.forPersistence(
            listOf(download(CandidateDownloadState.QUEUED))
        )
        assertEquals(1, persisted.size)
        assertEquals(CandidateDownloadState.ERROR, persisted[0].state)
        assertEquals(DownloadMessages.interrupted, persisted[0].errorMessage)
    }

    @Test
    fun decode_emptyOrInvalid_returnsEmpty() {
        assertTrue(ActiveDownloadCodec.decode("").isEmpty())
        assertTrue(ActiveDownloadCodec.decode("not-json").isEmpty())
    }

    @Test
    fun badgeCount_countsDownloadingAndError() {
        val list = listOf(
            download(CandidateDownloadState.DOWNLOADING, "a"),
            download(CandidateDownloadState.ERROR, "b"),
            download(CandidateDownloadState.IDLE, "c"),
            download(CandidateDownloadState.QUEUED, "d"),
            download(CandidateDownloadState.SUCCESS, "e")
        )
        assertEquals(2, activeDownloadBadgeCount(list))
    }

    @Test
    fun roundTrip_preservesLbImportAndTargetPlaylistId() {
        val original = listOf(
            download(CandidateDownloadState.ERROR).copy(
                source = ActiveDownloadSource.LB_IMPORT,
                targetPlaylistId = 42L
            )
        )
        val restored = ActiveDownloadCodec.decode(ActiveDownloadCodec.encode(original))
        assertEquals(1, restored.size)
        assertEquals(ActiveDownloadSource.LB_IMPORT, restored[0].source)
        assertEquals(42L, restored[0].targetPlaylistId)
    }

    @Test
    fun roundTrip_preservesSuccessAndResultSongId() {
        val original = listOf(
            download(CandidateDownloadState.SUCCESS).copy(
                resultSongId = 55L,
                progressPercent = 100
            )
        )
        val restored = ActiveDownloadCodec.decode(ActiveDownloadCodec.encode(original))
        assertEquals(1, restored.size)
        assertEquals(CandidateDownloadState.SUCCESS, restored[0].state)
        assertEquals(55L, restored[0].resultSongId)
        assertEquals(100, restored[0].progressPercent)
    }

    @Test
    fun decode_legacyDisplayTitleOverridesDifferentCandidateTitle() {
        val json = """
            [{"id":"job-saveas","source":"LB_IMPORT","displayTitle":"Song (2)","displayArtist":"Artist",
              "artworkUrl":null,"currentCandidateIndex":0,"state":"ERROR",
              "errorMessage":"boom","targetPlaylistId":3,"candidates":[{"id":"vid1","title":"Song","artist":"Artist","album":"",
              "artworkUrl":null,"durationMs":0,"audioUrl":"vid1","provider":"YouTube","trackNumber":0}]}]
        """.trimIndent()
        val restored = ActiveDownloadCodec.decode(json)
        assertEquals(1, restored.size)
        assertEquals("Song", restored[0].title)
        assertEquals("Song (2)", restored[0].displayLabel)
        assertEquals("Artist", restored[0].artist)
        assertEquals(ActiveDownloadSource.LB_IMPORT, restored[0].source)
        assertEquals(3L, restored[0].targetPlaylistId)
    }

    @Test
    fun decode_legacyDisplayFieldsFillBlankCandidateIdentity() {
        val json = """
            [{"id":"job-legacy","source":"CATALOG","displayTitle":"Old Title","displayArtist":"Old Artist",
              "artworkUrl":"https://example.com/old.jpg","currentCandidateIndex":0,"state":"ERROR",
              "errorMessage":"boom","candidates":[{"id":"vid1","title":"","artist":"","album":"",
              "artworkUrl":null,"durationMs":0,"audioUrl":"vid1","provider":"YouTube","trackNumber":0}]}]
        """.trimIndent()
        val restored = ActiveDownloadCodec.decode(json)
        assertEquals(1, restored.size)
        assertEquals("Old Title", restored[0].title)
        assertEquals("Old Artist", restored[0].artist)
        assertEquals("https://example.com/old.jpg", restored[0].artworkUri)
        assertEquals(null, restored[0].titleOverride)
        assertEquals("Old Title", restored[0].displayLabel)
    }

    @Test
    fun roundTrip_preservesTitleOverrideWithoutMutatingCandidate() {
        val original = listOf(
            download(CandidateDownloadState.ERROR).copy(titleOverride = "Song (2)")
        )
        val restored = ActiveDownloadCodec.decode(ActiveDownloadCodec.encode(original))
        assertEquals("Song", restored[0].title)
        assertEquals("Song (2)", restored[0].displayLabel)
    }

    @Test
    fun forPersistence_keepsTargetPlaylistIdWhenInterrupted() {
        val persisted = ActiveDownloadCodec.forPersistence(
            listOf(
                download(CandidateDownloadState.DOWNLOADING).copy(
                    source = ActiveDownloadSource.LB_IMPORT,
                    targetPlaylistId = 7L
                )
            )
        )
        assertEquals(1, persisted.size)
        assertEquals(CandidateDownloadState.ERROR, persisted[0].state)
        assertEquals(7L, persisted[0].targetPlaylistId)
        assertEquals(ActiveDownloadSource.LB_IMPORT, persisted[0].source)
    }
}
