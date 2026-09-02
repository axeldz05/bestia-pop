package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyGapsTest {

    @Test
    fun placeholderArtist_marksArtistOnlyAmongIdentity() {
        val fields = gapApplyFields(complete(artist = "Unknown Artist"))
        assertTrue(fields.artist)
        assertFalse(fields.title)
        assertFalse(fields.album)
        assertTrue(needsGapIdentify(complete(artist = "Unknown Artist")))
    }

    @Test
    fun genericAlbum_marksAlbum() {
        val fields = gapApplyFields(complete(album = "Unknown Album"))
        assertTrue(fields.album)
        assertFalse(fields.artist)
        assertFalse(fields.title)
    }

    @Test
    fun trackNumberTitle_isWeak_realId3TitleIsNot() {
        assertTrue(isWeakIdentityTitle("Radiohead", "01"))
        assertTrue(isWeakIdentityTitle("Unknown Artist", "- Creep"))
        assertTrue(isWeakIdentityTitle("Unknown Artist", "content://tree/primary:Music"))
        assertTrue(isWeakIdentityTitle("Unknown Artist", "Artist - Song"))
        assertFalse(isWeakIdentityTitle("Radiohead", "Creep"))
        assertFalse(isWeakIdentityTitle("namitape", "ブラックホール / Black Hole"))
        val fields = gapApplyFields(complete(title = "Creep"))
        assertFalse(fields.title)
    }

    @Test
    fun missingYearOrTrack_isNotAGap() {
        val missing = complete(year = 0, trackNumber = 0)
        val fields = gapApplyFields(missing)
        assertFalse(fields.year)
        assertFalse(fields.trackNumber)
        assertFalse(needsGapIdentify(missing))
    }

    @Test
    fun contentArtworkStub_isAGap_fileArtworkIsNot() {
        assertTrue(gapApplyFields(complete(artworkUri = "content://media/external/audio/albumart/1")).artwork)
        assertFalse(gapApplyFields(complete(artworkUri = "file:///cover.jpg")).artwork)
        assertTrue(gapApplyFields(complete(artworkUri = null)).artwork)
    }

    @Test
    fun completeSong_doesNotNeedIdentify() {
        val song = complete()
        val fields = gapApplyFields(song)
        assertFalse(fields.hasAny)
        assertFalse(needsGapIdentify(song))
        assertEquals(false, fields.isAll)
    }

    private fun complete(
        artist: String = "Radiohead",
        title: String = "Creep",
        album: String = "Pablo Honey",
        year: Int = 1993,
        trackNumber: Int = 2,
        artworkUri: String? = "file:///cover.jpg"
    ) = Song(
        id = 1L,
        uriString = "file:///song.mp3",
        title = title,
        artist = artist,
        album = album,
        year = year,
        trackNumber = trackNumber,
        artworkUri = artworkUri
    )
}
