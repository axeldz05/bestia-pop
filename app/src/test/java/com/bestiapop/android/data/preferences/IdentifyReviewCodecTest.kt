package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyReviewCodecTest {

    private fun track() = OnlineCatalogTrack(
        id = "dz-1",
        title = "Hysteria",
        artist = "Muse",
        album = "Absolution",
        artworkUri = "https://img.example/a.jpg",
        durationMs = 227_000L,
        audioUrl = "https://cdn.example/expired",
        provider = "Deezer",
        trackNumber = 8
    )

    private fun proposal(songId: Long = 10L): IdentifyProposal {
        val candidate = IdentifyCandidate(
            track = track(),
            score = 0.72f,
            reasons = listOf("título similar", "duración +1s")
        )
        return IdentifyProposal(
            songId = songId,
            queryArtist = "Unknown Artist",
            queryTitle = "Hysteria",
            sourceHints = "Muse · Hysteria",
            candidates = listOf(candidate),
            confidence = IdentifyConfidence.MEDIUM,
            suggested = candidate
        )
    }

    @Test
    fun roundTrip_stripsAudioUrlAndKeepsScore() {
        val original = PersistedIdentifyReviewQueue(
            proposals = listOf(proposal()),
            phase = "Overview"
        )
        val restored = IdentifyReviewCodec.decode(IdentifyReviewCodec.encode(original))
        assertEquals(1, restored.proposals.size)
        assertEquals("Overview", restored.phase)
        val p = restored.proposals[0]
        assertEquals(10L, p.songId)
        assertEquals(IdentifyConfidence.MEDIUM, p.confidence)
        assertEquals("Muse · Hysteria", p.sourceHints)
        assertEquals(0.72f, p.suggested?.score ?: 0f, 0.001f)
        assertEquals(listOf("título similar", "duración +1s"), p.candidates.first().reasons)
        assertEquals("Hysteria", p.suggested?.title)
        assertEquals("Deezer", p.suggested?.provider)
        assertEquals(8, p.suggested?.trackNumber)
        assertEquals("", p.suggested?.track?.audioUrl)
        assertEquals("https://img.example/a.jpg", p.suggested?.artworkUri)
    }

    @Test
    fun decode_emptyOrInvalid_returnsEmpty() {
        assertTrue(IdentifyReviewCodec.decode("").proposals.isEmpty())
        assertTrue(IdentifyReviewCodec.decode("not-json").proposals.isEmpty())
    }

    @Test
    fun hydrate_dropsOrphanSongIds() {
        val snapshot = PersistedIdentifyReviewQueue(
            proposals = listOf(proposal(1L), proposal(2L)),
            phase = "Overview"
        )
        val songs = listOf(
            Song(id = 2L, uriString = "file://b", title = "Hysteria", artist = "Muse", album = "Unknown Album")
        )
        val state = identifyReviewFromPersisted(snapshot.proposals, snapshot.phase, songs)
        assertEquals(1, state.items.size)
        assertEquals(2L, state.items[0].song.id)
        assertEquals(IdentifyReviewPhase.Item, state.phase)
        assertEquals(false, state.isVisible)
    }
}
