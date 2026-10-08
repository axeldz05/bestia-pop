package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.ParsedLinkTarget
import com.bestiapop.android.data.model.PlaylistPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.regex.Pattern

object PlaylistUrlParser {
    private val DIRECT_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "opus", "webm")

    private val SPOTIFY_PLAYLIST_PATTERN =
        Pattern.compile("""(?:spotify:playlist:|open\.spotify\.com/(?:[^/?#]+/)?playlist/)([a-zA-Z0-9]+)""")

    private val SPOTIFY_TRACK_PATTERN =
        Pattern.compile("""(?:spotify:track:|open\.spotify\.com/(?:[^/?#]+/)?track/)([a-zA-Z0-9]+)""")

    private val DEEZER_PLAYLIST_PATTERN =
        Pattern.compile("""deezer\.com/(?:[^/?#]+/)?playlist/(\d+)""")

    private val DEEZER_TRACK_PATTERN =
        Pattern.compile("""deezer\.com/(?:[^/?#]+/)?track/(\d+)""")

    private val YOUTUBE_PLAYLIST_PARAM_PATTERN =
        Pattern.compile("""[?&]list=([a-zA-Z0-9_-]+)""")

    fun parse(rawUrl: String): ParsedLinkTarget {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ParsedLinkTarget.Unsupported(trimmed)

        // 1. Check direct audio URLs
        val pathWithoutQuery = trimmed.substringBefore("?").substringBefore("#")
        val extension = pathWithoutQuery.substringAfterLast(".", "").lowercase()
        if (extension in DIRECT_AUDIO_EXTENSIONS) {
            return ParsedLinkTarget.DirectAudio(trimmed)
        }

        // 2. Spotify
        val spotifyPlaylistMatch = SPOTIFY_PLAYLIST_PATTERN.matcher(trimmed)
        if (spotifyPlaylistMatch.find()) {
            val id = spotifyPlaylistMatch.group(1).orEmpty()
            if (id.isNotBlank()) {
                return ParsedLinkTarget.Playlist(PlaylistPlatform.SPOTIFY, id)
            }
        }

        val spotifyTrackMatch = SPOTIFY_TRACK_PATTERN.matcher(trimmed)
        if (spotifyTrackMatch.find()) {
            val id = spotifyTrackMatch.group(1).orEmpty()
            if (id.isNotBlank()) {
                return ParsedLinkTarget.Track(PlaylistPlatform.SPOTIFY, id)
            }
        }

        // 3. Deezer
        val deezerPlaylistMatch = DEEZER_PLAYLIST_PATTERN.matcher(trimmed)
        if (deezerPlaylistMatch.find()) {
            val id = deezerPlaylistMatch.group(1).orEmpty()
            if (id.isNotBlank()) {
                return ParsedLinkTarget.Playlist(PlaylistPlatform.DEEZER, id)
            }
        }

        val deezerTrackMatch = DEEZER_TRACK_PATTERN.matcher(trimmed)
        if (deezerTrackMatch.find()) {
            val id = deezerTrackMatch.group(1).orEmpty()
            if (id.isNotBlank()) {
                return ParsedLinkTarget.Track(PlaylistPlatform.DEEZER, id)
            }
        }

        // 4. YouTube / YouTube Music Playlist
        if (trimmed.contains("youtube.com") || trimmed.contains("youtu.be")) {
            val listMatch = YOUTUBE_PLAYLIST_PARAM_PATTERN.matcher(trimmed)
            if (listMatch.find()) {
                val listId = listMatch.group(1).orEmpty()
                if (listId.isNotBlank()) {
                    return ParsedLinkTarget.Playlist(PlaylistPlatform.YOUTUBE, listId)
                }
            }

            // YouTube single track
            val ytId = YouTubeExtractor.extractYouTubeId(trimmed)
            if (!ytId.isNullOrBlank()) {
                return ParsedLinkTarget.Track(PlaylistPlatform.YOUTUBE, trimmed)
            }
        }

        return ParsedLinkTarget.Unsupported(trimmed)
    }

    private val KNOWN_SHORT_LINK_HOSTS = listOf("spotify.link", "deezer.page.link")

    fun isKnownShortLink(url: String): Boolean = KNOWN_SHORT_LINK_HOSTS.any { url.contains(it, ignoreCase = true) }

    /**
     * Resolves short links (like spotify.link or deezer.page.link) by following HTTP redirects.
     */
    suspend fun resolveAndParse(
        rawUrl: String,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): ParsedLinkTarget {
        val initial = parse(rawUrl)
        if (initial !is ParsedLinkTarget.Unsupported) return initial

        val trimmed = rawUrl.trim()
        if (!isKnownShortLink(trimmed)) return initial

        return withContext(dispatcher) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(trimmed)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                HttpClients.api.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    parse(finalUrl)
                }
            } catch (_: Exception) {
                initial
            }
        }
    }
}
