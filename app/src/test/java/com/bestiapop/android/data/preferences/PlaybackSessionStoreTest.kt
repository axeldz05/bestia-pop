package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionStoreTest {

    private fun song(
        id: Long,
        uri: String = "content://song/$id",
        title: String = "Song $id"
    ) = Song(
        id = id,
        uriString = uri,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        artworkUri = "file:///art/$id.jpg"
    )

    @Test
    fun codec_roundTrip_preservesFields() {
        val original = LastPlayedSnapshot(
            songId = 42L,
            uriString = "content://music/42",
            positionMs = 12_345L,
            title = "Hello",
            artist = "World",
            album = "LP",
            artworkUri = "file:///cover.jpg",
            durationMs = 200_000L
        )
        val restored = LastPlayedCodec.decode(LastPlayedCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun codec_decode_blankOrInvalid_returnsNull() {
        assertNull(LastPlayedCodec.decode(""))
        assertNull(LastPlayedCodec.decode("not-json"))
        assertNull(LastPlayedCodec.decode("""{"songId":1}"""))
    }

    @Test
    fun resolveIdleSeed_prefersLastPlayedById() {
        val library = listOf(song(1), song(2), song(3))
        val last = LastPlayedSnapshot(songId = 2L, uriString = "other", positionMs = 1000L)
        val picked = PlaybackHydration.resolveIdleSeed(library, last) { error("should not random") }
        assertEquals(2L, picked?.id)
    }

    @Test
    fun resolveIdleSeed_fallsBackToUriThenRandom() {
        val library = listOf(song(1), song(2, uri = "content://x"), song(3))
        val last = LastPlayedSnapshot(songId = 99L, uriString = "content://x", positionMs = 0L)
        val byUri = PlaybackHydration.resolveIdleSeed(library, last) { error("no") }
        assertEquals(2L, byUri?.id)

        val missing = LastPlayedSnapshot(songId = 99L, uriString = "missing", positionMs = 0L)
        val randomPick = song(3)
        val picked = PlaybackHydration.resolveIdleSeed(library, missing) { randomPick }
        assertEquals(3L, picked?.id)
    }

    @Test
    fun resolveIdleSeed_emptyLibrary_returnsNull() {
        assertNull(PlaybackHydration.resolveIdleSeed(emptyList(), null))
        assertNull(
            PlaybackHydration.resolveIdleSeed(
                emptyList(),
                LastPlayedSnapshot(1L, "uri")
            )
        )
    }

    @Test
    fun resolveIdleSeed_matchesSafSnapshotToAbsLibraryUri() {
        val abs = "/storage/emulated/0/Music/BestiaPop/01_pretext.mp3"
        val saf =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBestiaPop/document/primary%3AMusic%2FBestiaPop%2F01_pretext.mp3"
        val library = listOf(song(1, uri = abs).copy(folderPath = "/storage/emulated/0/Music/BestiaPop"))
        val last = LastPlayedSnapshot(songId = 99L, uriString = saf, positionMs = 1500L)
        val picked = PlaybackHydration.resolveIdleSeed(library, last) { error("no random") }
        assertEquals(1L, picked?.id)
        assertEquals(1500L, PlaybackHydration.resumePositionMs(library[0], last))
    }

    @Test
    fun resumePositionMs_onlyWhenMatchingAndCapped() {
        val s = song(5).copy(durationMs = 10_000L)
        val last = LastPlayedSnapshot(
            songId = 5L,
            uriString = s.uriString,
            positionMs = 50_000L,
            durationMs = 10_000L
        )
        assertEquals(10_000L, PlaybackHydration.resumePositionMs(s, last))
        assertEquals(0L, PlaybackHydration.resumePositionMs(song(9), last))
        assertEquals(0L, PlaybackHydration.resumePositionMs(s, null))
    }

    @Test
    fun snapshotFromSong_mapsFields() {
        val s = song(7)
        val snap = PlaybackHydration.snapshotFromSong(s, 900L)
        assertEquals(7L, snap.songId)
        assertEquals(s.uriString, snap.uriString)
        assertEquals(900L, snap.positionMs)
        assertEquals(s.title, snap.title)
        assertEquals(s.artworkUri, snap.artworkUri)
        assertTrue(LastPlayedCodec.decode(LastPlayedCodec.encode(snap)) == snap)
    }

    @Test
    fun hydrateQueue_matchesLocalByIdAndUri() {
        val library = listOf(song(1), song(2, uri = "content://x"), song(3))
        val snapshot = QueueSnapshot(
            currentIndex = 1,
            positionMs = 4_000L,
            items = listOf(
                PersistedQueueItem.Local(songId = 1L, uriString = "content://song/1"),
                PersistedQueueItem.Local(songId = 99L, uriString = "content://x"),
                PersistedQueueItem.Local(songId = 3L, uriString = "content://song/3")
            )
        )
        val hydrated = PlaybackHydration.hydrateQueue(snapshot, library)!!
        assertEquals(3, hydrated.items.size)
        assertEquals(1, hydrated.currentIndex)
        assertEquals(2L, (hydrated.items[1] as PlayableItem.Local).song.id)
        assertEquals(4_000L, hydrated.positionMs)
    }

    @Test
    fun hydrateQueue_skipsDeletedCurrent_advancesAndClearsPosition() {
        val library = listOf(song(1), song(3))
        val snapshot = QueueSnapshot(
            currentIndex = 1,
            positionMs = 9_000L,
            items = listOf(
                PersistedQueueItem.Local(songId = 1L, uriString = "content://song/1"),
                PersistedQueueItem.Local(songId = 2L, uriString = "content://song/2"),
                PersistedQueueItem.Local(songId = 3L, uriString = "content://song/3")
            )
        )
        val hydrated = PlaybackHydration.hydrateQueue(snapshot, library)!!
        assertEquals(2, hydrated.items.size)
        assertEquals(1, hydrated.currentIndex)
        assertEquals(3L, (hydrated.items[1] as PlayableItem.Local).song.id)
        assertEquals(0L, hydrated.positionMs)
    }

    @Test
    fun hydrateQueue_keepsRemoteAndCapsResumePosition() {
        val library = listOf(song(1).copy(durationMs = 10_000L))
        val snapshot = QueueSnapshot(
            currentIndex = 0,
            positionMs = 50_000L,
            items = listOf(
                PersistedQueueItem.Local(
                    songId = 1L,
                    uriString = "content://song/1",
                    durationMs = 10_000L
                ),
                PersistedQueueItem.Remote(
                    identity = TrackIdentity(title = "R", artist = "A"),
                    youtubeQueryOrId = "A R"
                )
            )
        )
        val hydrated = PlaybackHydration.hydrateQueue(snapshot, library)!!
        assertEquals(2, hydrated.items.size)
        assertTrue(hydrated.items[1] is PlayableItem.Remote)
        assertEquals(10_000L, hydrated.positionMs)
    }

    @Test
    fun hydrateQueue_nullOrAllDeleted_returnsNull() {
        assertNull(PlaybackHydration.hydrateQueue(null, listOf(song(1))))
        val gone = QueueSnapshot(
            currentIndex = 0,
            positionMs = 0L,
            items = listOf(PersistedQueueItem.Local(songId = 9L, uriString = "missing"))
        )
        assertNull(PlaybackHydration.hydrateQueue(gone, listOf(song(1))))
    }
}
