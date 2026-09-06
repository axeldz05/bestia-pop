package com.bestiapop.android.data.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzClientTest {

    @Test
    fun luceneQuery_quotesRecordingAndDurationWindow() {
        assertEquals(
            "recording:\"Yodaka\"",
            luceneRecordingQuery("Yodaka", durationMs = null)
        )
        assertEquals(
            "recording:\"Black Hole\" AND dur:[212204 TO 216204]",
            luceneRecordingQuery("Black Hole", durationMs = 214_204L)
        )
    }

    @Test
    fun parseSearch_mapsAliasesCoverArtAndDuration() {
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-1",
                "title": "ブラックホール",
                "length": 214154,
                "aliases": [{"name": "Black Hole"}],
                "artist-credit": [{
                  "name": "namitape",
                  "joinphrase": "",
                  "artist": {"name": "namitape"}
                }],
                "releases": [{
                  "id": "rel-flitter",
                  "title": "Flitter",
                  "status": "Official",
                  "date": "2024-05-01",
                  "media": [{
                    "position": 1,
                    "track": [{
                      "number": "2",
                      "title": "ブラックホール",
                      "length": 214155
                    }]
                  }]
                }]
              }]
            }
            """.trimIndent()
        )

        val tracks = parseMusicBrainzRecordingSearch(json)
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("rec-1", track.id)
        assertEquals("MusicBrainz", track.provider)
        assertEquals("namitape", track.artist)
        assertEquals("Flitter", track.album)
        assertEquals(214154L, track.durationMs)
        assertEquals(2, track.trackNumber)
        assertEquals(2024, track.year)
        assertEquals(
            "https://coverartarchive.org/release/rel-flitter/front-500",
            track.artworkUri
        )
        assertEquals("ブラックホール (Black Hole)", track.title)
        assertTrue(track.audioUrl.contains("namitape"))
    }

    @Test
    fun parseSearch_unmatchedTitle_doesNotDefaultToTrackOne() {
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-2",
                "title": "Some Song",
                "length": 180000,
                "artist-credit": [{
                  "name": "Artist",
                  "artist": {"name": "Artist"}
                }],
                "releases": [{
                  "id": "rel-1",
                  "title": "Album",
                  "media": [{
                    "position": 1,
                    "track": [{
                      "number": "1",
                      "title": "Intro",
                      "length": 60000
                    }, {
                      "number": "2",
                      "title": "Other Track",
                      "length": 120000
                    }]
                  }]
                }]
              }]
            }
            """.trimIndent()
        )

        val tracks = parseMusicBrainzRecordingSearch(json)
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals(0, track.trackNumber)
    }
}

