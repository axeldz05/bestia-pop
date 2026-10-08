package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.PlaylistPlatform
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

data class SpotifyUserProfile(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class SpotifyPlaylistSummary(
    val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val trackCount: Int,
    val isPublic: Boolean,
    val isOwner: Boolean,
    val ownerName: String,
)

object SpotifyApiClient {
    private const val API_BASE = "https://api.spotify.com/v1"

    fun isTrustedSpotifyUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return httpUrl.isHttps &&
            httpUrl.host.equals("api.spotify.com", ignoreCase = true) &&
            httpUrl.username.isEmpty() &&
            httpUrl.password.isEmpty()
    }

    suspend fun executeSpotifyRequest(
        url: String,
        accessToken: String,
        maxRetries: Int = 2,
    ): String =
        withContext(Dispatchers.IO) {
            if (!isTrustedSpotifyUrl(url)) {
                throw SecurityException("URL no permitida para llamadas a API de Spotify: $url")
            }

            var attempt = 0
            var lastErrorCode = 0
            while (attempt <= maxRetries) {
                currentCoroutineContext().ensureActive()
                attempt++
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Authorization", "Bearer $accessToken")
                        .build()

                val (code, retryAfterSec, bodyString) =
                    HttpClients.api.newCall(request).execute().use { response ->
                        val retrySec =
                            response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 30L)
                        Triple(response.code, retrySec, response.body?.string().orEmpty())
                    }

                if (code in 200..299) {
                    return@withContext bodyString
                }

                lastErrorCode = code
                if (code == 429 && attempt <= maxRetries) {
                    val waitMs = (retryAfterSec ?: (attempt * 2L)) * 1000L
                    delay(waitMs)
                    continue
                }

                when (code) {
                    401 -> throw IOException("Sesión de Spotify no autorizada o expirada (HTTP 401)")
                    403 -> throw IOException("Acceso denegado a recurso de Spotify (HTTP 403)")
                    429 -> throw IOException("Límite de solicitudes de Spotify alcanzado (HTTP 429)")
                    else -> throw IOException("Error al consultar API de Spotify: HTTP $code")
                }
            }

            throw IOException("Error al consultar API de Spotify: HTTP $lastErrorCode")
        }

    suspend fun fetchUserProfile(accessToken: String): Result<SpotifyUserProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val responseBody = executeSpotifyRequest("$API_BASE/me", accessToken)
                parseUserProfile(responseBody)
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun fetchUserPlaylists(accessToken: String): Result<List<SpotifyPlaylistSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val playlists = mutableListOf<SpotifyPlaylistSummary>()
                var nextUrl: String? = "$API_BASE/me/playlists?limit=50"
                var pageCount = 0

                while (!nextUrl.isNullOrBlank() && pageCount < 10) {
                    currentCoroutineContext().ensureActive()
                    pageCount++
                    val responseBody = executeSpotifyRequest(nextUrl, accessToken)
                    val (pagePlaylists, next) = parsePlaylistsResponse(responseBody)
                    playlists.addAll(pagePlaylists)
                    nextUrl = next
                }

                playlists
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun fetchPlaylistAllTracks(
        accessToken: String,
        playlistId: String,
        playlistName: String,
        coverUrl: String?,
        onProgress: ((loaded: Int) -> Unit)? = null,
    ): Result<ImportedPlaylistData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tracks = mutableListOf<TrackIdentity>()
                var nextUrl: String? = "$API_BASE/playlists/$playlistId/tracks?limit=100"
                var pageCount = 0

                while (!nextUrl.isNullOrBlank() && pageCount < 20) {
                    currentCoroutineContext().ensureActive()
                    pageCount++
                    val responseBody = executeSpotifyRequest(nextUrl, accessToken)
                    val (pageTracks, next) =
                        parseTrackItemsResponse(
                            jsonString = responseBody,
                            fallbackAlbum = playlistName,
                            fallbackCover = coverUrl,
                            startingTrackNumber = tracks.size + 1,
                        )
                    tracks.addAll(pageTracks)
                    onProgress?.invoke(tracks.size)
                    nextUrl = next
                }

                ImportedPlaylistData(
                    id = playlistId,
                    title = playlistName,
                    coverUrl = coverUrl,
                    platform = PlaylistPlatform.SPOTIFY,
                    tracks = tracks,
                )
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun fetchLikedSongsAsPlaylist(
        accessToken: String,
        onProgress: ((loaded: Int) -> Unit)? = null,
    ): Result<ImportedPlaylistData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tracks = mutableListOf<TrackIdentity>()
                var nextUrl: String? = "$API_BASE/me/tracks?limit=50"
                var pageCount = 0

                while (!nextUrl.isNullOrBlank() && pageCount < 20) {
                    currentCoroutineContext().ensureActive()
                    pageCount++
                    val responseBody = executeSpotifyRequest(nextUrl, accessToken)
                    val (pageTracks, next) =
                        parseTrackItemsResponse(
                            jsonString = responseBody,
                            fallbackAlbum = "Tus me gusta (Spotify)",
                            fallbackCover = null,
                            startingTrackNumber = tracks.size + 1,
                        )
                    tracks.addAll(pageTracks)
                    onProgress?.invoke(tracks.size)
                    nextUrl = next
                }

                ImportedPlaylistData(
                    id = "spotify-liked-songs",
                    title = "Tus me gusta (Spotify)",
                    coverUrl = tracks.firstOrNull()?.artworkUri,
                    platform = PlaylistPlatform.SPOTIFY,
                    tracks = tracks,
                )
            }.onFailure { if (it is CancellationException) throw it }
        }

    fun parseUserProfile(jsonString: String): SpotifyUserProfile {
        val json = JSONObject(jsonString)
        val id = json.getString("id")
        val displayName = json.optString("display_name").ifBlank { id }
        val avatarUrl =
            json
                .optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }

        return SpotifyUserProfile(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
    }

    fun parsePlaylistsResponse(jsonString: String): Pair<List<SpotifyPlaylistSummary>, String?> {
        val json = JSONObject(jsonString)
        val items = json.optJSONArray("items") ?: return emptyList<SpotifyPlaylistSummary>() to null
        val playlists = ArrayList<SpotifyPlaylistSummary>(items.length())

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue

            val name = item.optString("name").ifBlank { "Playlist de Spotify" }
            val description = item.optString("description").takeIf { it.isNotBlank() }
            val images = item.optJSONArray("images")
            val coverUrl = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            val trackCount = item.optJSONObject("tracks")?.optInt("total", 0) ?: 0
            val isPublic = item.optBoolean("public", false)
            val ownerObj = item.optJSONObject("owner")
            val ownerName = ownerObj?.optString("display_name").orEmpty()

            playlists.add(
                SpotifyPlaylistSummary(
                    id = id,
                    name = name,
                    description = description,
                    coverUrl = coverUrl,
                    trackCount = trackCount,
                    isPublic = isPublic,
                    isOwner = true,
                    ownerName = ownerName,
                ),
            )
        }

        val nextUrl = json.optString("next").takeIf { it.isNotBlank() }
        return playlists to nextUrl
    }

    fun parseTrackItemsResponse(
        jsonString: String,
        fallbackAlbum: String,
        fallbackCover: String?,
        startingTrackNumber: Int = 1,
    ): Pair<List<TrackIdentity>, String?> {
        val json = JSONObject(jsonString)
        val items = json.optJSONArray("items") ?: return emptyList<TrackIdentity>() to null
        val tracks = ArrayList<TrackIdentity>(items.length())

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val trackObj = item.optJSONObject("track") ?: continue

            val title = trackObj.optString("name").trim()
            if (title.isBlank()) continue

            val artistsArray = trackObj.optJSONArray("artists")
            val artistNames = mutableListOf<String>()
            if (artistsArray != null) {
                for (a in 0 until artistsArray.length()) {
                    val aName = artistsArray.optJSONObject(a)?.optString("name")
                    if (!aName.isNullOrBlank()) artistNames.add(aName)
                }
            }
            val artist = artistNames.joinToString(", ")

            val albumObj = trackObj.optJSONObject("album")
            val albumName = albumObj?.optString("name").orEmpty()
            val albumCover =
                albumObj
                    ?.optJSONArray("images")
                    ?.optJSONObject(0)
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() } ?: fallbackCover

            val durationMs = trackObj.optLong("duration_ms", 0L)

            tracks.add(
                TrackIdentity(
                    title = title,
                    artist = artist,
                    album = albumName.ifBlank { fallbackAlbum },
                    artworkUri = albumCover,
                    durationMs = durationMs,
                    trackNumber = startingTrackNumber + tracks.size,
                ),
            )
        }

        val nextUrl = json.optString("next").takeIf { it.isNotBlank() }
        return tracks to nextUrl
    }
}
