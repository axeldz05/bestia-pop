package com.bestiapop.android.data.util

import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.json.JSONObject

/** Shared JSON encode/decode for [OnlineCatalogTrack] snapshots (downloads + identify review). */
object CatalogTrackJson {

    fun encode(track: OnlineCatalogTrack, includeAudioUrl: Boolean = true): JSONObject =
        JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("artworkUrl", track.artworkUri ?: JSONObject.NULL)
            put("durationMs", track.durationMs)
            put("audioUrl", if (includeAudioUrl) track.audioUrl else "")
            put("provider", track.provider)
            put("trackNumber", track.trackNumber)
        }

    fun decode(obj: JSONObject): OnlineCatalogTrack =
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
