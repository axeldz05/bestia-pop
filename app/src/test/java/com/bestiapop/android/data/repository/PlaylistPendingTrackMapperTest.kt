package com.bestiapop.android.data.repository

import com.bestiapop.android.data.db.PlaylistPendingTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPendingTrackMapperTest {

    @Test
    fun roundTrip_nullReleaseNameBecomesBlankAlbum() {
        val entity = PlaylistPendingTrackEntity(
            id = 1L,
            playlistId = 10L,
            title = "Song",
            artist = "Artist",
            releaseName = null,
            recordingMbid = "mbid",
            position = 3
        )
        val domain = entity.toPendingTrack()
        assertEquals("", domain.album)
        assertEquals("Song", domain.title)
        assertEquals("Artist", domain.artist)
        assertEquals(1L, domain.id)
        assertEquals(10L, domain.playlistId)
        assertEquals("mbid", domain.recordingMbid)
        assertEquals(3, domain.position)

        val back = domain.toEntity()
        assertNull(back.releaseName)
        assertEquals("Song", back.title)
        assertEquals("Artist", back.artist)
        assertEquals(1L, back.id)
        assertEquals(10L, back.playlistId)
        assertEquals("mbid", back.recordingMbid)
        assertEquals(3, back.position)
    }

    @Test
    fun roundTrip_releaseNamePreserved() {
        val entity = PlaylistPendingTrackEntity(
            id = 2L,
            playlistId = 10L,
            title = "Song",
            artist = "Artist",
            releaseName = "EP"
        )
        val domain = entity.toPendingTrack()
        assertEquals("EP", domain.album)
        assertEquals("EP", domain.toEntity().releaseName)
    }
}
