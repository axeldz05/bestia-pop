package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackChangePolicyTest {

    @Test
    fun sameSongWithUpdatedMetadata_isNotANewPlayback() {
        val previous = song(id = 7L, title = "Old title")
        val updated = previous.copy(title = "Correct title", durationMs = 180_000L)

        assertTrue(PlaybackTrackChangePolicy.sameIdentity(previous, updated))
        assertEquals(
            PlaybackTrackChange.METADATA_UPDATE,
            PlaybackTrackChangePolicy.resolve(previous, updated)
        )
    }

    @Test
    fun unsavedCopies_useStableUriIdentity() {
        val previous = song(id = 0L, uri = "/music/song.mp3")
        val updated = previous.copy(artworkUri = "file:/covers/song.jpg")

        assertTrue(PlaybackTrackChangePolicy.sameIdentity(previous, updated))
        assertEquals(
            PlaybackTrackChange.METADATA_UPDATE,
            PlaybackTrackChangePolicy.resolve(
                previous,
                updated,
                PlaybackChangeHint.METADATA_UPDATE
            )
        )
    }

    @Test
    fun differentIdentity_isAlwaysANewPlayback() {
        val previous = song(id = 7L, uri = "/music/one.mp3")
        val current = song(id = 8L, uri = "/music/two.mp3")

        assertFalse(PlaybackTrackChangePolicy.sameIdentity(previous, current))
        assertEquals(
            PlaybackTrackChange.NEW_PLAYBACK,
            PlaybackTrackChangePolicy.resolve(
                previous,
                current,
                PlaybackChangeHint.METADATA_UPDATE
            )
        )
    }

    @Test
    fun explicitTransition_restartsEvenForSameIdentity() {
        val repeated = song(id = 7L)

        assertEquals(
            PlaybackTrackChange.NEW_PLAYBACK,
            PlaybackTrackChangePolicy.resolve(
                repeated,
                repeated,
                PlaybackChangeHint.NEW_PLAYBACK
            )
        )
    }

    private fun song(
        id: Long,
        uri: String = "/music/$id.mp3",
        title: String = "Song"
    ) = Song(
        id = id,
        uriString = uri,
        title = title,
        artist = "Artist",
        durationMs = 120_000L
    )
}
