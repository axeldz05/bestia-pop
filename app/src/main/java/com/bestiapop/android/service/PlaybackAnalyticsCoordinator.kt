package com.bestiapop.android.service

import com.bestiapop.android.data.listenbrainz.SaveWhileListeningEvent
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.playback.PlaybackChangeHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FALLBACK_SUCCESS_POSITION_MS = 1_000L

internal class PlaybackAnalyticsCoordinator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getCurrentItem: () -> PlayableItem?,
    private val setCurrentItem: (PlayableItem?, Boolean, PlaybackChangeHint) -> Unit,
    private val getCurrentSong: () -> Song?,
    private val setCurrentSong: (Song?) -> Unit,
    private val getQueue: () -> List<PlayableItem>,
    private val setQueue: (List<PlayableItem>) -> Unit,
    private val getController: () -> PlaybackControllerFacade?,
    private val getIsPlaying: () -> Boolean,
    private val setIsPlaying: (Boolean) -> Unit,
    private val getPlaybackPositionMs: () -> Long,
    private val setPlaybackPositionMs: (Long) -> Unit,
    private val getLastSeekTimestamp: () -> Long,
    private val clearRemoteRecoveryAfterProgress: () -> Unit,
    private val clearRejectedQueueEntries: () -> Unit,
    private val maybeSaveWhileListening: (PlayableItem.Remote, SaveWhileListeningEvent, Long, Long) -> Unit
) {
    private var lastTouchedSongId = -1L
    private var lyricsHydrateJob: Job? = null

    fun creditItemPlayback(item: PlayableItem?, completed: Boolean = false) {
        val playedMs = if (completed && item != null && item.durationMs > 0L) {
            item.durationMs
        } else {
            getPlaybackPositionMs()
        }
        if (playedMs > 0L) {
            dependencies.listenTracker.creditPlaybackTime(playedMs)
        }
    }

    fun touchLastPlayed(song: Song?) {
        if (song == null || song.id <= 0L || song.id == lastTouchedSongId) return
        lastTouchedSongId = song.id
        scope.launch(Dispatchers.IO) { dependencies.touchSongLastPlayed(song.id) }
    }

    fun triggerFlushPostponedTagWrites() {
        val activeId = (getCurrentItem() as? PlayableItem.Local)?.song?.id
        scope.launch(dependencies.ioDispatcher) {
            dependencies.flushPostponedTagWrites(activeId)
        }
    }

    fun hydrateCurrentSongLyrics(songId: Long) {
        lyricsHydrateJob?.cancel()
        lyricsHydrateJob = scope.launch(dependencies.ioDispatcher) {
            val full = dependencies.loadSongById(songId) ?: return@launch
            if (full.lyrics.isNullOrEmpty()) return@launch
            withContext(scope.coroutineContext) {
                applyLyricsToCurrent(songId, full.lyrics)
            }
        }
    }

    fun updateCurrentSongLyrics(songId: Long, lyrics: String?) {
        applyLyricsToCurrent(songId, lyrics)
    }

    fun updateCurrentItemLyrics(lyrics: String?) {
        val cur = getCurrentItem() ?: return
        when (cur) {
            is PlayableItem.Local -> applyLyricsToCurrent(cur.song.id, lyrics)
            is PlayableItem.Remote -> setCurrentItem(cur.copy(lyrics = lyrics), false, PlaybackChangeHint.METADATA_UPDATE)
        }
    }

    fun applyLyricsToCurrent(songId: Long, lyrics: String?) {
        val current = getCurrentSong()
        if (current?.id == songId) {
            setCurrentSong(current.copy(lyrics = lyrics))
        }
        val curItem = getCurrentItem()
        if (curItem is PlayableItem.Local && curItem.song.id == songId) {
            setCurrentItem(curItem.copy(song = curItem.song.copy(lyrics = lyrics)), false, PlaybackChangeHint.METADATA_UPDATE)
        } else if (curItem is PlayableItem.Remote && songId < 0) {
            setCurrentItem(curItem.copy(lyrics = lyrics), false, PlaybackChangeHint.METADATA_UPDATE)
        }
    }

    suspend fun samplePositionAndOwnership() {
        val player = getController() ?: return
        if (getIsPlaying() != player.isPlaying) setIsPlaying(player.isPlaying)
        if (player.isPlaying && dependencies.clockMs() - getLastSeekTimestamp() > 600L) {
            val pos = player.currentPosition.coerceAtLeast(0L)
            setPlaybackPositionMs(pos)
            if (pos >= FALLBACK_SUCCESS_POSITION_MS) {
                clearRemoteRecoveryAfterProgress()
                clearRejectedQueueEntries()
            }
            val duration = player.duration
            val current = getCurrentItem()
            if (duration > 0L && current != null && current.durationMs <= 0L) {
                when (current) {
                    is PlayableItem.Local -> {
                        scope.launch(Dispatchers.IO) {
                            dependencies.updateSongDuration(current.song.id, duration)
                        }
                        dependencies.listenTracker.onDurationKnown(current.song.id, duration)
                    }

                    is PlayableItem.Remote -> {
                        val index = player.currentMediaItemIndex
                        val updated = current.withIdentity { copy(durationMs = duration) }
                        val live = getQueue().toMutableList()
                        if (index in live.indices) {
                            live[index] = updated
                            setQueue(live)
                            setCurrentItem(
                                updated,
                                false,
                                PlaybackChangeHint.METADATA_UPDATE
                            )
                        }
                    }
                }
            }
            (current as? PlayableItem.Remote)?.let { remote ->
                maybeSaveWhileListening(
                    remote,
                    SaveWhileListeningEvent.PROGRESS,
                    getPlaybackPositionMs(),
                    remote.durationMs.takeIf { it > 0L } ?: duration
                )
            }
        } else if (!player.isPlaying && player.mediaItemCount > 0) {
            setPlaybackPositionMs(player.currentPosition.coerceAtLeast(0L))
        }
        dependencies.listenTracker.onPlaybackTick(
            player.isPlaying,
            dependencies.elapsedRealtimeMs()
        )
    }
}
