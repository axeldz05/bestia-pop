package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.util.CatalogTrackJson
import com.bestiapop.android.data.util.optNullableString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.identifyReviewDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identify_review"
)

data class PersistedIdentifyReviewQueue(
    val proposals: List<IdentifyProposal> = emptyList(),
    val phase: String = "Item",
    val applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL
)

/**
 * Pure JSON codec for the identify-review queue. Candidates persist without CDN [audioUrl].
 */
object IdentifyReviewCodec {

    fun encode(queue: PersistedIdentifyReviewQueue): String {
        val items = JSONArray()
        for (proposal in queue.proposals) {
            items.put(encodeProposal(proposal))
        }
        val fieldsObj = JSONObject().apply {
            put("artwork", queue.applyFields.artwork)
            put("title", queue.applyFields.title)
            put("artist", queue.applyFields.artist)
            put("album", queue.applyFields.album)
            put("year", queue.applyFields.year)
            put("trackNumber", queue.applyFields.trackNumber)
        }
        return JSONObject().apply {
            put("phase", queue.phase)
            put("items", items)
            put("applyFields", fieldsObj)
        }.toString()
    }

    fun decode(json: String): PersistedIdentifyReviewQueue {
        if (json.isBlank()) return PersistedIdentifyReviewQueue()
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("items") ?: return PersistedIdentifyReviewQueue()
            val proposals = buildList {
                for (i in 0 until arr.length()) {
                    decodeProposal(arr.getJSONObject(i))?.let { add(it) }
                }
            }
            val fieldsObj = obj.optJSONObject("applyFields")
            val applyFields = if (fieldsObj != null) {
                IdentifyApplyFields(
                    artwork = fieldsObj.optBoolean("artwork", true),
                    title = fieldsObj.optBoolean("title", true),
                    artist = fieldsObj.optBoolean("artist", true),
                    album = fieldsObj.optBoolean("album", true),
                    year = fieldsObj.optBoolean("year", true),
                    trackNumber = fieldsObj.optBoolean("trackNumber", true)
                )
            } else {
                IdentifyApplyFields.ALL
            }
            PersistedIdentifyReviewQueue(
                proposals = proposals,
                phase = obj.optString("phase", "Item").ifBlank { "Item" },
                applyFields = applyFields
            )
        } catch (_: Exception) {
            PersistedIdentifyReviewQueue()
        }
    }

    private fun encodeProposal(proposal: IdentifyProposal): JSONObject =
        JSONObject().apply {
            put("songId", proposal.songId)
            put("queryArtist", proposal.queryArtist)
            put("queryTitle", proposal.queryTitle)
            put("sourceHints", proposal.sourceHints ?: JSONObject.NULL)
            put("confidence", proposal.confidence.name)
            put("usedListenBrainz", proposal.usedListenBrainz)
            val candidates = JSONArray()
            for (candidate in proposal.candidates) {
                candidates.put(encodeCandidate(candidate))
            }
            put("candidates", candidates)
        }

    private fun encodeCandidate(candidate: IdentifyCandidate): JSONObject =
        JSONObject().apply {
            put("score", candidate.score.toDouble())
            val reasons = JSONArray()
            for (reason in candidate.reasons) {
                reasons.put(reason)
            }
            put("reasons", reasons)
            put("track", CatalogTrackJson.encode(candidate.track, includeAudioUrl = false))
        }

    private fun decodeProposal(obj: JSONObject): IdentifyProposal? {
        return try {
            val songId = obj.getLong("songId")
            val candidatesArr = obj.optJSONArray("candidates") ?: JSONArray()
            val candidates = buildList {
                for (i in 0 until candidatesArr.length()) {
                    decodeCandidate(candidatesArr.getJSONObject(i))?.let { add(it) }
                }
            }
            val confidence = runCatching {
                IdentifyConfidence.valueOf(obj.optString("confidence", "NONE"))
            }.getOrDefault(IdentifyConfidence.NONE)
            IdentifyProposal(
                songId = songId,
                queryArtist = obj.optString("queryArtist", ""),
                queryTitle = obj.optString("queryTitle", ""),
                sourceHints = obj.optNullableString("sourceHints"),
                candidates = candidates,
                confidence = confidence,
                suggested = candidates.firstOrNull(),
                usedListenBrainz = obj.optBoolean("usedListenBrainz", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCandidate(obj: JSONObject): IdentifyCandidate? {
        return try {
            val trackObj = obj.optJSONObject("track") ?: return null
            val track = CatalogTrackJson.decode(trackObj).let { decoded ->
                if (decoded.audioUrl.isEmpty()) decoded
                else decoded.copy(audioUrl = "")
            }
            val reasonsArr = obj.optJSONArray("reasons") ?: JSONArray()
            val reasons = buildList {
                for (i in 0 until reasonsArr.length()) {
                    add(reasonsArr.optString(i))
                }
            }
            IdentifyCandidate(
                track = track,
                score = obj.optDouble("score", 0.0).toFloat(),
                reasons = reasons
            )
        } catch (_: Exception) {
            null
        }
    }
}

class IdentifyReviewStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.identifyReviewDataStore)

    private object Keys {
        val QUEUE_JSON = stringPreferencesKey("queue_json")
    }

    val queueFlow: Flow<PersistedIdentifyReviewQueue> =
        dataStore.data.map { prefs ->
            IdentifyReviewCodec.decode(prefs[Keys.QUEUE_JSON].orEmpty())
        }

    suspend fun load(): PersistedIdentifyReviewQueue = queueFlow.first()

    suspend fun save(queue: PersistedIdentifyReviewQueue) {
        val json = if (queue.proposals.isEmpty()) "" else IdentifyReviewCodec.encode(queue)
        dataStore.edit { prefs ->
            prefs[Keys.QUEUE_JSON] = json
        }
    }
}
