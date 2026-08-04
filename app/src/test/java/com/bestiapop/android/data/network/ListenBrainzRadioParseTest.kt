package com.bestiapop.android.data.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzRadioParseTest {

    @Test
    fun parseMetadataLookup_readsArtistMbids() {
        val json = JSONObject(
            """
            {
              "artist_credit_name": "Rick Astley",
              "artist_mbids": ["db92a151-1ac2-438b-bc43-b82e149ddd50"],
              "recording_mbid": "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae",
              "recording_name": "Never Gonna Give You Up"
            }
            """.trimIndent()
        )
        val lookup = ListenBrainzClient.parseMetadataLookup(json)
        assertEquals(listOf("db92a151-1ac2-438b-bc43-b82e149ddd50"), lookup.artistMbids)
        assertEquals("Never Gonna Give You Up", lookup.recordingName)
    }

    @Test
    fun parseLbRadioArtist_flattensPerArtistArrays() {
        val json = JSONObject(
            """
            {
              "artist-a": [
                {
                  "recording_mbid": "rec-1",
                  "similar_artist_mbid": "art-1",
                  "similar_artist_name": "Artist One",
                  "total_listen_count": 10
                }
              ],
              "artist-b": [
                {
                  "recording_mbid": "rec-2",
                  "similar_artist_name": "Artist Two",
                  "total_listen_count": 5
                }
              ]
            }
            """.trimIndent()
        )
        val list = ListenBrainzClient.parseLbRadioArtist(json)
        assertEquals(2, list.size)
        assertEquals("rec-1", list[0].recordingMbid)
        assertEquals("Artist One", list[0].similarArtistName)
        assertEquals("rec-2", list[1].recordingMbid)
    }

    @Test
    fun parseRecordingMetadataMap_readsNestedName() {
        val json = JSONObject(
            """
            {
              "e97f805a-ab48-4c52-855e-07049142113d": {
                "recording": { "name": "Glory Box", "rels": [] },
                "artist": { "name": "Portishead" },
                "release": { "name": "Dummy" }
              }
            }
            """.trimIndent()
        )
        val map = ListenBrainzClient.parseRecordingMetadataMap(json)
        assertTrue(map.containsKey("e97f805a-ab48-4c52-855e-07049142113d"))
        val meta = map.getValue("e97f805a-ab48-4c52-855e-07049142113d")
        assertEquals("Glory Box", meta.title)
        assertEquals("Portishead", meta.artist)
        assertEquals("Dummy", meta.releaseName)
    }

    @Test
    fun parseCfRecommendations_readsMbidsAndScores() {
        val json = JSONObject(
            """
            {
              "payload": {
                "last_updated": 1588494361,
                "type": "top",
                "entity": "recording",
                "mbids": [
                  {
                    "recording_mbid": "526bd613-fddd-4bd6-9137-ab709ac74cab",
                    "score": 9.345
                  },
                  {
                    "recording_mbid": "a6081bc1-2a76-4984-b21f-38bc3dcca3a5",
                    "score": 6.998
                  }
                ],
                "user_name": "unclejohn69",
                "count": 2,
                "total_mbid_count": 30,
                "offset": 0
              }
            }
            """.trimIndent()
        )
        val payload = ListenBrainzClient.parseCfRecommendations(json, "fallback")
        assertEquals("unclejohn69", payload.userName)
        assertEquals(2, payload.recordings.size)
        assertEquals("526bd613-fddd-4bd6-9137-ab709ac74cab", payload.recordings[0].recordingMbid)
        assertEquals(9.345, payload.recordings[0].score, 0.001)
        assertEquals(1588494361L, payload.lastUpdatedEpochSec)
        assertEquals(30, payload.totalMbidCount)
        assertEquals("top", payload.artistType)
    }

    @Test
    fun parseCfRecommendations_emptyMbids() {
        val json = JSONObject(
            """
            {
              "payload": {
                "mbids": [],
                "user_name": "user",
                "count": 0,
                "total_mbid_count": 0
              }
            }
            """.trimIndent()
        )
        val payload = ListenBrainzClient.parseCfRecommendations(json, "user")
        assertTrue(payload.recordings.isEmpty())
        assertEquals(0, payload.totalMbidCount)
    }
}
