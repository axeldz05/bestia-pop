package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bestiapop.android.data.model.IdentifyApplyFields
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.identifyWorkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identify_work"
)

/** In-flight user-requested identify batch. Remaining IDs survive process death. */
data class IdentifyWorkSnapshot(
    val remainingSongIds: List<Long> = emptyList(),
    val force: Boolean = false,
    val showReview: Boolean = true,
    val applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val medium: Int = 0,
    val low: Int = 0,
    val none: Int = 0,
    val lbHits: Int = 0,
    val alreadyQueued: Int = 0,
    val reviewCount: Int = 0,
    val interrupted: Boolean = false
) {
    val hasRemaining: Boolean get() = remainingSongIds.isNotEmpty()
}

object IdentifyWorkCodec {
    fun encode(snapshot: IdentifyWorkSnapshot): String {
        val ids = JSONArray()
        for (id in snapshot.remainingSongIds) ids.put(id)
        return JSONObject().apply {
            put("remainingSongIds", ids)
            put("force", snapshot.force)
            put("showReview", snapshot.showReview)
            put("processedCount", snapshot.processedCount)
            put("totalCount", snapshot.totalCount)
            put("updated", snapshot.updated)
            put("skipped", snapshot.skipped)
            put("medium", snapshot.medium)
            put("low", snapshot.low)
            put("none", snapshot.none)
            put("lbHits", snapshot.lbHits)
            put("alreadyQueued", snapshot.alreadyQueued)
            put("reviewCount", snapshot.reviewCount)
            put("interrupted", snapshot.interrupted)
            put(
                "applyFields",
                JSONObject().apply {
                    put("artwork", snapshot.applyFields.artwork)
                    put("title", snapshot.applyFields.title)
                    put("artist", snapshot.applyFields.artist)
                    put("album", snapshot.applyFields.album)
                    put("year", snapshot.applyFields.year)
                    put("trackNumber", snapshot.applyFields.trackNumber)
                }
            )
        }.toString()
    }

    fun decode(json: String): IdentifyWorkSnapshot? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val idsArr = obj.optJSONArray("remainingSongIds") ?: JSONArray()
            val ids = buildList {
                for (i in 0 until idsArr.length()) {
                    val id = idsArr.optLong(i, Long.MIN_VALUE)
                    if (id != Long.MIN_VALUE) add(id)
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
            IdentifyWorkSnapshot(
                remainingSongIds = ids,
                force = obj.optBoolean("force", false),
                showReview = obj.optBoolean("showReview", true),
                applyFields = applyFields,
                processedCount = obj.optInt("processedCount", 0),
                totalCount = obj.optInt("totalCount", ids.size),
                updated = obj.optInt("updated", 0),
                skipped = obj.optInt("skipped", 0),
                medium = obj.optInt("medium", 0),
                low = obj.optInt("low", 0),
                none = obj.optInt("none", 0),
                lbHits = obj.optInt("lbHits", 0),
                alreadyQueued = obj.optInt("alreadyQueued", 0),
                reviewCount = obj.optInt("reviewCount", 0),
                interrupted = obj.optBoolean("interrupted", false)
            )
        } catch (_: Exception) {
            null
        }
    }
}

class IdentifyWorkStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.identifyWorkDataStore)

    private object Keys {
        val WORK_JSON = stringPreferencesKey("work_json")
    }

    val snapshotFlow: Flow<IdentifyWorkSnapshot?> =
        dataStore.data.map { prefs ->
            IdentifyWorkCodec.decode(prefs[Keys.WORK_JSON].orEmpty())
        }

    suspend fun load(): IdentifyWorkSnapshot? = snapshotFlow.first()

    suspend fun save(snapshot: IdentifyWorkSnapshot?) {
        val json = if (snapshot == null) "" else IdentifyWorkCodec.encode(snapshot)
        dataStore.edit { prefs ->
            prefs[Keys.WORK_JSON] = json
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs[Keys.WORK_JSON] = ""
        }
    }
}
