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
import java.util.regex.Pattern

object SpotifyPlaylistExtractor {
    private const val SPOTIFY_EMBED_BASE = "https://open.spotify.com/embed"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val NEXT_DATA_PATTERN =
        Pattern.compile("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", Pattern.DOTALL)

    suspend fun fetchPlaylist(playlistId: String): Result<ImportedPlaylistData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val embedUrl = "$SPOTIFY_EMBED_BASE/playlist/$playlistId"
                val request =
                    Request
                        .Builder()
                        .url(embedUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                        .build()

                val html =
                    try {
                        HttpClients.api.newCall(request).execute().use { response ->
                            if (response.code in 400..404) {
                                throw PlaylistImportException.PrivateOrUnavailable("Spotify")
                            }
                            if (!response.isSuccessful) {
                                throw PlaylistImportException.NetworkError("HTTP ${response.code} en Spotify")
                            }
                            response.body?.string().orEmpty()
                        }
                    } catch (e: IOException) {
                        throw PlaylistImportException.NetworkError(e.message)
                    }

                val matcher = NEXT_DATA_PATTERN.matcher(html)
                if (!matcher.find()) {
                    throw PlaylistImportException.PrivateOrUnavailable("Spotify")
                }

                val jsonStr = matcher.group(1).orEmpty()
                val root = JSONObject(jsonStr)
                val entity =
                    root
                        .optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("state")
                        ?.optJSONObject("data")
                        ?.optJSONObject("entity")
                        ?: throw PlaylistImportException.PrivateOrUnavailable("Spotify")

                val title =
                    entity
                        .optString("title")
                        .ifBlank { entity.optString("name") }
                        .ifBlank { "Playlist de Spotify" }

                val coverUrl = extractCoverUrl(entity)

                val trackList = entity.optJSONArray("trackList")
                if (trackList == null || trackList.length() == 0) {
                    throw PlaylistImportException.EmptyPlaylist("Spotify")
                }

                val tracks = mutableListOf<TrackIdentity>()
                for (i in 0 until trackList.length()) {
                    val trackObj = trackList.optJSONObject(i) ?: continue
                    val trackTitle = trackObj.optString("title").trim()
                    val trackArtist = trackObj.optString("subtitle").trim()
                    if (trackTitle.isBlank()) continue

                    val durationMs = trackObj.optLong("duration", 0L)

                    tracks.add(
                        TrackIdentity(
                            title = trackTitle,
                            artist = trackArtist,
                            album = title,
                            artworkUri = coverUrl,
                            durationMs = durationMs,
                            trackNumber = i + 1,
                        ),
                    )
                }

                if (tracks.isEmpty()) {
                    throw PlaylistImportException.EmptyPlaylist("Spotify")
                }

                ImportedPlaylistData(
                    id = playlistId,
                    title = title,
                    coverUrl = coverUrl,
                    platform = PlaylistPlatform.SPOTIFY,
                    tracks = tracks,
                )
            }
        }

    suspend fun fetchTrack(trackId: String): Result<TrackIdentity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val embedUrl = "$SPOTIFY_EMBED_BASE/track/$trackId"
                val request =
                    Request
                        .Builder()
                        .url(embedUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                        .build()

                val html =
                    try {
                        HttpClients.api.newCall(request).execute().use { response ->
                            if (response.code in 400..404) {
                                throw PlaylistImportException.PrivateOrUnavailable("Spotify")
                            }
                            if (!response.isSuccessful) {
                                throw PlaylistImportException.NetworkError("HTTP ${response.code} en Spotify")
                            }
                            response.body?.string().orEmpty()
                        }
                    } catch (e: IOException) {
                        throw PlaylistImportException.NetworkError(e.message)
                    }

                val matcher = NEXT_DATA_PATTERN.matcher(html)
                if (!matcher.find()) {
                    throw PlaylistImportException.PrivateOrUnavailable("Spotify")
                }

                val jsonStr = matcher.group(1).orEmpty()
                val root = JSONObject(jsonStr)
                val entity =
                    root
                        .optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("state")
                        ?.optJSONObject("data")
                        ?.optJSONObject("entity")
                        ?: throw PlaylistImportException.PrivateOrUnavailable("Spotify")

                val title =
                    entity
                        .optString("title")
                        .ifBlank { entity.optString("name") }
                        .ifBlank { "Canción de Spotify" }

                val artist =
                    entity.optString("subtitle").ifBlank {
                        val artistsArray = entity.optJSONArray("artists")
                        artistsArray?.optJSONObject(0)?.optString("name").orEmpty()
                    }

                val coverUrl = extractCoverUrl(entity)
                val durationMs = entity.optLong("duration", 0L)

                TrackIdentity(
                    title = title,
                    artist = artist,
                    album = "",
                    artworkUri = coverUrl,
                    durationMs = durationMs,
                )
            }
        }

    private fun extractCoverUrl(entity: JSONObject): String? {
        val visualImages = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
        if (visualImages != null && visualImages.length() > 0) {
            val largest = visualImages.optJSONObject(visualImages.length() - 1)
            val url = largest?.optString("url")
            if (!url.isNullOrBlank()) return url
        }
        val directCover = entity.optString("cover")
        if (directCover.isNotBlank()) return directCover
        val directImages = entity.optJSONArray("images")
        if (directImages != null && directImages.length() > 0) {
            val url = directImages.optJSONObject(0)?.optString("url")
            if (!url.isNullOrBlank()) return url
        }
        val coverArtUrl = entity.optJSONObject("coverArt")?.optJSONArray("sources")
        if (coverArtUrl != null && coverArtUrl.length() > 0) {
            return coverArtUrl.optJSONObject(coverArtUrl.length() - 1)?.optString("url")
        }
        return null
    }
}
