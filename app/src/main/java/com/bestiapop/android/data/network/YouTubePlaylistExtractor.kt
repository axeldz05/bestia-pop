package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.ImportedPlaylistData
import com.bestiapop.android.data.model.PlaylistImportException
import com.bestiapop.android.data.model.PlaylistPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

object YouTubePlaylistExtractor {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val YT_INITIAL_DATA_PATTERN =
        Pattern.compile("""var ytInitialData = (\{.*?\});</script>""", Pattern.DOTALL)

    suspend fun fetchPlaylist(playlistId: String): Result<ImportedPlaylistData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanId =
                    playlistId
                        .removePrefix("VL")
                        .substringAfter("list=")
                        .substringBefore("&")
                        .trim()

                if (cleanId.isBlank()) {
                    throw PlaylistImportException.PrivateOrUnavailable("YouTube")
                }

                val url = "https://www.youtube.com/playlist?list=$cleanId"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                        .build()

                var playlistTitle = "Playlist de YouTube"
                var coverUrl: String? = null
                var hasAlertError = false

                try {
                    HttpClients.api.newCall(request).execute().use { response ->
                        if (response.code in 400..404) {
                            throw PlaylistImportException.PrivateOrUnavailable("YouTube")
                        }
                        if (!response.isSuccessful) {
                            throw PlaylistImportException.NetworkError("HTTP ${response.code} en YouTube")
                        }
                        val html = response.body?.string().orEmpty()
                        val matcher = YT_INITIAL_DATA_PATTERN.matcher(html)
                        if (matcher.find()) {
                            val data = JSONObject(matcher.group(1).orEmpty())

                            // Check alerts for private/non-existent playlist
                            val alerts = data.optJSONArray("alerts")
                            if (alerts != null && alerts.length() > 0) {
                                for (i in 0 until alerts.length()) {
                                    val alertObj = alerts.optJSONObject(i)?.optJSONObject("alertRenderer")
                                    val alertType = alertObj?.optString("type")
                                    if (alertType.equals("ERROR", ignoreCase = true)) {
                                        hasAlertError = true
                                        break
                                    }
                                }
                            }

                            // Extract title
                            val metaTitle =
                                data
                                    .optJSONObject("metadata")
                                    ?.optJSONObject("playlistMetadataRenderer")
                                    ?.optString("title")

                            val headerTitle =
                                data
                                    .optJSONObject("header")
                                    ?.optJSONObject("playlistHeaderRenderer")
                                    ?.optJSONObject("title")
                                    ?.let { t ->
                                        t.optString("simpleText").ifBlank {
                                            t.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                        }
                                    }

                            val microformatTitle =
                                data
                                    .optJSONObject("microformat")
                                    ?.optJSONObject("microformatDataRenderer")
                                    ?.optString("title")

                            playlistTitle =
                                metaTitle?.takeIf { it.isNotBlank() }
                                    ?: headerTitle?.takeIf { it.isNotBlank() }
                                    ?: microformatTitle?.takeIf { it.isNotBlank() }
                                    ?: playlistTitle

                            // Extract thumbnail
                            val microformatThumbs =
                                data
                                    .optJSONObject("microformat")
                                    ?.optJSONObject("microformatDataRenderer")
                                    ?.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")

                            if (microformatThumbs != null && microformatThumbs.length() > 0) {
                                coverUrl = microformatThumbs.optJSONObject(microformatThumbs.length() - 1)?.optString("url")
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw PlaylistImportException.NetworkError(e.message)
                }

                if (hasAlertError) {
                    throw PlaylistImportException.PrivateOrUnavailable("YouTube")
                }

                val candidates = YouTubeExtractor.fetchPlaylistTrackCandidates(cleanId, playlistTitle)
                if (candidates.isEmpty()) {
                    if (hasAlertError || playlistTitle == "Playlist de YouTube") {
                        throw PlaylistImportException.PrivateOrUnavailable("YouTube")
                    } else {
                        throw PlaylistImportException.EmptyPlaylist("YouTube")
                    }
                }

                val tracks = candidates.map { it.identity }
                val resolvedCover = coverUrl ?: tracks.firstOrNull()?.artworkUri

                ImportedPlaylistData(
                    id = cleanId,
                    title = playlistTitle,
                    coverUrl = resolvedCover,
                    platform = PlaylistPlatform.YOUTUBE,
                    tracks = tracks,
                )
            }
        }
}
