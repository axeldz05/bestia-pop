package com.bestiapop.android.data.util

import com.bestiapop.android.data.model.TrackIdentity
import org.json.JSONObject

/** Shared JSON encode/decode for [TrackIdentity] fields. */
object TrackIdentityJson {

    fun putInto(obj: JSONObject, identity: TrackIdentity) {
        obj.put("title", identity.title)
        obj.put("artist", identity.artist)
        obj.put("album", identity.album)
        obj.put("artworkUri", identity.artworkUri ?: JSONObject.NULL)
        obj.put("durationMs", identity.durationMs)
        obj.put("trackNumber", identity.trackNumber)
    }

    fun decode(obj: JSONObject): TrackIdentity = TrackIdentity(
        title = obj.optString("title", ""),
        artist = obj.optString("artist", ""),
        album = obj.optString("album", ""),
        artworkUri = obj.optNullableString("artworkUri")
            ?: obj.optNullableString("artworkUrl"),
        durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
        trackNumber = obj.optInt("trackNumber", 0).coerceAtLeast(0)
    )
}
