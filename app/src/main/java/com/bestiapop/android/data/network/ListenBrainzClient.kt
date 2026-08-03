package com.bestiapop.android.data.network

import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.LbPlaylistSummary
import com.bestiapop.android.data.listenbrainz.LbPlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
)

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
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
        try {
            val encodedUser = URLEncoder.encode(username.trim(), Charsets.UTF_8.name())
            val url = "$BASE_URL/user/$encodedUser/playlists/createdfor?count=$count&offset=$offset"
            val request = buildGetRequest(url, token)
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LbApiResult.Failure(
                        message = errorMessageFromBody(body, response.code)
                    )
                }
                val playlists = parsePlaylistSummaries(JSONObject(body))
                LbApiResult.Success(playlists)
            }
        } catch (e: Exception) {
            LbApiResult.Failure(
                message = e.message ?: "Error de red",
                isNetworkError = true
            )
        }
    }

    suspend fun fetchPlaylist(
        playlistMbid: String,
        token: String? = null
    ): LbApiResult<LbPlaylistDetail> = withContext(Dispatchers.IO) {
        if (playlistMbid.isBlank()) {
            return@withContext LbApiResult.Failure("Playlist inválida")
        }
        try {
            val encodedMbid = URLEncoder.encode(playlistMbid.trim(), Charsets.UTF_8.name())
            val url = "$BASE_URL/playlist/$encodedMbid"
            val request = buildGetRequest(url, token)
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LbApiResult.Failure(
                        message = errorMessageFromBody(body, response.code)
                    )
                }
                val detail = parsePlaylistDetail(JSONObject(body), playlistMbid.trim())
                    ?: return@withContext LbApiResult.Failure("Respuesta de playlist inválida")
                LbApiResult.Success(detail)
            }
        } catch (e: Exception) {
            LbApiResult.Failure(
                message = e.message ?: "Error de red",
                isNetworkError = true
            )
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
                    title = title.ifBlank { "Unknown Title" },
                    artist = artist.ifBlank { "Unknown Artist" },
                    recordingMbid = recordingMbid,
                    releaseName = releaseName
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
}
