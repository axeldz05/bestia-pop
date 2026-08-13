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
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.DownloadPlaylistDestination
import com.bestiapop.android.data.model.isFailed
import com.bestiapop.android.data.model.resolveDownloadPlaylistDestinations
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.util.CatalogTrackJson
import com.bestiapop.android.data.util.TrackIdentityJson
import com.bestiapop.android.data.util.optNullableString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.activeDownloadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_downloads"
)

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
     * [DownloadMessages.interrupted] (QUEUED never started; DOWNLOADING was interrupted).
     */
    fun forPersistence(list: List<ActiveDownload>): List<ActiveDownload> =
        list.map { download ->
            when (download.state) {
                CandidateDownloadState.DOWNLOADING,
                CandidateDownloadState.QUEUED ->
                    download.asError(DownloadMessages.interrupted, interrupted = true)
                CandidateDownloadState.SUCCESS -> download.copy(
                    progressMessage = null,
                    progressPercent = 100,
                    interrupted = false
                )
                CandidateDownloadState.ERROR -> download.asError(
                    message = download.errorMessage,
                    interrupted = download.interrupted ||
                        isInterruptedMessage(download.errorMessage)
                )
                // IDLE means an unresolved conflict: keep a status line, or the row came back blank.
                CandidateDownloadState.IDLE -> download.copy(
                    progressMessage = download.progressMessage ?: DownloadMessages.conflictPending,
                    progressPercent = 0,
                    interrupted = false
                )
            }
        }

    private fun encodeOne(download: ActiveDownload): JSONObject =
        JSONObject().apply {
            put("id", download.id)
            put("source", download.source.name)
            put("currentCandidateIndex", download.currentCandidateIndex)
            put("state", download.state.name)
            put("errorMessage", download.errorMessage ?: JSONObject.NULL)
            put("interrupted", download.interrupted)
            put("downloadStarted", download.downloadStarted)
            put("storageCommitted", download.storageCommitted)
            put("overwriteTargetSongId", download.overwriteTargetSongId ?: JSONObject.NULL)
            put("batchId", download.batchId ?: JSONObject.NULL)
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
            val override = download.titleOverride?.takeIf { it.isNotBlank() }
            if (override != null) {
                put("displayTitle", override)
            }
            download.lookupIdentity?.let { identity ->
                put(
                    "lookupIdentity",
                    JSONObject().also { TrackIdentityJson.putInto(it, identity) }
                )
            }
            put(
                "playlistTargets",
                JSONArray().apply {
                    download.playlistTargets.forEach { target ->
                        put(
                            JSONObject().apply {
                                put("playlistId", target.playlistId)
                                put(
                                    "identity",
                                    JSONObject().also {
                                        TrackIdentityJson.putInto(it, target.identity)
                                    }
                                )
                            }
                        )
                    }
                }
            )
            val candidates = JSONArray()
            for (track in download.candidates) {
                candidates.put(CatalogTrackJson.encode(track))
            }
            put("candidates", candidates)
        }

    private fun decodeOne(obj: JSONObject): ActiveDownload? {
        return try {
            val candidatesArr = obj.getJSONArray("candidates")
            val candidates = buildList {
                for (i in 0 until candidatesArr.length()) {
                    add(CatalogTrackJson.decode(candidatesArr.getJSONObject(i)))
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
            val index = obj.optInt("currentCandidateIndex", 0)
                .coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
            val fallbackTitle = obj.optString("displayTitle", "")
            val fallbackArtist = obj.optString("displayArtist", "")
            val fallbackArt = obj.optNullableString("artworkUrl")
            val patched = candidates.mapIndexed { i, track ->
                if (i != index) track
                else track.withIdentity {
                    copy(
                        title = title.ifBlank { fallbackTitle },
                        artist = artist.ifBlank { fallbackArtist },
                        artworkUri = artworkUri?.takeIf { it.isNotBlank() } ?: fallbackArt
                    )
                }
            }
            val currentTitle = patched.getOrNull(index)?.title.orEmpty()
            val titleOverride = fallbackTitle.takeIf { it.isNotBlank() && it != currentTitle }
            val errorMessage = obj.optNullableString("errorMessage")
            val lookupIdentity = obj.optJSONObject("lookupIdentity")
                ?.let(TrackIdentityJson::decode)
            val decodedPlaylistTargets = obj.optJSONArray("playlistTargets")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        val target = array.optJSONObject(i) ?: continue
                        val playlistId = target.optLong("playlistId").takeIf { it > 0L }
                            ?: continue
                        val identity = target.optJSONObject("identity")
                            ?.let(TrackIdentityJson::decode)
                            ?: continue
                        add(DownloadPlaylistDestination(playlistId, identity))
                    }
                }
            }.orEmpty()
            val fallbackIdentity = lookupIdentity ?: patched.getOrNull(index)?.identity
            val playlistTargets = if (fallbackIdentity == null) {
                decodedPlaylistTargets
            } else {
                resolveDownloadPlaylistDestinations(
                    decodedPlaylistTargets,
                    targetPlaylistId,
                    fallbackIdentity
                )
            }
            ActiveDownload(
                id = obj.getString("id"),
                source = source,
                candidates = patched,
                currentCandidateIndex = index,
                state = state,
                progressMessage = null,
                progressPercent = if (state == CandidateDownloadState.SUCCESS) 100 else 0,
                errorMessage = errorMessage,
                targetPlaylistId = targetPlaylistId,
                playlistTargets = playlistTargets,
                resultSongId = resultSongId,
                lookupIdentity = lookupIdentity,
                interrupted = obj.optBoolean(
                    "interrupted",
                    isInterruptedMessage(errorMessage)
                ),
                downloadStarted = obj.optBoolean("downloadStarted", false),
                storageCommitted = obj.optBoolean("storageCommitted", false),
                overwriteTargetSongId = obj.optLong("overwriteTargetSongId")
                    .takeIf { it > 0L },
                batchId = obj.optNullableString("batchId"),
                titleOverride = titleOverride
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isInterruptedMessage(message: String?): Boolean =
        message?.startsWith("Interrumpida") == true
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
        it.state == CandidateDownloadState.DOWNLOADING || it.state.isFailed
    }
