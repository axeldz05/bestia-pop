package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
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
        artworkUrl = "https://example.com/a.jpg",
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
        displayTitle = "Song",
        displayArtist = "Artist",
        artworkUrl = "https://example.com/a.jpg",
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
        assertEquals(INTERRUPTED_DOWNLOAD_MESSAGE, persisted[0].errorMessage)
        assertEquals(0, persisted[0].progressPercent)
    }

    @Test
    fun forPersistence_dropsSuccess() {
        val persisted = ActiveDownloadCodec.forPersistence(
            listOf(download(CandidateDownloadState.SUCCESS))
        )
        assertTrue(persisted.isEmpty())
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
            download(CandidateDownloadState.IDLE, "c")
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
