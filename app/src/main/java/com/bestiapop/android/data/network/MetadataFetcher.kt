package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.util.encodeAlbumTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class DeezerArtistHit(
    val id: Long,
    val pictureUrl: String?
)

object MetadataFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun cleanString(raw: String): String {
        return raw.replace(Regex("\\.(mp3|flac|m4a|wav|ogg|aac)$", RegexOption.IGNORE_CASE), "")
            .replace("_", " ")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        return if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)
    }

    private fun buildQueryText(artist: String, titleOrAlbum: String): String? {
        val cleanTitle = cleanString(titleOrAlbum)
        val cleanArtist = cleanArtist(artist)
        val queryText = if (cleanArtist.isNotEmpty()) "$cleanArtist $cleanTitle" else cleanTitle
        return queryText.ifEmpty { null }
    }

    private fun encodeQuery(queryText: String): String =
        URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())

    private fun deezerAlbumTrackNumber(obj: JSONObject): Int =
        encodeAlbumTrack(obj.optInt("track_position", 0), obj.optInt("disk_number", 0))

    // --- L1: artwork / JSON primitives (kept accessible) ---

    fun normalizeItunesArtwork(artworkUrl100: String): String? {
        if (artworkUrl100.isEmpty()) return null
        return artworkUrl100.replace("100x100bb", "600x600bb")
    }

    fun pickCoverUrl(coverXl: String?, coverBig: String?): String? {
        return coverXl?.ifBlank { null } ?: coverBig?.ifBlank { null }
    }

    fun getJson(url: String, userAgent: String = "BestiaPop/1.0"): JSONObject? {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun JSONObject.toDeezerTrackIdentity(
        requireTitleArtist: Boolean = false,
        defaultTitle: String = "Canción",
        defaultArtist: String = "Artista",
        defaultAlbum: String = "Álbum",
        defaultDurationSec: Long = 180L
    ): TrackIdentity? {
        val rawTitle = optString("title")
        val title = rawTitle.ifBlank {
            if (requireTitleArtist) return null else defaultTitle
        }
        val artistName = optJSONObject("artist")?.optString("name")?.ifBlank { null }
            ?: if (requireTitleArtist) return null else defaultArtist
        val albumObj = optJSONObject("album")
        val albumTitle = albumObj?.optString("title")?.ifBlank { null }
            ?: if (requireTitleArtist) "" else defaultAlbum
        val cover = pickCoverUrl(
            albumObj?.optString("cover_xl"),
            albumObj?.optString("cover_big")
        )
        val durationSec = optLong("duration", defaultDurationSec)
        return TrackIdentity(
            title = title,
            artist = artistName,
            album = albumTitle,
            artworkUri = cover,
            durationMs = if (durationSec > 0L) durationSec * 1000L else 0L,
            trackNumber = deezerAlbumTrackNumber(this)
        )
    }

    private fun JSONObject.toItunesTrackIdentity(
        defaultTitle: String = "Canción",
        defaultArtist: String = "Artista",
        defaultAlbum: String = "Álbum"
    ): TrackIdentity = TrackIdentity(
        title = optString("trackName", defaultTitle),
        artist = optString("artistName", defaultArtist),
        album = optString("collectionName", defaultAlbum),
        artworkUri = normalizeItunesArtwork(optString("artworkUrl100")),
        durationMs = optLong("trackTimeMillis", 180000L),
        trackNumber = encodeAlbumTrack(optInt("trackNumber", 0), optInt("discNumber", 0))
    )

    fun parseDeezerTrackArray(data: JSONArray?): List<TrackIdentity> {
        if (data == null || data.length() == 0) return emptyList()
        val out = ArrayList<TrackIdentity>(data.length())
        for (i in 0 until data.length()) {
            data.getJSONObject(i).toDeezerTrackIdentity(
                requireTitleArtist = true,
                defaultDurationSec = 0L
            )?.let { out.add(it) }
        }
        return out
    }

    // --- L2: compressed parsers / search helpers ---

    fun parseDeezerSearchTracks(
        data: JSONArray?,
        provider: String = "Deezer/YouTube"
    ): List<OnlineCatalogTrack> {
        if (data == null || data.length() == 0) return emptyList()
        val tracks = ArrayList<OnlineCatalogTrack>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val identity = obj.toDeezerTrackIdentity() ?: continue
            tracks.add(
                OnlineCatalogTrack(
                    identity = identity,
                    id = obj.optString("id").ifBlank { "${identity.youtubeSearchQuery()}#$i" },
                    audioUrl = identity.youtubeSearchQuery(),
                    provider = provider
                )
            )
        }
        return tracks
    }

    fun parseItunesSongResults(
        results: JSONArray?,
        provider: String = "iTunes/YouTube",
        limit: Int = Int.MAX_VALUE,
        defaultTitle: String = "Canción",
        defaultArtist: String = "Artista",
        defaultAlbum: String = "Álbum"
    ): List<OnlineCatalogTrack> {
        if (results == null || results.length() == 0 || limit <= 0) return emptyList()
        val tracks = ArrayList<OnlineCatalogTrack>(minOf(limit, results.length()))
        for (i in 0 until results.length()) {
            if (tracks.size >= limit) break
            val obj = results.getJSONObject(i)
            val identity = obj.toItunesTrackIdentity(defaultTitle, defaultArtist, defaultAlbum)
            tracks.add(
                OnlineCatalogTrack(
                    identity = identity,
                    id = obj.optString("trackId").ifBlank {
                        "${identity.youtubeSearchQuery()}#${obj.optString("collectionId", "$i")}"
                    },
                    audioUrl = identity.youtubeSearchQuery(),
                    provider = provider
                )
            )
        }
        return tracks
    }

    fun toCatalogCandidate(track: OnlineCatalogTrack): CatalogTrackCandidate =
        CatalogTrackCandidate(identity = track.identity, candidates = listOf(track))

    /** Deezer artist search hit (id + picture). Shared by photo URL and artist-id resolve. */
    fun searchDeezerArtist(name: String): DeezerArtistHit? {
        val cleanArtistName = cleanArtist(name)
        if (cleanArtistName.isEmpty()) return null
        val url = "https://api.deezer.com/search/artist?q=${encodeQuery(cleanArtistName)}&limit=1"
        val json = getJson(url) ?: return null
        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val item = data.getJSONObject(0)
        val id = item.optLong("id", 0L)
        if (id <= 0L) return null
        return DeezerArtistHit(
            id = id,
            pictureUrl = pickCoverUrl(item.optString("picture_xl"), item.optString("picture_big"))
        )
    }

    private fun searchDeezerTrack(queryText: String): TrackIdentity? {
        val url = "https://api.deezer.com/search?q=${encodeQuery(queryText)}&limit=1"
        val json = getJson(url) ?: return null
        return parseDeezerTrackArray(json.optJSONArray("data")).firstOrNull()
    }

    private fun searchItunesSong(queryText: String): TrackIdentity? {
        val url = "https://itunes.apple.com/search?term=${encodeQuery(queryText)}&entity=song&limit=1"
        val json = getJson(url) ?: return null
        val track = parseItunesSongResults(
            json.optJSONArray("results"),
            limit = 1,
            defaultTitle = "",
            defaultArtist = "",
            defaultAlbum = ""
        ).firstOrNull() ?: return null
        if (track.title.isBlank()) return null
        return track.identity
    }

    private fun searchDeezerAlbumArt(queryText: String): String? {
        val url = "https://api.deezer.com/search/album?q=${encodeQuery(queryText)}&limit=1"
        val json = getJson(url) ?: return null
        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val item = data.getJSONObject(0)
        return pickCoverUrl(item.optString("cover_xl"), item.optString("cover_big"))
    }

    suspend fun getFeaturedDemoCatalog(): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val tracks = searchOnlineCatalog("rock hits")
        if (tracks.isNotEmpty()) {
            return@withContext tracks
        }
        return@withContext searchOnlineCatalog("top songs")
    }

    suspend fun searchOnlineCatalog(query: String): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) {
            return@withContext getFeaturedDemoCatalog()
        }

        // 1. Deezer Song Search API
        val deezerUrl = "https://api.deezer.com/search?q=${encodeQuery(cleanQ)}&limit=25"
        val deezerTracks = parseDeezerSearchTracks(
            getJson(deezerUrl, userAgent = "Mozilla/5.0")?.optJSONArray("data")
        )
        if (deezerTracks.isNotEmpty()) return@withContext deezerTracks

        // 2. Fallback to iTunes Song Search API
        val itunesUrl =
            "https://itunes.apple.com/search?term=${encodeQuery(cleanQ)}&entity=song&limit=25"
        val itunesTracks = parseItunesSongResults(
            getJson(itunesUrl, userAgent = "Mozilla/5.0")?.optJSONArray("results")
        )
        if (itunesTracks.isNotEmpty()) return@withContext itunesTracks

        // 3. Fallback to YouTube Search API
        return@withContext YouTubeExtractor.searchYouTube(cleanQ)
    }

    suspend fun fetchArtistPhotoUrl(artist: String): String? = withContext(Dispatchers.IO) {
        searchDeezerArtist(artist)?.pictureUrl
    }

    /** Deezer artist id for radio / related lookups. */
    suspend fun resolveDeezerArtistId(artist: String): Long? = withContext(Dispatchers.IO) {
        searchDeezerArtist(artist)?.id
    }

    /** Tracks from Deezer artist radio mix. */
    suspend fun fetchDeezerArtistRadio(artistId: Long): List<TrackIdentity> = withContext(Dispatchers.IO) {
        if (artistId <= 0L) return@withContext emptyList()
        val url = "https://api.deezer.com/artist/$artistId/radio"
        val json = getJson(url) ?: return@withContext emptyList()
        return@withContext parseDeezerTrackArray(json.optJSONArray("data"))
    }

    /** Related Deezer artist ids (for diversity). */
    suspend fun fetchDeezerRelatedArtistIds(artistId: Long, limit: Int = 5): List<Long> =
        withContext(Dispatchers.IO) {
            if (artistId <= 0L || limit <= 0) return@withContext emptyList()
            val url = "https://api.deezer.com/artist/$artistId/related?limit=$limit"
            val json = getJson(url) ?: return@withContext emptyList()
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val ids = ArrayList<Long>(minOf(limit, data.length()))
            for (i in 0 until data.length()) {
                if (ids.size >= limit) break
                val id = data.getJSONObject(i).optLong("id", 0L)
                if (id > 0L) ids.add(id)
            }
            ids
        }

    /** Top tracks for a Deezer artist. */
    suspend fun fetchDeezerArtistTop(artistId: Long, limit: Int = 5): List<TrackIdentity> =
        withContext(Dispatchers.IO) {
            if (artistId <= 0L || limit <= 0) return@withContext emptyList()
            val url = "https://api.deezer.com/artist/$artistId/top?limit=$limit"
            val json = getJson(url) ?: return@withContext emptyList()
            return@withContext parseDeezerTrackArray(json.optJSONArray("data"))
        }

    /**
     * Same-artist songs from iTunes (secondary fill when Deezer remotes are short).
     */
    suspend fun fetchItunesArtistSongs(artist: String, limit: Int = 25): List<TrackIdentity> =
        withContext(Dispatchers.IO) {
            val cleanArtistName = cleanArtist(artist)
            if (cleanArtistName.isEmpty() || limit <= 0) return@withContext emptyList()
            val url =
                "https://itunes.apple.com/search?term=${encodeQuery(cleanArtistName)}&entity=song&limit=$limit"
            val json = getJson(url) ?: return@withContext emptyList()
            return@withContext parseItunesSongResults(
                json.optJSONArray("results"),
                limit = limit,
                defaultAlbum = ""
            ).mapNotNull { track ->
                if (track.title.isBlank()) return@mapNotNull null
                track.identity.copy(
                    artist = track.artist.ifBlank { cleanArtistName },
                    album = track.album
                )
            }
        }

    suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? = withContext(Dispatchers.IO) {
        val queryText = buildQueryText(artist, titleOrAlbum) ?: return@withContext null
        searchDeezerAlbumArt(queryText)?.let { return@withContext it }
        return@withContext searchItunesSong(queryText)?.artworkUri
    }

    suspend fun fetchFullTrackMetadata(artist: String, title: String): TrackIdentity? = withContext(Dispatchers.IO) {
        val queryText = buildQueryText(artist, title) ?: return@withContext null
        val deezer = searchDeezerTrack(queryText)
        if (deezer != null && deezer.album.isNotBlank()) {
            return@withContext deezer
        }
        val itunes = searchItunesSong(queryText) ?: return@withContext deezer
        if (deezer == null) return@withContext itunes
        return@withContext deezer.mergePreferring(itunes)
    }

    suspend fun fetchLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanString(title)
            val cleanArtistName = cleanArtist(artist)

            if (cleanArtistName.isNotEmpty()) {
                val artistEnc = encodeQuery(cleanArtistName)
                val trackEnc = encodeQuery(cleanTitle)
                val url = "https://lrclib.net/api/get?artist_name=$artistEnc&track_name=$trackEnc"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BestiaPop/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val syncedLyrics = json.optString("syncedLyrics")
                        val plainLyrics = json.optString("plainLyrics")
                        val res = syncedLyrics.ifEmpty { plainLyrics }
                        if (res.isNotEmpty()) return@withContext res
                    }
                }
            }

            val q = encodeQuery(
                if (cleanArtistName.isNotEmpty()) "$cleanArtistName $cleanTitle" else cleanTitle
            )
            val searchUrl = "https://lrclib.net/api/search?q=$q"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "BestiaPop/1.0")
                .build()

            client.newCall(searchReq).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val first = array.getJSONObject(0)
                        val synced = first.optString("syncedLyrics")
                        val plain = first.optString("plainLyrics")
                        val res = synced.ifEmpty { plain }
                        if (res.isNotEmpty()) return@withContext res
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun fetchTrackDurationMs(artist: String, title: String): Long = withContext(Dispatchers.IO) {
        fetchFullTrackMetadata(artist, title)?.durationMs ?: 0L
    }

    suspend fun searchAlbums(query: String): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "rock hits" }
        val list = mutableListOf<CatalogAlbum>()
        try {
            val url = "https://api.deezer.com/search/album?q=${encodeQuery(cleanQ)}&limit=15"
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val artistObj = obj.optJSONObject("artist")
                    list.add(
                        CatalogAlbum(
                            id = obj.optLong("id").toString(),
                            title = obj.optString("title", "Álbum"),
                            artist = artistObj?.optString("name", "Artista") ?: "Artista",
                            coverUrl = pickCoverUrl(obj.optString("cover_xl"), obj.optString("cover_big")),
                            trackCount = obj.optInt("nb_tracks", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to iTunes if Deezer returned empty
        if (list.isEmpty()) {
            try {
                val url =
                    "https://itunes.apple.com/search?term=${encodeQuery(cleanQ)}&entity=album&limit=15"
                val results = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val obj = results.getJSONObject(i)
                        list.add(
                            CatalogAlbum(
                                id = obj.optLong("collectionId").toString(),
                                title = obj.optString("collectionName", "Álbum"),
                                artist = obj.optString("artistName", "Artista"),
                                coverUrl = normalizeItunesArtwork(obj.optString("artworkUrl100")),
                                trackCount = obj.optInt("trackCount", 0)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext list
    }

    suspend fun searchPlaylists(query: String): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "top hits" }
        val list = mutableListOf<CatalogPlaylist>()
        try {
            val url = "https://api.deezer.com/search/playlist?q=${encodeQuery(cleanQ)}&limit=15"
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val userObj = obj.optJSONObject("user")
                    list.add(
                        CatalogPlaylist(
                            id = obj.optLong("id").toString(),
                            title = obj.optString("title", "Playlist"),
                            creator = userObj?.optString("name", "Deezer User") ?: "Deezer User",
                            coverUrl = pickCoverUrl(
                                obj.optString("picture_xl"),
                                obj.optString("picture_big")
                            ),
                            trackCount = obj.optInt("nb_tracks", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext list
    }

    suspend fun fetchAlbumTrackCandidates(
        albumId: String,
        albumTitle: String,
        artistName: String,
        albumCoverUrl: String?
    ): List<CatalogTrackCandidate> = withContext(Dispatchers.IO) {
        val resultCandidates = mutableListOf<CatalogTrackCandidate>()
        try {
            val url = "https://api.deezer.com/album/$albumId/tracks?limit=50"
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null && data.length() > 0) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val trackTitle = obj.optString("title", "Pista ${i + 1}")
                    val trackArtistObj = obj.optJSONObject("artist")
                    val trackArtist = trackArtistObj?.optString("name", artistName) ?: artistName
                    resultCandidates.add(
                        toCatalogCandidate(
                            OnlineCatalogTrack(
                                identity = TrackIdentity(
                                    title = trackTitle,
                                    artist = trackArtist,
                                    album = albumTitle,
                                    artworkUri = albumCoverUrl,
                                    durationMs = obj.optLong("duration", 180L) * 1000L,
                                    trackNumber = deezerAlbumTrackNumber(obj)
                                ),
                                id = "$trackArtist $trackTitle",
                                audioUrl = "$trackArtist $trackTitle",
                                provider = "YouTube"
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to iTunes song search if Deezer album tracks returned empty
        if (resultCandidates.isEmpty()) {
            try {
                val queryTerm = "$artistName $albumTitle".trim()
                val url =
                    "https://itunes.apple.com/search?term=${encodeQuery(queryTerm)}&entity=song&limit=30"
                val tracks = parseItunesSongResults(
                    getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("results"),
                    provider = "YouTube",
                    limit = 30,
                    defaultAlbum = albumTitle,
                    defaultArtist = artistName
                )
                for (track in tracks) {
                    val cover = track.artworkUri ?: albumCoverUrl
                    resultCandidates.add(
                        toCatalogCandidate(
                            track.copy(
                                identity = track.identity.copy(artworkUri = cover),
                                id = track.youtubeSearchQuery(),
                                audioUrl = track.youtubeSearchQuery(),
                                provider = "YouTube"
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext resultCandidates
    }

    suspend fun fetchPlaylistTrackCandidates(
        playlistId: String,
        playlistTitle: String
    ): List<CatalogTrackCandidate> = withContext(Dispatchers.IO) {
        val resultCandidates = mutableListOf<CatalogTrackCandidate>()
        try {
            val url = "https://api.deezer.com/playlist/$playlistId/tracks?limit=50"
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val trackTitle = obj.optString("title", "Pista ${i + 1}")
                    val trackArtistObj = obj.optJSONObject("artist")
                    val trackArtist = trackArtistObj?.optString("name", "Artista") ?: "Artista"
                    val albumObj = obj.optJSONObject("album")
                    val albumName = albumObj?.optString("title", playlistTitle) ?: playlistTitle
                    val cover = pickCoverUrl(
                        albumObj?.optString("cover_xl"),
                        albumObj?.optString("cover_big")
                    )
                    resultCandidates.add(
                        toCatalogCandidate(
                            OnlineCatalogTrack(
                                identity = TrackIdentity(
                                    title = trackTitle,
                                    artist = trackArtist,
                                    album = albumName,
                                    artworkUri = cover,
                                    durationMs = obj.optLong("duration", 180L) * 1000L,
                                    trackNumber = deezerAlbumTrackNumber(obj)
                                ),
                                id = youtubeSearchQuery(trackArtist, trackTitle),
                                audioUrl = youtubeSearchQuery(trackArtist, trackTitle),
                                provider = "YouTube"
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext resultCandidates
    }

}
