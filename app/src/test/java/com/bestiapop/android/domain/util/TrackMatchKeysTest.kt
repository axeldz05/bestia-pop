package com.bestiapop.android.domain.util

import com.bestiapop.android.data.listenbrainz.MatchedRemoteTrack
import com.bestiapop.android.data.listenbrainz.rematchLocals
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchKeysTest {

    private fun song(id: Long, title: String, artist: String, isRemote: Boolean = false) = Song(
        id = id,
        uriString = if (isRemote) "remote://stream/$id" else "file:///$id",
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
    fun normalize_foldsDiacritics() {
        assertEquals("cancion", TrackMatchKeys.normalize("Canción"))
        assertEquals("senor", TrackMatchKeys.normalize("Señor"))
        assertEquals("radiohead|creep", TrackMatchKeys.matchKey("Radiohead", "Creep!"))
        assertEquals("radiohead|creep", TrackMatchKeys.composeKey("radiohead", "creep"))
        assertEquals("radiohead|creep", TrackMatchKeys.matchKeyPreNormalized("radiohead", "creep"))
        assertEquals("", TrackMatchKeys.composeKey("", "creep"))
        assertEquals("", TrackMatchKeys.composeKey("radiohead", ""))
    }

    @Test
    fun normalize_handlesAsciiAndDiacriticsCorrectly() {
        assertEquals("bjork", TrackMatchKeys.normalize("Björk"))
        assertEquals("radiohead", TrackMatchKeys.normalize("Radiohead"))
        // Second call should hit the LRU cache
        assertEquals("radiohead", TrackMatchKeys.normalize("Radiohead"))
        assertEquals("post 1995", TrackMatchKeys.normalize("Post (1995)"))
        assertEquals("", TrackMatchKeys.normalize(""))
    }

    @Test
    fun lookupLocalSong_matchesWithoutTildes() {
        val library = listOf(song(2, "La Canción", "José"))
        val found = TrackMatchKeys.lookupLocalSong(
            TrackMatchKeys.buildLibraryIndex(library),
            TrackIdentity(title = "la cancion", artist = "jose")
        )
        assertEquals(2L, found?.id)
    }

    @Test
    fun containsNormalized_substringIgnoresDiacritics() {
        assertTrue(TrackMatchKeys.containsNormalized("La Canción del Verano", "cancion"))
        assertTrue(TrackMatchKeys.containsNormalized("José José", "jose"))
        assertFalse(TrackMatchKeys.containsNormalized("Creep", "cancion"))
    }

    @Test
    fun containsNormalized_punctuationOnlyNeedleDoesNotMatch() {
        assertFalse(TrackMatchKeys.containsNormalized("Creep", "!!!"))
        assertTrue(TrackMatchKeys.containsNormalized("Creep", ""))
        assertTrue(TrackMatchKeys.containsNormalized("Creep", "   "))
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

    @Test
    fun lookupLocalSong_matchesTransliteratedOriginalTitle_soranin() {
        val library = listOf(song(1549, "Soranin", "ASIAN KUNG-FU GENERATION"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val query = TrackIdentity(title = "ソラニン", artist = "ASIAN KUNG-FU GENERATION")
        val found = TrackMatchKeys.lookupLocalSong(index, query)
        assertEquals(1549L, found?.id)
    }

    @Test
    fun lookupLocalSong_matchesOriginalLocalTitle_withRomanizedQuery() {
        val library = listOf(song(1745, "ソラニン", "ASIAN KUNG-FU GENERATION"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val query = TrackIdentity(title = "Soranin", artist = "ASIAN KUNG-FU GENERATION")
        val found = TrackMatchKeys.lookupLocalSong(index, query)
        assertEquals(1745L, found?.id)
    }

    @Test
    fun lookupLocalSong_matchesJapaneseRLVariation() {
        val library = listOf(song(10, "Solanin", "ASIAN KUNG-FU GENERATION"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val query = TrackIdentity(title = "Soranin", artist = "ASIAN KUNG-FU GENERATION")
        val found = TrackMatchKeys.lookupLocalSong(index, query)
        assertEquals(10L, found?.id)
    }

    @Test
    fun lookupLocalSong_matchesParenthesizedBilingualTitle() {
        val library = listOf(song(20, "夜鷹 (Yodaka)", "Artist"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val queryInside = TrackIdentity(title = "Yodaka", artist = "Artist")
        val queryOutside = TrackIdentity(title = "夜鷹", artist = "Artist")
        assertEquals(20L, TrackMatchKeys.lookupLocalSong(index, queryInside)?.id)
        assertEquals(20L, TrackMatchKeys.lookupLocalSong(index, queryOutside)?.id)
    }

    @Test
    fun lookupLocalSong_matchesCosmeticNoiseTitle() {
        val library = listOf(song(30, "Rewrite (2016 Rerecorded)", "ASIAN KUNG-FU GENERATION"))
        val index = TrackMatchKeys.buildLibraryIndex(library)
        val query = TrackIdentity(title = "Rewrite", artist = "ASIAN KUNG-FU GENERATION")
        val found = TrackMatchKeys.lookupLocalSong(index, query)
        assertEquals(30L, found?.id)
    }

    @Test
    fun buildLibraryIndex_localSongWinsOverRemoteSong() {
        val local = song(1549, "Soranin", "ASIAN KUNG-FU GENERATION", isRemote = false)
        val remote = song(1745, "ソラニン", "ASIAN KUNG-FU GENERATION", isRemote = true)
        val index = TrackMatchKeys.buildLibraryIndex(listOf(remote, local))
        val query = TrackIdentity(title = "ソラニン", artist = "ASIAN KUNG-FU GENERATION")
        val found = TrackMatchKeys.lookupLocalSong(index, query)
        assertEquals(1549L, found?.id)
    }
}
