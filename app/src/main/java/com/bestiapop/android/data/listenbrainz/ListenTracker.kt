package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.db.toEntity
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tracks played time for the current track and enqueues a ListenBrainz listen
 * once the official threshold is met: half the track or 4 minutes, whichever is lower.
 */
class ListenTracker(
    private val scope: CoroutineScope,
    private val pendingListenDao: PendingListenDao,
    private val preferences: ListenBrainzPreferencesRepository,
    private val onListenEnqueued: () -> Unit
) {
    private var activeSong: Song? = null
    private var listenedAtSec: Long = 0L
    private var playedMs: Long = 0L
    private var alreadySubmitted: Boolean = false
    private var lastTickElapsedRealtime: Long = 0L

    fun onTrackChanged(song: Song?) {
        maybeEnqueueIfReady()
        activeSong = song
        listenedAtSec = System.currentTimeMillis() / 1000L
        playedMs = 0L
        alreadySubmitted = false
        lastTickElapsedRealtime = 0L
    }

    /** Keeps threshold accurate when duration is discovered mid-playback. */
    fun onDurationKnown(songId: Long, durationMs: Long) {
        val song = activeSong ?: return
        if (song.id == songId && durationMs > 0 && song.durationMs <= 0) {
            activeSong = song.copy(durationMs = durationMs)
        }
    }

    /**
     * Call periodically while playback may be active.
     * @param isPlaying whether the player is currently playing
     * @param elapsedRealtimeMs SystemClock.elapsedRealtime()
     */
    fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) {
        if (activeSong == null) return

        if (isPlaying) {
            if (lastTickElapsedRealtime > 0L) {
                val delta = (elapsedRealtimeMs - lastTickElapsedRealtime).coerceIn(0L, 2_000L)
                playedMs += delta
            }
            lastTickElapsedRealtime = elapsedRealtimeMs
            maybeEnqueueIfReady()
        } else {
            lastTickElapsedRealtime = 0L
        }
    }

    fun onStopped() {
        maybeEnqueueIfReady()
        lastTickElapsedRealtime = 0L
    }

    private fun thresholdMs(song: Song): Long {
        val duration = song.durationMs
        return if (duration > 0) {
            minOf(duration / 2, FOUR_MINUTES_MS)
        } else {
            FOUR_MINUTES_MS
        }
    }

    private fun maybeEnqueueIfReady() {
        val song = activeSong ?: return
        if (alreadySubmitted) return
        if (playedMs < thresholdMs(song)) return
        alreadySubmitted = true

        val entity = ListenPayload.fromIdentity(song.toIdentity(), listenedAtSec).toEntity()

        scope.launch {
            val settings = preferences.settingsFlow.first()
            if (!settings.enabled || settings.userToken.isBlank()) return@launch
            pendingListenDao.insert(entity)
            onListenEnqueued()
        }
    }

    companion object {
        private const val FOUR_MINUTES_MS = 4 * 60_000L
    }
}
