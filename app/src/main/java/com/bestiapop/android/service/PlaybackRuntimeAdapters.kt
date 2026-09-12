package com.bestiapop.android.service

import com.bestiapop.android.data.listenbrainz.ListenTracker
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.stream.StreamResolver

internal class PlaybackSessionStoreRuntimePersistence(
    private val store: PlaybackSessionStore
) : PlaybackRuntimePersistence {
    override suspend fun loadLastPlayed() = store.load()
    override suspend fun loadQueue() = store.loadQueue()
    override suspend fun saveSession(
        lastPlayed: LastPlayedSnapshot?,
        queue: QueueSnapshot?,
        clearQueue: Boolean
    ) {
        store.saveSession(lastPlayed, queue, clearQueue)
    }
}

internal class ListenTrackerRuntimeAdapter(
    private val tracker: ListenTracker
) : PlaybackRuntimeListenTracker {
    override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) =
        tracker.onTrackChanged(song, hint)

    override fun onDurationKnown(songId: Long, durationMs: Long) =
        tracker.onDurationKnown(songId, durationMs)

    override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) =
        tracker.onPlaybackTick(isPlaying, elapsedRealtimeMs)

    override fun onStopped() = tracker.onStopped()

    override fun creditPlaybackTime(timeMs: Long) = tracker.creditPlaybackTime(timeMs)
}

internal class StreamResolverRuntimeAccess(
    private val resolver: StreamResolver,
    private val clockMs: () -> Long
) : PlaybackRuntimeStreamAccess {
    override fun needsResolve(item: PlayableItem.Remote): Boolean {
        val resolved = item.resolved ?: return true
        return resolved.audioUrl.isBlank() || !resolver.isFresh(resolved)
    }

    override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? =
        resolver.resolve(item)
            .getOrNull()
            ?.let { item.copy(resolved = it) }

    override suspend fun invalidate(item: PlayableItem.Remote) {
        resolver.invalidate(item)
    }
}
