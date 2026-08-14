package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.db.toEntity
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.playback.PlaybackTrackChange
import com.bestiapop.android.data.playback.PlaybackTrackChangePolicy
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tracks played time for the current track and enqueues a ListenBrainz listen
 * once the official threshold is met: half the track or 4 minutes, whichever is lower.
 */
class ListenTracker private constructor(
    private val enqueueListen: (ListenPayload) -> Unit,
    private val nowEpochSeconds: () -> Long
) {
    constructor(
        scope: CoroutineScope,
        pendingListenDao: PendingListenDao,
        preferences: ListenBrainzPreferencesRepository,
        onListenEnqueued: () -> Unit
    ) : this(
        enqueueListen = { payload ->
            scope.launch {
                val settings = preferences.settingsFlow.first()
                if (!settings.enabled || settings.userToken.isBlank()) return@launch
                pendingListenDao.insert(payload.toEntity())
                onListenEnqueued()
            }
        },
        nowEpochSeconds = { System.currentTimeMillis() / 1000L }
    )

    internal constructor(
        nowEpochSeconds: () -> Long,
        onListenReady: (ListenPayload) -> Unit
    ) : this(
        enqueueListen = onListenReady,
        nowEpochSeconds = nowEpochSeconds
    )

    private var activeSong: Song? = null
    private var listenedAtSec: Long = 0L
    private var playedMs: Long = 0L
    private var alreadySubmitted: Boolean = false
    private var lastTickElapsedRealtime: Long = 0L

    /**
     * Updates the active song without losing progress when a queue mutation merely re-emits it.
     * Repeat One and duplicate queue occurrences must pass [PlaybackChangeHint.NEW_PLAYBACK].
     */
    fun onTrackChanged(
        song: Song?,
        hint: PlaybackChangeHint = PlaybackChangeHint.AUTO
    ) {
        when (PlaybackTrackChangePolicy.resolve(activeSong, song, hint)) {
            PlaybackTrackChange.METADATA_UPDATE -> {
                val previousDuration = activeSong?.durationMs ?: 0L
                activeSong = song?.let {
                    if (it.durationMs > 0L || previousDuration <= 0L) {
                        it
                    } else {
                        it.copy(durationMs = previousDuration)
                    }
                }
                maybeEnqueueIfReady()
            }
            PlaybackTrackChange.NEW_PLAYBACK -> {
                maybeEnqueueIfReady()
                activeSong = song
                listenedAtSec = nowEpochSeconds()
                playedMs = 0L
                alreadySubmitted = false
                lastTickElapsedRealtime = 0L
            }
            PlaybackTrackChange.STOPPED -> {
                maybeEnqueueIfReady()
                activeSong = null
                listenedAtSec = 0L
                playedMs = 0L
                alreadySubmitted = false
                lastTickElapsedRealtime = 0L
            }
        }
    }

    /** Keeps threshold accurate when duration is discovered mid-playback. */
    fun onDurationKnown(songId: Long, durationMs: Long) {
        val song = activeSong ?: return
        if (song.id == songId && durationMs > 0 && song.durationMs <= 0) {
            activeSong = song.copy(durationMs = durationMs)
            maybeEnqueueIfReady()
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
                val delta = (elapsedRealtimeMs - lastTickElapsedRealtime).coerceAtLeast(0L)
                playedMs += delta
            }
            lastTickElapsedRealtime = elapsedRealtimeMs
            maybeEnqueueIfReady()
        } else {
            if (lastTickElapsedRealtime > 0L) {
                val delta = (elapsedRealtimeMs - lastTickElapsedRealtime).coerceAtLeast(0L)
                playedMs += delta
            }
            lastTickElapsedRealtime = 0L
            maybeEnqueueIfReady()
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

        enqueueListen(ListenPayload.fromIdentity(song.toIdentity(), listenedAtSec))
    }

    companion object {
        private const val FOUR_MINUTES_MS = 4 * 60_000L
    }
}
