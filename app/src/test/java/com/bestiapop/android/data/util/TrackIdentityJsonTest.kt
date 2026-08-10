package com.bestiapop.android.data.util

import com.bestiapop.android.data.model.TrackIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityJsonTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val identity = TrackIdentity(
            title = "Song",
            artist = "Artist",
            album = "Album",
            artworkUri = "https://example.com/a.jpg",
            durationMs = 120_000L,
            trackNumber = 3
        )
        val obj = JSONObject()
        TrackIdentityJson.putInto(obj, identity)
        assertEquals(identity, TrackIdentityJson.decode(obj))
        assertTrue(obj.has("artworkUri"))
        assertTrue(!obj.has("artworkUrl"))
    }

    @Test
    fun decode_legacyArtworkUrl_fallsBack() {
        val obj = JSONObject(
            """{"title":"T","artist":"A","album":"","artworkUrl":"https://old.example/x.jpg","durationMs":1,"trackNumber":0}"""
        )
        val identity = TrackIdentityJson.decode(obj)
        assertEquals("https://old.example/x.jpg", identity.artworkUri)
    }

    @Test
    fun decode_prefersArtworkUriOverArtworkUrl() {
        val obj = JSONObject(
            """{"title":"T","artist":"A","album":"","artworkUri":"https://new.example/n.jpg","artworkUrl":"https://old.example/o.jpg","durationMs":0,"trackNumber":0}"""
        )
        assertEquals("https://new.example/n.jpg", TrackIdentityJson.decode(obj).artworkUri)
    }

    @Test
    fun decode_nullArtwork_isNull() {
        val obj = JSONObject()
        TrackIdentityJson.putInto(obj, TrackIdentity(title = "T", artworkUri = null))
        assertNull(TrackIdentityJson.decode(obj).artworkUri)
    }
}
