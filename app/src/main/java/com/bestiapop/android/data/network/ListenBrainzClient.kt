package com.bestiapop.android.data.network

import com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload
import com.bestiapop.android.data.listenbrainz.CfRecommendedRecording
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbMetadataLookup
import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.LbPlaylistTrack
import com.bestiapop.android.data.listenbrainz.LbRadioRecording
import com.bestiapop.android.data.listenbrainz.LbRecordingMetadata
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ListenPayload(
    val listenedAt: Long,
    val trackName: String,
    val artistName: String,
    val releaseName: String? = null,
    val durationMs: Long? = null
) {
    companion object {
        fun fromIdentity(identity: TrackIdentity, listenedAt: Long): ListenPayload = ListenPayload(
            listenedAt = listenedAt,
            trackName = identity.title,
            artistName = identity.artist,
            releaseName = identity.album.takeIf {
                it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true)
            },
            durationMs = identity.durationMs.takeIf { it > 0 }
        )
    }
}

data class TokenValidationResult(
    val valid: Boolean,
    val username: String? = null,
    val message: String? = null
)

sealed class SubmitListensResult {
    data class Success(
        val rateLimitRemaining: Int?,
        val rateLimitResetInSec: Int?
    ) : SubmitListensResult()

    data class RateLimited(val resetInSec: Int) : SubmitListensResult()

    data class Failure(
        val message: String,
        val isNetworkError: Boolean = false,
        val rateLimitRemaining: Int? = null,
        val rateLimitResetInSec: Int? = null
    ) : SubmitListensResult()
}

object ListenBrainzClient {

    private const val BASE_URL = "https://api.listenbrainz.org/1"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = HttpClients.api.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun validateToken(token: String): TokenValidationResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext TokenValidationResult(valid = false, message = "Token vacío")
        }
        try {
            val request = Request.Builder()
                .url("$BASE_URL/validate-token")
                .header("Authorization", "Token ${token.trim()}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext TokenValidationResult(
                        valid = false,
                        message = "Error ${response.code}"
                    )
                }
                val json = JSONObject(body)
                val valid = json.optBoolean("valid", false)
                val username = if (json.has("user_name") && !json.isNull("user_name")) {
                    json.getString("user_name").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                TokenValidationResult(
                    valid = valid,
                    username = username,
                    message = if (valid) null else json.optString("message", "Token inválido")
                )
            }
        } catch (e: Exception) {
            TokenValidationResult(valid = false, message = e.message ?: "Error de red")
        }
    }

    suspend fun submitListens(
        token: String,
        listens: List<ListenPayload>
    ): SubmitListensResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext SubmitListensResult.Failure("Token vacío")
        }
        if (listens.isEmpty()) {
            return@withContext SubmitListensResult.Success(null, null)
        }

        try {
            val listenType = if (listens.size == 1) "single" else "import"
            val payload = JSONObject().apply {
                put("listen_type", listenType)
                put("payload", JSONArray().apply {
                    listens.forEach { listen ->
                        put(JSONObject().apply {
                            put("listened_at", listen.listenedAt)
                            put("track_metadata", JSONObject().apply {
                                put("track_name", listen.trackName)
                                put("artist_name", listen.artistName)
                                if (!listen.releaseName.isNullOrBlank()) {
                                    put("release_name", listen.releaseName)
                                }
                                put("additional_info", JSONObject().apply {
                                    put("media_player", "Bestia Pop")
                                    put("submission_client", "Bestia Pop")
                                    listen.durationMs?.takeIf { it > 0 }?.let {
                                        put("duration_ms", it)
                                    }
                                })
                            })
                        })
                    }
                })
            }

            val request = Request.Builder()
                .url("$BASE_URL/submit-listens")
                .header("Authorization", "Token ${token.trim()}")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                val resetIn = response.header("X-RateLimit-Reset-In")?.toIntOrNull()
                val body = response.body?.string().orEmpty()

                when {
                    response.code == 429 -> {
                        SubmitListensResult.RateLimited(resetInSec = (resetIn ?: 30).coerceAtLeast(1))
                    }
                    response.isSuccessful -> {
                        SubmitListensResult.Success(
                            rateLimitRemaining = remaining,
                            rateLimitResetInSec = resetIn
                        )
                    }
                    else -> {
                        val message = runCatching {
                            JSONObject(body).optString("error", body)
                        }.getOrDefault(body).ifBlank { "Error ${response.code}" }
                        SubmitListensResult.Failure(
                            message = message,
                            rateLimitRemaining = remaining,
                            rateLimitResetInSec = resetIn
                        )
                    }
                }
            }
        } catch (e: Exception) {
            SubmitListensResult.Failure(
                message = e.message ?: "Error de red",
                isNetworkError = true
            )
        }
    }

    suspend fun fetchCreatedForPlaylists(
        username: String,
        token: String? = null,
        count: Int = 25,
        offset: Int = 0
    ): LbApiResult<List<LbPlaylistSummary>> = withContext(Dispatchers.IO) {
        if (username.isBlank()) {
            return@withContext LbApiResult.Failure("Usuario vacío")
        }
        val encodedUser = URLEncoder.encode(username.trim(), Charsets.UTF_8.name())
        val url = "$BASE_URL/user/$encodedUser/playlists/createdfor?count=$count&offset=$offset"
        lbGet(url, token) { body -> parsePlaylistSummaries(JSONObject(body)) }
    }

    suspend fun fetchPlaylist(
        playlistMbid: String,
        token: String? = null
    ): LbApiResult<LbPlaylistDetail> = withContext(Dispatchers.IO) {
        if (playlistMbid.isBlank()) {
            return@withContext LbApiResult.Failure("Playlist inválida")
        }
        val encodedMbid = URLEncoder.encode(playlistMbid.trim(), Charsets.UTF_8.name())
        val url = "$BASE_URL/playlist/$encodedMbid"
        lbCall(buildGetRequest(url, token)) { code, body ->
            if (code !in 200..299) {
                LbApiResult.Failure(message = errorMessageFromBody(body, code))
            } else {
                val detail = parsePlaylistDetail(JSONObject(body), playlistMbid.trim())
                    ?: return@lbCall LbApiResult.Failure("Respuesta de playlist inválida")
                LbApiResult.Success(detail)
            }
        }
    }

    suspend fun lookupRecordingMetadata(
        artistName: String,
        recordingName: String,
        token: String,
        releaseName: String? = null
    ): LbApiResult<LbMetadataLookup> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext LbApiResult.Failure("Token vacío")
        }
        if (artistName.isBlank() || recordingName.isBlank()) {
            return@withContext LbApiResult.Failure("Artista o título vacío")
        }
        val utf8 = Charsets.UTF_8.name()
        val params = buildString {
            append("artist_name=").append(URLEncoder.encode(artistName.trim(), utf8))
            append("&recording_name=").append(URLEncoder.encode(recordingName.trim(), utf8))
            if (!releaseName.isNullOrBlank()) {
                append("&release_name=").append(URLEncoder.encode(releaseName.trim(), utf8))
            }
        }
        val url = "$BASE_URL/metadata/lookup/?$params"
        lbGet(url, token) { body -> parseMetadataLookup(JSONObject(body)) }
    }

    suspend fun fetchLbRadioArtist(
        artistMbid: String,
        token: String,
        mode: String = "medium",
        maxSimilarArtists: Int = 8,
        maxRecordingsPerArtist: Int = 4,
        popBegin: Int = 20,
        popEnd: Int = 100
    ): LbApiResult<List<LbRadioRecording>> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext LbApiResult.Failure("Token vacío")
        }
        if (artistMbid.isBlank()) {
            return@withContext LbApiResult.Failure("Artist MBID vacío")
        }
        val utf8 = Charsets.UTF_8.name()
        val encodedMbid = URLEncoder.encode(artistMbid.trim(), utf8)
        val encodedMode = URLEncoder.encode(mode.trim().ifBlank { "medium" }, utf8)
        val url = "$BASE_URL/lb-radio/artist/$encodedMbid" +
            "?mode=$encodedMode" +
            "&max_similar_artists=$maxSimilarArtists" +
            "&max_recordings_per_artist=$maxRecordingsPerArtist" +
            "&pop_begin=$popBegin" +
            "&pop_end=$popEnd"
        lbGet(url, token) { body -> parseLbRadioArtist(JSONObject(body)) }
    }

    suspend fun fetchCfRecordingRecommendations(
        username: String,
        token: String? = null,
        count: Int = 50,
        offset: Int = 0,
        artistType: String = "top"
    ): LbApiResult<CfRecommendationsPayload> = withContext(Dispatchers.IO) {
        if (username.isBlank()) {
            return@withContext LbApiResult.Failure("Usuario vacío")
        }
        val utf8 = Charsets.UTF_8.name()
        val encodedUser = URLEncoder.encode(username.trim(), utf8)
        val encodedType = URLEncoder.encode(artistType.trim().ifBlank { "top" }, utf8)
        val url = "$BASE_URL/cf/recommendation/user/$encodedUser/recording" +
            "?count=$count&offset=$offset&artist_type=$encodedType"
        val empty = CfRecommendationsPayload(userName = username.trim(), recordings = emptyList())
        lbCall(buildGetRequest(url, token)) { code, body ->
            when {
                code == 204 || body.isBlank() -> LbApiResult.Success(empty)
                code !in 200..299 -> LbApiResult.Failure(message = errorMessageFromBody(body, code))
                else -> LbApiResult.Success(parseCfRecommendations(JSONObject(body), username.trim()))
            }
        }
    }

    suspend fun fetchRecordingMetadata(
        recordingMbids: List<String>,
        token: String? = null,
        inc: String = "artist release"
    ): LbApiResult<Map<String, LbRecordingMetadata>> = withContext(Dispatchers.IO) {
        val mbids = recordingMbids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (mbids.isEmpty()) {
            return@withContext LbApiResult.Success(emptyMap())
        }
        // POST avoids URL length limits for larger batches
        val payload = JSONObject().apply {
            put("recording_mbids", JSONArray(mbids))
            put("inc", inc)
        }
        val builder = Request.Builder()
            .url("$BASE_URL/metadata/recording/")
            .post(payload.toString().toRequestBody(JSON))
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Token ${token.trim()}")
        }
        lbCall(builder.build()) { code, body ->
            if (code !in 200..299) {
                LbApiResult.Failure(message = errorMessageFromBody(body, code))
            } else {
                LbApiResult.Success(parseRecordingMetadataMap(JSONObject(body)))
            }
        }
    }


    private inline fun <T> lbCall(
        request: Request,
        parse: (code: Int, body: String) -> LbApiResult<T>
    ): LbApiResult<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                parse(response.code, body)
            }
        } catch (e: Exception) {
            LbApiResult.Failure(
                message = e.message ?: "Error de red",
                isNetworkError = true
            )
        }
    }

    private inline fun <T> lbGet(
        url: String,
        token: String?,
        parse: (body: String) -> T
    ): LbApiResult<T> = lbCall(buildGetRequest(url, token)) { code, body ->
        if (code !in 200..299) {
            LbApiResult.Failure(message = errorMessageFromBody(body, code))
        } else {
            LbApiResult.Success(parse(body))
        }
    }

    private fun buildGetRequest(url: String, token: String?): Request {
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Token ${token.trim()}")
        }
        return builder.build()
    }

    private fun errorMessageFromBody(body: String, code: Int): String {
        return runCatching {
            JSONObject(body).optString("error", body)
        }.getOrDefault(body).ifBlank { "Error $code" }
    }

    private fun parsePlaylistSummaries(root: JSONObject): List<LbPlaylistSummary> {
        val playlistsArray = root.optJSONArray("playlists") ?: JSONArray()
        val result = ArrayList<LbPlaylistSummary>(playlistsArray.length())
        for (i in 0 until playlistsArray.length()) {
            val wrapper = playlistsArray.optJSONObject(i) ?: continue
            val playlist = wrapper.optJSONObject("playlist") ?: continue
            parsePlaylistSummary(playlist)?.let { result.add(it) }
        }
        return result
    }

    private fun parsePlaylistDetail(root: JSONObject, fallbackMbid: String): LbPlaylistDetail? {
        val playlist = root.optJSONObject("playlist") ?: return null
        val summary = parsePlaylistSummary(playlist, fallbackMbid) ?: return null
        val tracksArray = playlist.optJSONArray("track") ?: JSONArray()
        val tracks = ArrayList<LbPlaylistTrack>(tracksArray.length())
        for (i in 0 until tracksArray.length()) {
            val trackObj = tracksArray.optJSONObject(i) ?: continue
            val title = trackObj.optString("title").orEmpty().trim()
            val artist = trackObj.optString("creator").orEmpty().trim()
            if (title.isBlank() && artist.isBlank()) continue
            val recordingMbid = extractRecordingMbid(trackObj.opt("identifier"))
            val releaseName = trackObj.optString("album").takeIf { it.isNotBlank() }
            tracks.add(
                LbPlaylistTrack(
                    identity = TrackIdentity(
                        title = title.ifBlank { "Unknown Title" },
                        artist = artist.ifBlank { "Unknown Artist" },
                        album = releaseName.orEmpty()
                    ),
                    recordingMbid = recordingMbid
                )
            )
        }
        return LbPlaylistDetail(
            summary = summary.copy(trackCount = if (summary.trackCount > 0) summary.trackCount else tracks.size),
            tracks = tracks
        )
    }

    private fun parsePlaylistSummary(
        playlist: JSONObject,
        fallbackMbid: String? = null
    ): LbPlaylistSummary? {
        val mbid = extractPlaylistMbid(playlist.opt("identifier")) ?: fallbackMbid
        if (mbid.isNullOrBlank()) return null
        val title = playlist.optString("title").orEmpty().ifBlank { "Playlist" }
        val description = playlist.optString("annotation").takeIf { it.isNotBlank() }
        val trackCount = playlist.optJSONArray("track")?.length()
            ?: playlist.optInt("num_tracks", 0).takeIf { it > 0 }
            ?: extensionTrackCount(playlist)
        return LbPlaylistSummary(
            mbid = mbid,
            title = title,
            description = description,
            trackCount = trackCount
        )
    }

    private fun extensionTrackCount(playlist: JSONObject): Int {
        val extension = playlist.optJSONObject("extension") ?: return 0
        val keys = extension.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val ns = extension.optJSONObject(key) ?: continue
            val count = ns.optInt("num_tracks", -1)
            if (count >= 0) return count
            val additional = ns.optJSONObject("additional_metadata")
            val additionalCount = additional?.optInt("num_tracks", -1) ?: -1
            if (additionalCount >= 0) return additionalCount
        }
        return 0
    }

    private fun extractPlaylistMbid(identifier: Any?): String? {
        val raw = when (identifier) {
            is String -> identifier
            is JSONArray -> identifier.optString(0)
            else -> null
        } ?: return null
        val marker = "/playlist/"
        val idx = raw.lastIndexOf(marker)
        if (idx >= 0) {
            return raw.substring(idx + marker.length).substringBefore('?').trim().ifBlank { null }
        }
        return raw.trim().takeIf { it.length == 36 }
    }

    private fun extractRecordingMbid(identifier: Any?): String? {
        val raw = when (identifier) {
            is String -> identifier
            is JSONArray -> identifier.optString(0)
            else -> null
        } ?: return null
        val marker = "/recording/"
        val idx = raw.lastIndexOf(marker)
        if (idx >= 0) {
            return raw.substring(idx + marker.length).substringBefore('?').trim().ifBlank { null }
        }
        return raw.trim().takeIf { it.length == 36 }
    }

    internal fun parseMetadataLookup(json: JSONObject): LbMetadataLookup {
        val artistMbids = ArrayList<String>()
        val arr = json.optJSONArray("artist_mbids")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val mbid = arr.optString(i).orEmpty().trim()
                if (mbid.isNotEmpty()) artistMbids.add(mbid)
            }
        }
        val recordingMbid = json.optString("recording_mbid").takeIf { it.isNotBlank() }
        val artistCreditName = json.optString("artist_credit_name").takeIf { it.isNotBlank() }
        val recordingName = json.optString("recording_name").takeIf { it.isNotBlank() }
        return LbMetadataLookup(
            artistMbids = artistMbids,
            recordingMbid = recordingMbid,
            artistCreditName = artistCreditName,
            recordingName = recordingName
        )
    }

    internal fun parseLbRadioArtist(root: JSONObject): List<LbRadioRecording> {
        val result = ArrayList<LbRadioRecording>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val artistKey = keys.next()
            val recordings = root.optJSONArray(artistKey) ?: continue
            for (i in 0 until recordings.length()) {
                val obj = recordings.optJSONObject(i) ?: continue
                val recordingMbid = obj.optString("recording_mbid").orEmpty().trim()
                if (recordingMbid.isEmpty()) continue
                result.add(
                    LbRadioRecording(
                        recordingMbid = recordingMbid,
                        similarArtistMbid = obj.optString("similar_artist_mbid")
                            .takeIf { it.isNotBlank() },
                        similarArtistName = obj.optString("similar_artist_name")
                            .takeIf { it.isNotBlank() },
                        totalListenCount = obj.optLong("total_listen_count", 0L)
                    )
                )
            }
        }
        return result
    }

    internal fun parseCfRecommendations(
        root: JSONObject,
        fallbackUserName: String
    ): CfRecommendationsPayload {
        val payload = root.optJSONObject("payload") ?: root
        val mbidsArray = payload.optJSONArray("mbids") ?: JSONArray()
        val recordings = ArrayList<CfRecommendedRecording>(mbidsArray.length())
        for (i in 0 until mbidsArray.length()) {
            val obj = mbidsArray.optJSONObject(i) ?: continue
            val mbid = obj.optString("recording_mbid").orEmpty().trim()
            if (mbid.isEmpty()) continue
            recordings.add(
                CfRecommendedRecording(
                    recordingMbid = mbid,
                    score = obj.optDouble("score", 0.0)
                )
            )
        }
        val userName = payload.optString("user_name").takeIf { it.isNotBlank() }
            ?: fallbackUserName
        val lastUpdated = when {
            payload.has("last_updated") && !payload.isNull("last_updated") ->
                payload.optLong("last_updated")
            else -> null
        }
        return CfRecommendationsPayload(
            userName = userName,
            recordings = recordings,
            lastUpdatedEpochSec = lastUpdated,
            totalMbidCount = payload.optInt("total_mbid_count", recordings.size),
            artistType = payload.optString("type").takeIf { it.isNotBlank() }
        )
    }

    internal fun parseRecordingMetadataMap(root: JSONObject): Map<String, LbRecordingMetadata> {
        val result = HashMap<String, LbRecordingMetadata>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val mbid = keys.next()
            val entry = root.optJSONObject(mbid) ?: continue
            val recordingObj = entry.optJSONObject("recording")
            val title = recordingObj?.optString("name")?.takeIf { it.isNotBlank() }
                ?: entry.optString("recording_name").takeIf { it.isNotBlank() }
                ?: continue
            val artistObj = entry.optJSONObject("artist")
            val artist = artistObj?.optString("name")?.takeIf { it.isNotBlank() }
                ?: entry.optString("artist_credit_name").takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val releaseObj = entry.optJSONObject("release")
            val releaseName = releaseObj?.optString("name")?.takeIf { it.isNotBlank() }
            result[mbid] = LbRecordingMetadata(
                identity = TrackIdentity(
                    title = title,
                    artist = artist,
                    album = releaseName.orEmpty()
                ),
                recordingMbid = mbid
            )
        }
        return result
    }
}
