package com.bestiapop.android.data.util

import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.json.JSONObject

/** Shared JSON encode/decode for [OnlineCatalogTrack] snapshots (downloads + identify review). */
object CatalogTrackJson {

    fun encode(track: OnlineCatalogTrack, includeAudioUrl: Boolean = true): JSONObject =
        JSONObject().apply {
            put("id", track.id)
            TrackIdentityJson.putInto(this, track.identity)
            put("audioUrl", if (includeAudioUrl) track.audioUrl else "")
            put("provider", track.provider)
            if (track.year > 0) put("year", track.year)
        }

    fun decode(obj: JSONObject): OnlineCatalogTrack =
        OnlineCatalogTrack(
            identity = TrackIdentityJson.decode(obj),
            id = obj.optString("id", ""),
            audioUrl = obj.optString("audioUrl", ""),
            provider = obj.optString("provider", "YouTube"),
            year = obj.optInt("year", 0)
        )
}
