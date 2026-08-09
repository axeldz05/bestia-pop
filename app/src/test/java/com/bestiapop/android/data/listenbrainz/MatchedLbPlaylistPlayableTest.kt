package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchedLbPlaylistPlayableTest {

    private fun song(id: Long, title: String, artist: String) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 180_000L
    )

    @Test
    fun toPlayableItems_mapsLocalAndRemoteInOrder() {
        val local = song(1, "Local Hit", "Artist A")
        val matched = MatchedLbPlaylist(
            detail = LbPlaylistDetail(
                summary = LbPlaylistSummary("mbid", "Daily Jams", null, 3),
                tracks = emptyList()
            ),
            matches = listOf(
                MatchedLbTrack(
                    track = LbPlaylistTrack("Local Hit", "Artist A", recordingMbid = "r1"),
                    localSong = local
                ),
                MatchedLbTrack(
                    track = LbPlaylistTrack(
                        title = "Remote Jam",
                        artist = "Artist B",
                        recordingMbid = "r2",
                        album = "EP"
                    ),
                    localSong = null
                ),
                MatchedLbTrack(
                    track = LbPlaylistTrack("Another Local", "Artist C"),
                    localSong = song(2, "Another Local", "Artist C")
                )
            )
        )

        assertEquals(2, matched.matchedCount)
        assertEquals(1, matched.streamCount)
        assertEquals(3, matched.totalCount)

        val items = matched.toPlayableItems()
        assertEquals(3, items.size)
        assertTrue(items[0] is PlayableItem.Local)
        assertEquals("Local Hit", items[0].title)

        val remote = items[1] as PlayableItem.Remote
        assertEquals("Remote Jam", remote.title)
        assertEquals("Artist B", remote.artist)
        assertEquals("EP", remote.album)
        assertEquals("r2", remote.recordingMbid)
        assertEquals("Artist B Remote Jam", remote.youtubeQueryOrId)

        assertTrue(items[2] is PlayableItem.Local)
        assertEquals("Another Local", items[2].title)
    }
}
