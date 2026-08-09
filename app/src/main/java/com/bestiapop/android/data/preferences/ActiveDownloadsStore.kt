package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.util.optNullableString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.activeDownloadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_downloads"
)

const val INTERRUPTED_DOWNLOAD_MESSAGE = "Interrumpida — tocá Reintentar"

/**
 * Pure JSON codec for [ActiveDownload] snapshots. Used by [ActiveDownloadsStore] and unit tests.
 */
object ActiveDownloadCodec {

    fun encode(list: List<ActiveDownload>): String {
        val arr = JSONArray()
        for (item in forPersistence(list)) {
            arr.put(encodeOne(item))
        }
        return arr.toString()
    }

    fun decode(json: String): List<ActiveDownload> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    decodeOne(arr.getJSONObject(i))?.let { add(it) }
                }
            }.let { forPersistence(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Persist IDLE/ERROR/SUCCESS/QUEUED; any DOWNLOADING or QUEUED becomes ERROR with
     * [INTERRUPTED_DOWNLOAD_MESSAGE] (QUEUED never started; DOWNLOADING was interrupted).
     */
    fun forPersistence(list: List<ActiveDownload>): List<ActiveDownload> =
        list.map { download ->
            when (download.state) {
                CandidateDownloadState.DOWNLOADING,
                CandidateDownloadState.QUEUED -> download.copy(
                    state = CandidateDownloadState.ERROR,
                    progressMessage = null,
                    progressPercent = 0,
                    errorMessage = INTERRUPTED_DOWNLOAD_MESSAGE
                )
                CandidateDownloadState.SUCCESS -> download.copy(
                    progressMessage = null,
                    progressPercent = 100
                )
                CandidateDownloadState.ERROR,
                CandidateDownloadState.IDLE -> download.copy(
                    progressMessage = null,
                    progressPercent = 0
                )
            }
        }

    private fun encodeOne(download: ActiveDownload): JSONObject =
        JSONObject().apply {
            put("id", download.id)
            put("source", download.source.name)
            put("displayTitle", download.displayTitle)
            put("displayArtist", download.displayArtist)
            put("artworkUrl", download.artworkUrl ?: JSONObject.NULL)
            put("currentCandidateIndex", download.currentCandidateIndex)
            put("state", download.state.name)
            put("errorMessage", download.errorMessage ?: JSONObject.NULL)
            if (download.targetPlaylistId != null) {
                put("targetPlaylistId", download.targetPlaylistId)
            } else {
                put("targetPlaylistId", JSONObject.NULL)
            }
            if (download.resultSongId != null) {
                put("resultSongId", download.resultSongId)
            } else {
                put("resultSongId", JSONObject.NULL)
            }
            val candidates = JSONArray()
            for (track in download.candidates) {
                candidates.put(encodeTrack(track))
            }
            put("candidates", candidates)
        }

    private fun encodeTrack(track: OnlineCatalogTrack): JSONObject =
        JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("artworkUrl", track.artworkUri ?: JSONObject.NULL)
            put("durationMs", track.durationMs)
            put("audioUrl", track.audioUrl)
            put("provider", track.provider)
            put("trackNumber", track.trackNumber)
        }

    private fun decodeOne(obj: JSONObject): ActiveDownload? {
        return try {
            val candidatesArr = obj.getJSONArray("candidates")
            val candidates = buildList {
                for (i in 0 until candidatesArr.length()) {
                    add(decodeTrack(candidatesArr.getJSONObject(i)))
                }
            }
            if (candidates.isEmpty()) return null
            val source = runCatching {
                ActiveDownloadSource.valueOf(obj.getString("source"))
            }.getOrDefault(ActiveDownloadSource.CATALOG)
            val state = runCatching {
                CandidateDownloadState.valueOf(obj.getString("state"))
            }.getOrDefault(CandidateDownloadState.ERROR)
            val targetPlaylistId = if (obj.has("targetPlaylistId") && !obj.isNull("targetPlaylistId")) {
                obj.optLong("targetPlaylistId").takeIf { it > 0L }
            } else {
                null
            }
            val resultSongId = if (obj.has("resultSongId") && !obj.isNull("resultSongId")) {
                obj.optLong("resultSongId").takeIf { it > 0L }
            } else {
                null
            }
            ActiveDownload(
                id = obj.getString("id"),
                source = source,
                displayTitle = obj.optString("displayTitle", ""),
                displayArtist = obj.optString("displayArtist", ""),
                artworkUrl = obj.optNullableString("artworkUrl"),
                candidates = candidates,
                currentCandidateIndex = obj.optInt("currentCandidateIndex", 0)
                    .coerceIn(0, (candidates.size - 1).coerceAtLeast(0)),
                state = state,
                progressMessage = null,
                progressPercent = if (state == CandidateDownloadState.SUCCESS) 100 else 0,
                errorMessage = obj.optNullableString("errorMessage"),
                targetPlaylistId = targetPlaylistId,
                resultSongId = resultSongId
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeTrack(obj: JSONObject): OnlineCatalogTrack =
        OnlineCatalogTrack(
            id = obj.optString("id", ""),
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            album = obj.optString("album", ""),
            artworkUri = obj.optNullableString("artworkUrl"),
            durationMs = obj.optLong("durationMs", 0L),
            audioUrl = obj.optString("audioUrl", ""),
            provider = obj.optString("provider", "YouTube"),
            trackNumber = obj.optInt("trackNumber", 0)
        )
}


class ActiveDownloadsStore(private val context: Context) {

    private object Keys {
        val QUEUE_JSON = stringPreferencesKey("queue_json")
    }

    val queueFlow: Flow<List<ActiveDownload>> = context.activeDownloadsDataStore.data.map { prefs ->
        ActiveDownloadCodec.decode(prefs[Keys.QUEUE_JSON].orEmpty())
    }

    suspend fun load(): List<ActiveDownload> = queueFlow.first()

    suspend fun save(list: List<ActiveDownload>) {
        val json = ActiveDownloadCodec.encode(list)
        context.activeDownloadsDataStore.edit { prefs ->
            prefs[Keys.QUEUE_JSON] = json
        }
    }
}

/** Count of downloads that should show on the Descargas nav badge. */
fun activeDownloadBadgeCount(downloads: List<ActiveDownload>): Int =
    downloads.count {
        it.state == CandidateDownloadState.DOWNLOADING || it.state == CandidateDownloadState.ERROR
    }
