package com.bestiapop.android.domain.util

import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrackMatchKeysTest {

    private fun song(id: Long, title: String, artist: String) = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = "A",
        durationMs = 1L
    )

    @Test
    fun lookupLocalSong_findsByNormalizedArtistTitle() {
        val library = listOf(song(1, "Creep!", "Radiohead"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val found = TrackMatchKeys.lookupLocalSong(
            index,
            TrackIdentity(title = "creep", artist = "radiohead")
        )
        assertEquals(1L, found?.id)
    }

    @Test
    fun lookupLocalSong_returnsNullWhenMissing() {
        val index = TrackMatchKeys.buildLibraryIndex(listOf(song(1, "A", "B")))
        assertNull(
            TrackMatchKeys.lookupLocalSong(index, TrackIdentity(title = "X", artist = "Y"))
        )
    }

    @Test
    fun rematchLocals_fillsOnlyUnmatchedAndKeepsExisting() {
        val local = song(9, "Hit", "Band")
        val already = MatchedRemoteTrack(
            identity = TrackIdentity(title = "Hit", artist = "Band"),
            recordingMbid = "r1",
            localSong = local
        )
        val pending = MatchedRemoteTrack(
            identity = TrackIdentity(title = "New", artist = "Band"),
            recordingMbid = "r2",
            localSong = null
        )
        val library = listOf(local, song(10, "New", "Band"))
        val rematched = listOf(already, pending).rematchLocals(library)
        assertSame(local, rematched[0].localSong)
        assertEquals(10L, rematched[1].localSong?.id)
    }

    @Test
    fun matchMetasAgainstLibrary_mapsWithSharedIndex() {
        val library = listOf(song(1, "A", "B"), song(2, "C", "D"))
        val metas = listOf(
            TrackIdentity(title = "A", artist = "B"),
            TrackIdentity(title = "Missing", artist = "Z"),
            TrackIdentity(title = "", artist = "B")
        )
        val matched = TrackMatchKeys.matchMetasAgainstLibrary(
            items = metas,
            library = library,
            skipBlank = true
        ) { meta, local -> meta.title to local?.id }
        assertEquals(listOf("A" to 1L, "Missing" to null), matched)
    }

    @Test
    fun matchAgainstLibrary_passesWholeItem() {
        val library = listOf(song(5, "Hit", "Band"))
        data class Scored(val meta: TrackIdentity, val score: Double)
        val items = listOf(Scored(TrackIdentity(title = "Hit", artist = "Band"), 0.9))
        val out = TrackMatchKeys.matchAgainstLibrary(
            items = items,
            library = library,
            metaOf = { it.meta }
        ) { item, local -> item.score to local?.id }
        assertEquals(listOf(0.9 to 5L), out)
    }
}
