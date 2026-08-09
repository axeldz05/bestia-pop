package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.optNullableString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
}

class PlaybackSessionStore(private val context: Context) {

    private object Keys {
        val LAST_PLAYED_JSON = stringPreferencesKey("last_played_json")
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
}
