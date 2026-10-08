package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.PlaylistImportException
import com.bestiapop.android.data.model.PlaylistPlatform
import com.bestiapop.android.data.model.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object DeezerPlaylistExtractor {
    private const val DEEZER_API_BASE = "https://api.deezer.com"

    suspend fun fetchPlaylist(playlistId: String): Result<ImportedPlaylistData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$DEEZER_API_BASE/playlist/$playlistId"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                val jsonStr =
                    try {
                        HttpClients.api.newCall(request).execute().use { response ->
                            if (response.code in 400..404) {
                                throw PlaylistImportException.PrivateOrUnavailable("Deezer")
                            }
                            if (!response.isSuccessful) {
                                throw PlaylistImportException.NetworkError("HTTP ${response.code} en Deezer")
                            }
                            response.body?.string().orEmpty()
                        }
                    } catch (e: IOException) {
                        throw PlaylistImportException.NetworkError(e.message)
                    }

                val root = JSONObject(jsonStr)
                val error = root.optJSONObject("error")
                if (error != null) {
                    val code = error.optInt("code", 0)
                    val type = error.optString("type", "")
                    if (code == 800 || type.contains("DataException", ignoreCase = true)) {
                        throw PlaylistImportException.PrivateOrUnavailable("Deezer")
                    }
                    throw PlaylistImportException.NetworkError("Deezer API error: ${error.optString("message")}")
                }

                val title = root.optString("title").ifBlank { "Playlist de Deezer" }
                val coverUrl =
                    root
                        .optString("picture_xl")
                        .ifBlank {
                            root.optString("picture_big").ifBlank {
                                root.optString("picture_medium")
                            }
                        }.takeIf { it.isNotBlank() }

                val trackData = root.optJSONObject("tracks")?.optJSONArray("data")
                if (trackData == null || trackData.length() == 0) {
                    throw PlaylistImportException.EmptyPlaylist("Deezer")
                }

                val tracks = mutableListOf<TrackIdentity>()
                for (i in 0 until trackData.length()) {
                    val trackObj = trackData.optJSONObject(i) ?: continue
                    val trackTitle = trackObj.optString("title").trim()
                    if (trackTitle.isBlank()) continue

                    val artistName =
                        trackObj
                            .optJSONObject("artist")
                            ?.optString("name")
                            ?.trim()
                            .orEmpty()
                    val albumObj = trackObj.optJSONObject("album")
                    val albumName = albumObj?.optString("title")?.trim().orEmpty()
                    val durationSec = trackObj.optLong("duration", 0L)
                    val trackCover =
                        albumObj
                            ?.optString("cover_xl")
                            ?.ifBlank {
                                albumObj.optString("cover_medium")
                            }?.takeIf { it.isNotBlank() } ?: coverUrl

                    tracks.add(
                        TrackIdentity(
                            title = trackTitle,
                            artist = artistName,
                            album = albumName.ifBlank { title },
                            artworkUri = trackCover,
                            durationMs = durationSec * 1000L,
                            trackNumber = i + 1,
                        ),
                    )
                }

                var nextUrl = root.optJSONObject("tracks")?.optString("next")?.takeIf { it.isNotBlank() }
                var pageCount = 0
                while (!nextUrl.isNullOrBlank() && pageCount < 20 && tracks.size < 500) {
                    pageCount++
                    try {
                        val nextReq =
                            Request
                                .Builder()
                                .url(nextUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()
                        val nextJson =
                            HttpClients.api
                                .newCall(nextReq)
                                .execute()
                                .use { it.body?.string().orEmpty() }
                        val nextRoot = JSONObject(nextJson)
                        val nextData = nextRoot.optJSONArray("data") ?: break
                        for (i in 0 until nextData.length()) {
                            val trackObj = nextData.optJSONObject(i) ?: continue
                            val trackTitle = trackObj.optString("title").trim()
                            if (trackTitle.isBlank()) continue
                            val artistName =
                                trackObj
                                    .optJSONObject("artist")
                                    ?.optString("name")
                                    ?.trim()
                                    .orEmpty()
                            val albumObj = trackObj.optJSONObject("album")
                            val albumName = albumObj?.optString("title")?.trim().orEmpty()
                            val durationSec = trackObj.optLong("duration", 0L)
                            val trackCover =
                                albumObj
                                    ?.optString("cover_xl")
                                    ?.ifBlank {
                                        albumObj.optString("cover_medium")
                                    }?.takeIf { it.isNotBlank() } ?: coverUrl

                            tracks.add(
                                TrackIdentity(
                                    title = trackTitle,
                                    artist = artistName,
                                    album = albumName.ifBlank { title },
                                    artworkUri = trackCover,
                                    durationMs = durationSec * 1000L,
                                    trackNumber = tracks.size + 1,
                                ),
                            )
                        }
                        nextUrl = nextRoot.optString("next").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        break
                    }
                }

                if (tracks.isEmpty()) {
                    throw PlaylistImportException.EmptyPlaylist("Deezer")
                }

                ImportedPlaylistData(
                    id = playlistId,
                    title = title,
                    coverUrl = coverUrl,
                    platform = PlaylistPlatform.DEEZER,
                    tracks = tracks,
                )
            }
        }

    suspend fun fetchTrack(trackId: String): Result<TrackIdentity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$DEEZER_API_BASE/track/$trackId"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                val jsonStr =
                    try {
                        HttpClients.api.newCall(request).execute().use { response ->
                            if (response.code in 400..404) {
                                throw PlaylistImportException.PrivateOrUnavailable("Deezer")
                            }
                            if (!response.isSuccessful) {
                                throw PlaylistImportException.NetworkError("HTTP ${response.code} en Deezer")
                            }
                            response.body?.string().orEmpty()
                        }
                    } catch (e: IOException) {
                        throw PlaylistImportException.NetworkError(e.message)
                    }

                val root = JSONObject(jsonStr)
                val error = root.optJSONObject("error")
                if (error != null) {
                    throw PlaylistImportException.PrivateOrUnavailable("Deezer")
                }

                val title = root.optString("title").ifBlank { "Canción de Deezer" }
                val artist = root.optJSONObject("artist")?.optString("name").orEmpty()
                val albumObj = root.optJSONObject("album")
                val album = albumObj?.optString("title").orEmpty()
                val durationSec = root.optLong("duration", 0L)
                val cover =
                    albumObj
                        ?.optString("cover_xl")
                        ?.ifBlank {
                            albumObj.optString("cover_medium")
                        }?.takeIf { it.isNotBlank() }

                TrackIdentity(
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUri = cover,
                    durationMs = durationSec * 1000L,
                )
            }
        }
}
