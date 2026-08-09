package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.optNullableString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.playbackSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_session"
)

/**
 * Last local track shown / played in the mini player.
 * Never stores remote CDN URLs.
 */
data class LastPlayedSnapshot(
    val songId: Long,
    val uriString: String,
    val positionMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val durationMs: Long = 0L
)

sealed class PersistedQueueItem {
    data class Local(
        val songId: Long,
        val uriString: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artworkUri: String? = null,
        val durationMs: Long = 0L,
        val trackNumber: Int = 0
    ) : PersistedQueueItem()

    data class Remote(
        val identity: TrackIdentity,
        val recordingMbid: String? = null,
        val youtubeQueryOrId: String? = null,
        val videoId: String? = null
    ) : PersistedQueueItem()
}

data class QueueSnapshot(
    val currentIndex: Int,
    val positionMs: Long,
    val items: List<PersistedQueueItem>
)

data class HydratedQueue(
    val items: List<PlayableItem>,
    val currentIndex: Int,
    val positionMs: Long
)

object LastPlayedCodec {
    fun encode(snapshot: LastPlayedSnapshot): String =
        JSONObject().apply {
            put("songId", snapshot.songId)
            put("uriString", snapshot.uriString)
            put("positionMs", snapshot.positionMs)
            put("title", snapshot.title)
            put("artist", snapshot.artist)
            put("album", snapshot.album)
            put("artworkUri", snapshot.artworkUri ?: JSONObject.NULL)
            put("durationMs", snapshot.durationMs)
        }.toString()

    fun decode(json: String): LastPlayedSnapshot? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val uri = obj.optString("uriString", "")
            if (uri.isBlank()) return null
            LastPlayedSnapshot(
                songId = obj.optLong("songId", 0L),
                uriString = uri,
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                title = obj.optString("title", ""),
                artist = obj.optString("artist", ""),
                album = obj.optString("album", ""),
                artworkUri = obj.optNullableString("artworkUri"),
                durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L)
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Persistable playback queue. Locals by id/uri; remotes by identity + query/videoId.
 * Never writes CDN [ResolvedStream.audioUrl].
 */
object QueueSnapshotCodec {
    fun encode(snapshot: QueueSnapshot): String =
        JSONObject().apply {
            put("currentIndex", snapshot.currentIndex)
            put("positionMs", snapshot.positionMs)
            val arr = JSONArray()
            for (item in snapshot.items) {
                arr.put(encodeItem(item))
            }
            put("items", arr)
        }.toString()

    fun decode(json: String): QueueSnapshot? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("items") ?: return null
            val items = buildList {
                for (i in 0 until arr.length()) {
                    decodeItem(arr.getJSONObject(i))?.let { add(it) }
                }
            }
            if (items.isEmpty()) return null
            QueueSnapshot(
                currentIndex = obj.optInt("currentIndex", 0).coerceAtLeast(0),
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                items = items
            )
        } catch (_: Exception) {
            null
        }
    }

    fun fromPlayable(
        items: List<PlayableItem>,
        currentIndex: Int,
        positionMs: Long
    ): QueueSnapshot = QueueSnapshot(
        currentIndex = currentIndex.coerceAtLeast(0),
        positionMs = positionMs.coerceAtLeast(0L),
        items = items.map { toPersisted(it) }
    )

    private fun toPersisted(item: PlayableItem): PersistedQueueItem = when (item) {
        is PlayableItem.Local -> PersistedQueueItem.Local(
            songId = item.song.id,
            uriString = item.song.uriString,
            title = item.song.title,
            artist = item.song.artist,
            album = item.song.album,
            artworkUri = item.song.artworkUri,
            durationMs = item.song.durationMs,
            trackNumber = item.song.trackNumber
        )
        is PlayableItem.Remote -> PersistedQueueItem.Remote(
            identity = item.identity,
            recordingMbid = item.recordingMbid,
            youtubeQueryOrId = item.youtubeQueryOrId,
            videoId = item.resolved?.videoId?.takeIf { it.isNotBlank() }
        )
    }

    private fun encodeItem(item: PersistedQueueItem): JSONObject = when (item) {
        is PersistedQueueItem.Local -> JSONObject().apply {
            put("kind", "local")
            put("songId", item.songId)
            put("uriString", item.uriString)
            put("title", item.title)
            put("artist", item.artist)
            put("album", item.album)
            put("artworkUri", item.artworkUri ?: JSONObject.NULL)
            put("durationMs", item.durationMs)
            put("trackNumber", item.trackNumber)
        }
        is PersistedQueueItem.Remote -> JSONObject().apply {
            put("kind", "remote")
            put("title", item.identity.title)
            put("artist", item.identity.artist)
            put("album", item.identity.album)
            put("artworkUri", item.identity.artworkUri ?: JSONObject.NULL)
            put("durationMs", item.identity.durationMs)
            put("trackNumber", item.identity.trackNumber)
            put("recordingMbid", item.recordingMbid ?: JSONObject.NULL)
            put("youtubeQueryOrId", item.youtubeQueryOrId ?: JSONObject.NULL)
            put("videoId", item.videoId ?: JSONObject.NULL)
        }
    }

    private fun decodeItem(obj: JSONObject): PersistedQueueItem? {
        return try {
            when (obj.optString("kind", "")) {
                "local" -> {
                    val uri = obj.optString("uriString", "")
                    if (uri.isBlank()) return null
                    PersistedQueueItem.Local(
                        songId = obj.optLong("songId", 0L),
                        uriString = uri,
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        artworkUri = obj.optNullableString("artworkUri"),
                        durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
                        trackNumber = obj.optInt("trackNumber", 0).coerceAtLeast(0)
                    )
                }
                "remote" -> {
                    val title = obj.optString("title", "")
                    val artist = obj.optString("artist", "")
                    val query = obj.optNullableString("youtubeQueryOrId")
                    val videoId = obj.optNullableString("videoId")
                    if (title.isBlank() && artist.isBlank() && query.isNullOrBlank() && videoId.isNullOrBlank()) {
                        return null
                    }
                    PersistedQueueItem.Remote(
                        identity = TrackIdentity(
                            title = title,
                            artist = artist,
                            album = obj.optString("album", ""),
                            artworkUri = obj.optNullableString("artworkUri"),
                            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
                            trackNumber = obj.optInt("trackNumber", 0).coerceAtLeast(0)
                        ),
                        recordingMbid = obj.optNullableString("recordingMbid"),
                        youtubeQueryOrId = query,
                        videoId = videoId
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Pure helpers for idle mini-player seeding (unit-tested).
 */
object PlaybackHydration {
    /**
     * Prefer [lastPlayed] matched in [library]; otherwise a random library song.
     * Returns null when the library is empty.
     */
    fun resolveIdleSeed(
        library: List<Song>,
        lastPlayed: LastPlayedSnapshot?,
        random: (List<Song>) -> Song = { it.random() }
    ): Song? {
        if (library.isEmpty()) return null
        val matched = lastPlayed?.let { snap ->
            library.find { snap.songId > 0L && it.id == snap.songId }
                ?: library.find { matchesLastPlayed(it, snap) }
        }
        return matched ?: random(library)
    }

    fun matchesLastPlayed(song: Song, lastPlayed: LastPlayedSnapshot): Boolean {
        if (lastPlayed.songId > 0L && song.id == lastPlayed.songId) return true
        if (song.uriString == lastPlayed.uriString) return true
        val songCanon = AudioPersistRef.canonicalize(song.uriString, song.folderPath).uriString
        val snapCanon = AudioPersistRef.canonicalize(lastPlayed.uriString).uriString
        if (songCanon.isNotBlank() && songCanon == snapCanon) return true
        return SongPathNormalizer.pathsReferToSameFile(song.uriString, lastPlayed.uriString)
    }

    /** Position to show / resume when [song] matches [lastPlayed]. */
    fun resumePositionMs(song: Song, lastPlayed: LastPlayedSnapshot?): Long {
        if (lastPlayed == null) return 0L
        if (!matchesLastPlayed(song, lastPlayed)) return 0L
        val cap = song.durationMs.takeIf { it > 0 } ?: lastPlayed.durationMs
        val pos = lastPlayed.positionMs.coerceAtLeast(0L)
        return if (cap > 0) pos.coerceAtMost(cap) else pos
    }

    fun snapshotFromSong(song: Song, positionMs: Long): LastPlayedSnapshot =
        LastPlayedSnapshot(
            songId = song.id,
            uriString = song.uriString,
            positionMs = positionMs.coerceAtLeast(0L),
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUri = song.artworkUri,
            durationMs = song.durationMs
        )

    fun matchPersistedLocal(item: PersistedQueueItem.Local, library: List<Song>): Song? {
        if (library.isEmpty()) return null
        val snap = LastPlayedSnapshot(
            songId = item.songId,
            uriString = item.uriString,
            title = item.title,
            artist = item.artist,
            album = item.album,
            artworkUri = item.artworkUri,
            durationMs = item.durationMs
        )
        return library.find { item.songId > 0L && it.id == item.songId }
            ?: library.find { matchesLastPlayed(it, snap) }
    }

    fun toPlayableRemote(item: PersistedQueueItem.Remote): PlayableItem.Remote {
        val videoId = item.videoId?.takeIf { it.isNotBlank() }
        val resolved = if (videoId != null) {
            ResolvedStream(
                audioUrl = "",
                userAgent = "",
                videoId = videoId,
                resolvedAtEpochMs = 0L
            )
        } else {
            null
        }
        return PlayableItem.remoteFrom(
            identity = item.identity,
            recordingMbid = item.recordingMbid,
            youtubeQueryOrId = item.youtubeQueryOrId ?: videoId,
            resolved = resolved
        )
    }

    /**
     * Rematch persisted queue against [library]. Drops deleted locals.
     * If the current item is gone, advances to the next surviving item (position 0).
     */
    fun hydrateQueue(snapshot: QueueSnapshot?, library: List<Song>): HydratedQueue? {
        if (snapshot == null || snapshot.items.isEmpty()) return null
        val resolved = ArrayList<Pair<Int, PlayableItem>>(snapshot.items.size)
        snapshot.items.forEachIndexed { origIdx, persisted ->
            when (persisted) {
                is PersistedQueueItem.Local -> matchPersistedLocal(persisted, library)
                    ?.let { resolved.add(origIdx to it.toPlayable()) }
                is PersistedQueueItem.Remote -> resolved.add(origIdx to toPlayableRemote(persisted))
            }
        }
        if (resolved.isEmpty()) return null
        val targetOrig = snapshot.currentIndex.coerceAtLeast(0)
        val currentPair = resolved.firstOrNull { it.first >= targetOrig } ?: resolved.first()
        val items = resolved.map { it.second }
        val newIndex = resolved.indexOfFirst { it.first == currentPair.first }.coerceAtLeast(0)
        val sameCurrent = currentPair.first == targetOrig
        val current = items[newIndex]
        val positionMs = if (sameCurrent) {
            val cap = current.durationMs.takeIf { it > 0 } ?: snapshot.positionMs
            val pos = snapshot.positionMs.coerceAtLeast(0L)
            if (cap > 0) pos.coerceAtMost(cap) else pos
        } else {
            0L
        }
        return HydratedQueue(items = items, currentIndex = newIndex, positionMs = positionMs)
    }
}

class PlaybackSessionStore(private val context: Context) {

    private object Keys {
        val LAST_PLAYED_JSON = stringPreferencesKey("last_played_json")
        val QUEUE_JSON = stringPreferencesKey("queue_json")
    }

    val lastPlayedFlow: Flow<LastPlayedSnapshot?> =
        context.playbackSessionDataStore.data.map { prefs ->
            LastPlayedCodec.decode(prefs[Keys.LAST_PLAYED_JSON].orEmpty())
        }

    suspend fun load(): LastPlayedSnapshot? = lastPlayedFlow.first()

    suspend fun save(snapshot: LastPlayedSnapshot) {
        context.playbackSessionDataStore.edit { prefs ->
            prefs[Keys.LAST_PLAYED_JSON] = LastPlayedCodec.encode(snapshot)
        }
    }

    suspend fun loadQueue(): QueueSnapshot? {
        val json = context.playbackSessionDataStore.data.first()[Keys.QUEUE_JSON].orEmpty()
        return QueueSnapshotCodec.decode(json)
    }

    suspend fun saveQueue(snapshot: QueueSnapshot) {
        saveSession(queue = snapshot)
    }

    suspend fun clearQueue() {
        saveSession(clearQueue = true)
    }

    suspend fun saveSession(
        lastPlayed: LastPlayedSnapshot? = null,
        queue: QueueSnapshot? = null,
        clearQueue: Boolean = false
    ) {
        context.playbackSessionDataStore.edit { prefs ->
            if (lastPlayed != null) {
                prefs[Keys.LAST_PLAYED_JSON] = LastPlayedCodec.encode(lastPlayed)
            }
            when {
                clearQueue -> prefs.remove(Keys.QUEUE_JSON)
                queue != null -> prefs[Keys.QUEUE_JSON] = QueueSnapshotCodec.encode(queue)
            }
        }
    }
}
