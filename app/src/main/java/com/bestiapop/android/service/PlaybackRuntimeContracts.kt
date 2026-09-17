package com.bestiapop.android.service

import android.os.SystemClock
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.playback.PlaybackChangeHint
import com.bestiapop.android.data.preferences.HydratedQueue
import com.bestiapop.android.data.preferences.LastPlayedSnapshot
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackSettings
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.util.compareSongsWithinAlbum
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.util.albumNamesMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

internal fun controllerReconnectBackoffMs(attempt: Int): Long {
    val exponent = (attempt - 1).coerceIn(0, 4)
    return 500L * (1L shl exponent)
}

/** Keep already-hydrated LRC when the library row is identity-slim (`lyrics` null). */
internal fun Song.keepLyricsIfIncomingSlim(incoming: Song): Song =
    if (incoming.lyrics.isNullOrEmpty() && !lyrics.isNullOrEmpty()) incoming.copy(lyrics = lyrics)
    else incoming

internal fun refreshLocalQueueMetadata(
    queue: List<PlayableItem>,
    songs: List<Song>
): List<PlayableItem> {
    val localItems = queue.filterIsInstance<PlayableItem.Local>()
    if (localItems.isEmpty() || songs.isEmpty()) return queue
    val targetIds = HashSet<Long>(localItems.size)
    val targetUris = HashSet<String>(localItems.size)
    for (item in localItems) {
        if (item.song.id > 0L) targetIds.add(item.song.id)
        targetUris.add(item.song.uriString)
    }
    val byId = HashMap<Long, IndexedValue<Song>>(targetIds.size)
    val byUri = HashMap<String, IndexedValue<Song>>(targetUris.size)
    songs.forEachIndexed { index, song ->
        val matchId = song.id > 0L && song.id in targetIds
        val matchUri = song.uriString in targetUris
        if (matchId || matchUri) {
            val indexed = IndexedValue(index, song)
            if (matchId) byId.putIfAbsent(song.id, indexed)
            if (matchUri) byUri.putIfAbsent(song.uriString, indexed)
        }
    }
    return queue.map { item ->
        if (item !is PlayableItem.Local) return@map item
        val idMatch = item.song.id.takeIf { it > 0L }?.let(byId::get)
        val uriMatch = byUri[item.song.uriString]
        val refreshed = when {
            idMatch == null -> uriMatch
            uriMatch == null -> idMatch
            idMatch.index <= uriMatch.index -> idMatch
            else -> uriMatch
        }?.value
        refreshed?.let { incoming ->
            item.copy(song = item.song.keepLyricsIfIncomingSlim(incoming))
        } ?: item
    }
}

/**
 * Detects if [queue] represents an ordered album playback queue (all items belong to the same
 * album, not in shuffle mode), and returns the reordered list sorted by album track number
 * if the track order changed. Returns null if reordering is not needed or not applicable.
 */
internal fun reorderAlbumQueueByTrackNumber(
    queue: List<PlayableItem>,
    isShuffle: Boolean
): List<PlayableItem>? {
    if (isShuffle || queue.size < 2) return null
    val firstLocal = queue.firstOrNull() as? PlayableItem.Local ?: return null
    val firstAlbum = firstLocal.song.album
    if (firstAlbum.isBlank()) return null
    if (!queue.all { it is PlayableItem.Local && albumNamesMatch(it.song.album, firstAlbum) }) {
        return null
    }
    val sorted = queue.sortedWith { a, b ->
        compareSongsWithinAlbum(
            (a as PlayableItem.Local).song,
            (b as PlayableItem.Local).song
        )
    }
    return if (sorted != queue) sorted else null
}

internal interface PlaybackRuntimePersistence {
    suspend fun loadLastPlayed(): LastPlayedSnapshot? = null
    suspend fun loadQueue(): QueueSnapshot? = null
    suspend fun saveSession(
        lastPlayed: LastPlayedSnapshot?,
        queue: QueueSnapshot?,
        clearQueue: Boolean
    ) = Unit
}

internal interface PlaybackRuntimeListenTracker {
    fun onTrackChanged(song: Song?, hint: PlaybackChangeHint)
    fun onDurationKnown(songId: Long, durationMs: Long)
    fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long)
    fun onStopped()
    fun creditPlaybackTime(timeMs: Long) = Unit
}

internal sealed interface SaveWhileListeningDownloadResult {
    data class Saved(val song: Song) : SaveWhileListeningDownloadResult
    data class InFlight(val downloadId: String) : SaveWhileListeningDownloadResult
    data class Failed(val error: Throwable) : SaveWhileListeningDownloadResult
}

internal interface PlaybackRuntimeSaveDownloads {
    val downloads: StateFlow<List<ActiveDownload>>
    suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult
    fun dismiss(id: String)
}

internal interface PlaybackRuntimeStreamAccess {
    fun needsResolve(item: PlayableItem.Remote): Boolean
    suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote?
    suspend fun invalidate(item: PlayableItem.Remote)
}

internal data class PlaybackRuntimeRadioRequest(
    val seed: PlayableItem,
    val library: List<Song>,
    val mode: RadioMode,
    val excludeKeys: Set<String>,
    val settings: ListenBrainzSettings,
    val timeoutMs: Long,
    val coPlaylistSongIds: Set<Long>
)

internal fun interface PlaybackRuntimeRadioSuggester {
    suspend fun suggest(request: PlaybackRuntimeRadioRequest): RadioSuggestResult
}

internal data class PlaybackRuntimeDependencies(
    val scope: CoroutineScope,
    val libraryUpdates: Flow<List<Song>> = flowOf(emptyList()),
    val playbackSettings: StateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings()),
    val playbackSettingsReady: StateFlow<Boolean> = MutableStateFlow(true),
    val listenSettings: StateFlow<ListenBrainzSettings> = MutableStateFlow(ListenBrainzSettings()),
    val listenSettingsReady: StateFlow<Boolean> = MutableStateFlow(true),
    val persistence: PlaybackRuntimePersistence = object : PlaybackRuntimePersistence {},
    val listenTracker: PlaybackRuntimeListenTracker = object : PlaybackRuntimeListenTracker {
        override fun onTrackChanged(song: Song?, hint: PlaybackChangeHint) = Unit
        override fun onDurationKnown(songId: Long, durationMs: Long) = Unit
        override fun onPlaybackTick(isPlaying: Boolean, elapsedRealtimeMs: Long) = Unit
        override fun onStopped() = Unit
    },
    val streamAccess: PlaybackRuntimeStreamAccess,
    val saveDownloads: PlaybackRuntimeSaveDownloads = object : PlaybackRuntimeSaveDownloads {
        override val downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
        override suspend fun save(remote: PlayableItem.Remote): SaveWhileListeningDownloadResult =
            SaveWhileListeningDownloadResult.Failed(
                IllegalStateException("Save while listening unavailable")
            )

        override fun dismiss(id: String) = Unit
    },
    val radioSuggester: PlaybackRuntimeRadioSuggester = PlaybackRuntimeRadioSuggester {
        RadioSuggestResult(emptyList(), usedOnlineDiscovery = false, onlineDiscoveryFailed = false)
    },
    val resolveCoPlaylistSongIds: suspend (PlayableItem) -> Set<Long> = { emptySet() },
    val isOnline: () -> Boolean = { false },
    val persistShuffle: suspend (Boolean) -> Unit = {},
    val persistRepeat: suspend (RepeatMode) -> Unit = {},
    val touchItemLastPlayed: suspend (PlayableItem, Long) -> Unit = { _, _ -> },
    val updateSongDuration: suspend (Long, Long) -> Unit = { _, _ -> },
    val loadSongById: suspend (Long) -> Song? = { null },
    val loadSongsByIds: suspend (List<Long>) -> List<Song> = { emptyList() },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val requestListenSync: () -> Unit = {},
    val flushPostponedTagWrites: suspend (Long?) -> Unit = {},
    val clockMs: () -> Long = System::currentTimeMillis,
    val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    val controllerReconnectBackoffMs: (attempt: Int) -> Long = ::controllerReconnectBackoffMs,
    val startTicker: Boolean = true
)

internal data class PlaybackPersistenceRequest(
    val lastPlayed: LastPlayedSnapshot?,
    val queue: QueueSnapshot?,
    val clearQueue: Boolean
)

internal data class PersistedCollectionProjection(
    val snapshot: PlaybackCollectionSnapshot,
    val hydratedQueue: HydratedQueue? = null,
    val restoreShuffle: Boolean = false
)
