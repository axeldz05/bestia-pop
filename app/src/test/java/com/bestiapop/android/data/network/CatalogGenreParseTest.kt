package com.bestiapop.android.data.network

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGenreParseTest {

    @Test
    fun parseCatalogGenres_skipsAllIdAndBlankNames() {
        val data = JSONArray(
            """
            [
              {"id": 0, "name": "All", "picture_xl": "", "picture_big": ""},
              {"id": 132, "name": "Pop", "picture_xl": "https://xl", "picture_big": "https://big"},
              {"id": 152, "name": "  ", "picture_xl": "", "picture_big": ""},
              {"id": 113, "name": "Dance", "picture_xl": "", "picture_big": "https://big2"}
            ]
            """.trimIndent()
        )
        val genres = MetadataFetcher.parseCatalogGenres(data)
        assertEquals(2, genres.size)
        assertEquals(132L, genres[0].id)
        assertEquals("Pop", genres[0].name)
        assertEquals("https://xl", genres[0].pictureUrl)
        assertEquals(113L, genres[1].id)
        assertEquals("Dance", genres[1].name)
        assertEquals("https://big2", genres[1].pictureUrl)
    }

    @Test
    fun parseCatalogGenres_nullOrEmpty_returnsEmpty() {
        assertTrue(MetadataFetcher.parseCatalogGenres(null).isEmpty())
        assertTrue(MetadataFetcher.parseCatalogGenres(JSONArray()).isEmpty())
    }

    @Test
    fun parseDeezerSearchTracks_chartShapedPayload() {
        val data = JSONArray(
            """
            [{
              "id": "3135556",
              "title": "Harder Better Faster Stronger",
              "duration": 224,
              "artist": {"name": "Daft Punk"},
              "album": {"title": "Discovery", "cover_xl": "https://xl", "cover_big": ""}
            }]
            """.trimIndent()
        )
        val tracks = MetadataFetcher.parseDeezerSearchTracks(data)
        assertEquals(1, tracks.size)
        assertEquals("Harder Better Faster Stronger", tracks[0].title)
        assertEquals("Daft Punk", tracks[0].artist)
        assertEquals("3135556", tracks[0].id)
    }
}
