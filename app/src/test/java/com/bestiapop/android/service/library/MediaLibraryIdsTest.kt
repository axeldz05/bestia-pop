package com.bestiapop.android.service.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLibraryIdsTest {

    @Test
    fun unicodeAlbumAndArtist_roundTripWithoutPathCharacters() {
        val albumId = MediaLibraryIds.album("Canción/Álbum: 2026")
        val artistId = MediaLibraryIds.artist("Björk & compañía")

        assertEquals(
            MediaLibraryTarget.Album("Canción/Álbum: 2026"),
            MediaLibraryIds.parse(albumId)
        )
        assertEquals(
            MediaLibraryTarget.Artist("Björk & compañía"),
            MediaLibraryIds.parse(artistId)
        )
    }

    @Test
    fun malformedOrNonPositiveIds_areRejected() {
        assertNull(MediaLibraryIds.parse("bestiapop:song:0"))
        assertNull(MediaLibraryIds.parse("bestiapop:playlist:not-a-number"))
        assertNull(MediaLibraryIds.parse("bestiapop:album:%%%"))
        assertNull(MediaLibraryIds.parse("other:song:1"))
    }
}
