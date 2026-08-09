package com.bestiapop.android.data.network

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTrackNumberParseTest {

    @Test
    fun parseDeezerSearchTracks_readsPositionAndDisc() {
        val data = JSONArray(
            """
            [{
              "id": "1",
              "title": "Harder Better Faster Stronger",
              "duration": 224,
              "track_position": 4,
              "disk_number": 2,
              "artist": {"name": "Daft Punk"},
              "album": {"title": "Discovery", "cover_xl": "", "cover_big": ""}
            }]
            """.trimIndent()
        )
        val tracks = MetadataFetcher.parseDeezerSearchTracks(data)
        assertEquals(1, tracks.size)
        assertEquals(2004, tracks[0].trackNumber)
    }

    @Test
    fun parseItunesSongResults_readsTrackAndDisc() {
        val results = JSONArray(
            """
            [{
              "trackId": "99",
              "trackName": "Creep",
              "artistName": "Radiohead",
              "collectionName": "Pablo Honey",
              "artworkUrl100": "",
              "trackTimeMillis": 238000,
              "trackNumber": 4,
              "discNumber": 1
            }]
            """.trimIndent()
        )
        val tracks = MetadataFetcher.parseItunesSongResults(results)
        assertEquals(1, tracks.size)
        assertEquals(4, tracks[0].trackNumber)
    }
}
