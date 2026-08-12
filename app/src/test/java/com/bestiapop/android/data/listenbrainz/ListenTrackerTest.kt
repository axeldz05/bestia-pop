package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.data.playback.PlaybackChangeHint
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTrackerTest {

    @Test
    fun sameIdentityQueueMutation_doesNotLoseAccumulatedProgress() {
        var nowSec = 100L
        val listens = mutableListOf<ListenPayload>()
        val tracker = tracker(nowSec = { nowSec }, listens = listens)
        val song = song(durationMs = 4_000L)

        tracker.onTrackChanged(song)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 1_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 2_000L)

        nowSec = 200L
        tracker.onTrackChanged(song.copy(title = "Corrected title"))
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 3_000L)

        assertEquals(1, listens.size)
        assertEquals("Corrected title", listens.single().trackName)
        assertEquals(100L, listens.single().listenedAt)
    }

    @Test
    fun sameIdentityQueueMutation_afterSubmission_doesNotDuplicateListen() {
        val listens = mutableListOf<ListenPayload>()
        val tracker = tracker(nowSec = { 100L }, listens = listens)
        val song = song(durationMs = 2_000L)

        tracker.onTrackChanged(song)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 1_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 2_000L)
        tracker.onTrackChanged(song.copy(artworkUri = "file:/new-cover.jpg"))
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 3_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 4_000L)

        assertEquals(1, listens.size)
    }

    @Test
    fun explicitRepeat_resetsProgressAndSubmissionForSameIdentity() {
        var nowSec = 100L
        val listens = mutableListOf<ListenPayload>()
        val tracker = tracker(nowSec = { nowSec }, listens = listens)
        val song = song(durationMs = 2_000L)

        tracker.onTrackChanged(song)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 1_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 2_000L)

        nowSec = 200L
        tracker.onTrackChanged(song, PlaybackChangeHint.NEW_PLAYBACK)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 3_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 4_000L)

        assertEquals(listOf(100L, 200L), listens.map { it.listenedAt })
    }

    @Test
    fun differentSong_resetsProgressWithoutAnExplicitHint() {
        val listens = mutableListOf<ListenPayload>()
        val tracker = tracker(nowSec = { 100L }, listens = listens)

        tracker.onTrackChanged(song(id = 1L, durationMs = 4_000L))
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 1_000L)
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 2_000L)
        tracker.onTrackChanged(song(id = 2L, durationMs = 2_000L))
        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 3_000L)

        assertEquals(0, listens.size)

        tracker.onPlaybackTick(isPlaying = true, elapsedRealtimeMs = 4_000L)
        assertEquals(1, listens.size)
        assertEquals("Song 2", listens.single().trackName)
    }

    private fun tracker(
        nowSec: () -> Long,
        listens: MutableList<ListenPayload>
    ) = ListenTracker(
        nowEpochSeconds = nowSec,
        onListenReady = listens::add
    )

    private fun song(
        id: Long = 1L,
        durationMs: Long
    ) = Song(
        id = id,
        uriString = "/music/$id.mp3",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        durationMs = durationMs
    )
}
