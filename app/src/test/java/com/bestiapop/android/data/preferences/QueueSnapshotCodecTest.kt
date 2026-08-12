package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.toPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotCodecTest {

    private fun song(id: Long) = Song(
        id = id,
        uriString = "content://song/$id",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        artworkUri = "file:///art/$id.jpg",
        trackNumber = id.toInt()
    )

    @Test
    fun roundTrip_localAndRemote_omitsCdnUrl() {
        val local = song(1).toPlayable()
        val remote = PlayableItem.remoteFrom(
            identity = TrackIdentity(
                title = "Remote",
                artist = "Band",
                album = "LP",
                artworkUri = "https://art.example/r.jpg",
                durationMs = 200_000L,
                trackNumber = 2
            ),
            recordingMbid = "mbid-1",
            youtubeQueryOrId = "Band Remote",
            resolved = ResolvedStream(
                audioUrl = "https://googlevideo.com/expire/secret.m4a",
                userAgent = "UA",
                videoId = "dQw4w9WgXcQ",
                resolvedAtEpochMs = 1L
            )
        )
        val snapshot = QueueSnapshotCodec.fromPlayable(
            items = listOf(local, remote),
            currentIndex = 1,
            positionMs = 12_000L,
            shufflePlayOrder = listOf(1, 0)
        )
        val encoded = QueueSnapshotCodec.encode(snapshot)
        assertFalse(encoded.contains("audioUrl"))
        assertFalse(encoded.contains("googlevideo"))
        assertFalse(encoded.contains(local.queueEntryId))
        assertFalse(encoded.contains(remote.queueEntryId))

        val restored = QueueSnapshotCodec.decode(encoded)!!
        assertEquals(1, restored.currentIndex)
        assertEquals(12_000L, restored.positionMs)
        assertEquals(2, restored.items.size)
        assertEquals(listOf(1, 0), restored.shufflePlayOrder)

        val localItem = restored.items[0] as PersistedQueueItem.Local
        assertEquals(1L, localItem.songId)
        assertEquals("content://song/1", localItem.uriString)
        assertEquals("Song 1", localItem.title)

        val remoteItem = restored.items[1] as PersistedQueueItem.Remote
        assertEquals("Remote", remoteItem.identity.title)
        assertEquals("Band", remoteItem.identity.artist)
        assertEquals("mbid-1", remoteItem.recordingMbid)
        assertEquals("Band Remote", remoteItem.youtubeQueryOrId)
        assertEquals("dQw4w9WgXcQ", remoteItem.videoId)
    }

    @Test
    fun decode_blankOrInvalid_returnsNull() {
        assertNull(QueueSnapshotCodec.decode(""))
        assertNull(QueueSnapshotCodec.decode("not-json"))
        assertNull(QueueSnapshotCodec.decode("""{"currentIndex":0}"""))
        assertNull(QueueSnapshotCodec.decode("""{"currentIndex":0,"items":[]}"""))
    }

    @Test
    fun decode_ignoresInjectedAudioUrlAndBlankLocal() {
        val json = """
            {"currentIndex":0,"positionMs":1,"items":[
              {"kind":"local","songId":0,"uriString":""},
              {"kind":"remote","title":"T","artist":"A","audioUrl":"https://cdn.example/x","youtubeQueryOrId":"q"}
            ]}
        """.trimIndent()
        val restored = QueueSnapshotCodec.decode(json)!!
        assertEquals(1, restored.items.size)
        val remote = restored.items[0] as PersistedQueueItem.Remote
        assertEquals("T", remote.identity.title)
        assertEquals("q", remote.youtubeQueryOrId)
        assertTrue(QueueSnapshotCodec.encode(restored).let { !it.contains("audioUrl") })
        assertNull(restored.shufflePlayOrder)
    }

    @Test
    fun decode_legacyJsonWithoutPlayOrder_isNull() {
        val json = """{"currentIndex":0,"positionMs":0,"items":[
            {"kind":"local","songId":1,"uriString":"content://song/1","title":"T","artist":"A"}
        ]}"""
        val restored = QueueSnapshotCodec.decode(json)!!
        assertNull(restored.shufflePlayOrder)
        assertEquals(1, restored.items.size)
    }

    @Test
    fun fromPlayable_rejectsInvalidPlayOrder() {
        val snapshot = QueueSnapshotCodec.fromPlayable(
            items = listOf(song(1).toPlayable(), song(2).toPlayable()),
            currentIndex = 0,
            positionMs = 0L,
            shufflePlayOrder = listOf(0, 0)
        )
        assertNull(snapshot.shufflePlayOrder)
    }
}
