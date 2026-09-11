package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.toCatalogTrack
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.util.encodeAlbumTrack
import com.bestiapop.android.domain.util.IdentifyQueryVariants
import kotlinx.coroutines.CancellationException
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

internal data class MetadataFetcherEndpoints(
    val deezerBaseUrl: String = "https://api.deezer.com",
    val itunesBaseUrl: String = "https://itunes.apple.com",
    val lyricsBaseUrl: String = "https://lrclib.net"
)

object MetadataFetcher {

    private val defaultClient = HttpClients.api.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var client: OkHttpClient = defaultClient

    @Volatile
    private var endpoints = MetadataFetcherEndpoints()

    internal fun configureForTest(
        http: OkHttpClient,
        endpoints: MetadataFetcherEndpoints
    ) {
        client = http
        this.endpoints = endpoints
    }

    internal fun resetTestOverrides() {
        client = defaultClient
        endpoints = MetadataFetcherEndpoints()
    }

    private fun endpoint(baseUrl: String, pathAndQuery: String): String =
        "${baseUrl.trimEnd('/')}/${pathAndQuery.trimStart('/')}"

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
        var attempts = 0
        while (attempts < 3) {
            attempts++
            try {
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val (statusCode, bodyString, retryAfterSec) = client.newCall(request).execute().use { response ->
                    val retrySec = response.header("Retry-After")?.toLongOrNull()
                    Triple(response.code, if (response.isSuccessful) response.body?.string() else null, retrySec)
                }
                if (statusCode == 429 && attempts < 3) {
                    val waitMs = (retryAfterSec?.times(1000L) ?: 500L).coerceIn(250L, 1500L)
                    Thread.sleep(waitMs)
                    continue
                }
                if (bodyString == null) return null
                return JSONObject(bodyString)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                if (e is java.net.UnknownHostException) {
                    // Host cannot be resolved / offline; fail fast without sleeping or retrying
                    return null
                }
                if (attempts >= 3) {
                    e.printStackTrace()
                    return null
                }
            }
        }
        return null
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
            val isrc = obj.optString("isrc").trim().takeIf { it.isNotBlank() }
            tracks.add(
                OnlineCatalogTrack(
                    identity = identity,
                    id = obj.optString("id").ifBlank { "${identity.youtubeSearchQuery()}#$i" },
                    audioUrl = identity.youtubeSearchQuery(),
                    provider = provider,
                    year = parseReleaseYear(obj.optJSONObject("album")?.optString("release_date"))
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
                    provider = provider,
                    year = parseReleaseYear(obj.optString("releaseDate"))
                )
            )
        }
        return tracks
    }

    fun toCatalogCandidate(track: OnlineCatalogTrack): CatalogTrackCandidate =
        CatalogTrackCandidate(identity = track.identity, candidates = listOf(track))

    fun parseDeezerAlbums(
        data: JSONArray?,
        defaultArtist: String? = null
    ): List<CatalogAlbum> {
        if (data == null || data.length() == 0) return emptyList()
        val albums = ArrayList<CatalogAlbum>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val title = obj.optString("title", "").trim().ifEmpty { obj.optString("name", "Álbum") }
            val artistObj = obj.optJSONObject("artist")
            val artist = defaultArtist?.ifBlank { null }
                ?: artistObj?.optString("name", "Artista")
                ?: "Artista"
            albums.add(
                CatalogAlbum(
                    id = obj.optLong("id").toString(),
                    title = title,
                    artist = artist,
                    coverUrl = pickCoverUrl(obj.optString("cover_xl"), obj.optString("cover_big")),
                    trackCount = obj.optInt("nb_tracks", 0)
                )
            )
        }
        return albums
    }

    fun parseItunesAlbums(
        results: JSONArray?,
        limit: Int = Int.MAX_VALUE
    ): List<CatalogAlbum> {
        if (results == null || results.length() == 0 || limit <= 0) return emptyList()
        val albums = ArrayList<CatalogAlbum>(minOf(limit, results.length()))
        for (i in 0 until results.length()) {
            if (albums.size >= limit) break
            val obj = results.getJSONObject(i)
            albums.add(
                CatalogAlbum(
                    id = obj.optLong("collectionId").toString(),
                    title = obj.optString("collectionName", "Álbum"),
                    artist = obj.optString("artistName", "Artista"),
                    coverUrl = normalizeItunesArtwork(obj.optString("artworkUrl100")),
                    trackCount = obj.optInt("trackCount", 0)
                )
            )
        }
        return albums
    }

    private val deezerArtistCache = java.util.concurrent.ConcurrentHashMap<String, DeezerArtistHit>()
    private val onlineCatalogCache = java.util.concurrent.ConcurrentHashMap<String, List<OnlineCatalogTrack>>()
    private val albumSearchCache = java.util.concurrent.ConcurrentHashMap<String, List<CatalogAlbum>>()

    /** Deezer artist search hit (id + picture). Shared by photo URL and artist-id resolve. */
    fun searchDeezerArtist(name: String): DeezerArtistHit? {
        val cleanArtistName = cleanArtist(name)
        if (cleanArtistName.isEmpty()) return null
        val cacheKey = cleanArtistName.lowercase()
        deezerArtistCache[cacheKey]?.let { return it }

        val url = endpoint(
            endpoints.deezerBaseUrl,
            "search/artist?q=${encodeQuery(cleanArtistName)}&limit=1"
        )
        val json = getJson(url) ?: return null
        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val item = data.getJSONObject(0)
        val id = item.optLong("id", 0L)
        if (id <= 0L) return null
        val hit = DeezerArtistHit(
            id = id,
            pictureUrl = pickCoverUrl(item.optString("picture_xl"), item.optString("picture_big"))
        )
        deezerArtistCache[cacheKey] = hit
        return hit
    }

    private fun searchDeezerTrack(queryText: String): TrackIdentity? {
        val url = endpoint(
            endpoints.deezerBaseUrl,
            "search?q=${encodeQuery(queryText)}&limit=1"
        )
        val json = getJson(url) ?: return null
        return parseDeezerTrackArray(json.optJSONArray("data")).firstOrNull()
    }

    private fun searchItunesSong(queryText: String): TrackIdentity? {
        val url = endpoint(
            endpoints.itunesBaseUrl,
            "search?term=${encodeQuery(queryText)}&entity=song&limit=1"
        )
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
        val url = endpoint(
            endpoints.deezerBaseUrl,
            "search/album?q=${encodeQuery(queryText)}&limit=1"
        )
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

    fun parseCatalogGenres(data: JSONArray?): List<CatalogGenre> {
        if (data == null || data.length() == 0) return emptyList()
        val genres = ArrayList<CatalogGenre>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optLong("id", -1L)
            // Deezer id 0 is the synthetic "All" row — skip.
            if (id <= 0L) continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            genres.add(
                CatalogGenre(
                    id = id,
                    name = name,
                    pictureUrl = pickCoverUrl(
                        obj.optString("picture_xl"),
                        obj.optString("picture_big").ifBlank { obj.optString("picture_medium") }
                    )
                )
            )
        }
        return genres
    }

    /** Deezer genre browse list (`GET /genre`). */
    suspend fun listGenres(): List<CatalogGenre> = withContext(Dispatchers.IO) {
        try {
            val json = getJson(
                endpoint(endpoints.deezerBaseUrl, "genre"),
                userAgent = "Mozilla/5.0"
            )
            return@withContext parseCatalogGenres(json?.optJSONArray("data"))
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Global Deezer chart tracks (`GET /chart/0/tracks`). */
    suspend fun fetchChartTracks(limit: Int = 25): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        fetchDeezerChartTracks(chartId = 0L, limit = limit)
    }

    /** Global Deezer chart albums (`GET /chart/0/albums`). */
    suspend fun fetchChartAlbums(limit: Int = 20): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        try {
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "chart/0/albums?limit=$limit"
            )
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            parseDeezerAlbums(data)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Tracks for a Deezer genre: chart-by-genre first, then `search?q=genre:"Name"`.
     * Reuses [parseDeezerSearchTracks] — same download path as song search.
     */
    suspend fun searchTracksByGenre(
        genreId: Long,
        genreName: String,
        limit: Int = 25
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        if (genreId > 0L) {
            val chartTracks = fetchDeezerChartTracks(chartId = genreId, limit = limit)
            if (chartTracks.isNotEmpty()) return@withContext chartTracks
        }
        val cleanName = genreName.trim()
        if (cleanName.isEmpty() || limit <= 0) return@withContext emptyList()
        try {
            val q = "genre:\"$cleanName\""
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "search?q=${encodeQuery(q)}&limit=$limit"
            )
            return@withContext parseDeezerSearchTracks(
                getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun fetchDeezerChartTracks(chartId: Long, limit: Int): List<OnlineCatalogTrack> {
        if (limit <= 0) return emptyList()
        return try {
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "chart/$chartId/tracks?limit=$limit"
            )
            parseDeezerSearchTracks(
                getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun searchOnlineCatalog(
        query: String,
        limit: Int = 25,
        index: Int = 0
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) {
            return@withContext if (index > 0) emptyList() else getFeaturedDemoCatalog()
        }
        val pageLimit = limit.coerceIn(1, 100)
        val pageIndex = index.coerceAtLeast(0)
        val cacheKey = "${cleanQ.lowercase()}|$pageLimit|$pageIndex"
        onlineCatalogCache[cacheKey]?.let { return@withContext it }

        // 1. Deezer Song Search API
        val deezerUrl = endpoint(
            endpoints.deezerBaseUrl,
            "search?q=${encodeQuery(cleanQ)}&limit=$pageLimit&index=$pageIndex"
        )
        val deezerTracks = parseDeezerSearchTracks(
            getJson(deezerUrl, userAgent = "Mozilla/5.0")?.optJSONArray("data")
        )
        if (deezerTracks.isNotEmpty()) {
            if (onlineCatalogCache.size > 500) onlineCatalogCache.clear()
            onlineCatalogCache[cacheKey] = deezerTracks
            return@withContext deezerTracks
        }

        // 2. Fallback to iTunes Song Search API (no offset; skip on subsequent pages)
        if (pageIndex > 0) return@withContext emptyList()
        val itunesUrl = endpoint(
            endpoints.itunesBaseUrl,
            "search?term=${encodeQuery(cleanQ)}&entity=song&limit=$pageLimit"
        )
        val itunesTracks = parseItunesSongResults(
            getJson(itunesUrl, userAgent = "Mozilla/5.0")?.optJSONArray("results")
        )
        if (itunesTracks.isNotEmpty()) {
            if (onlineCatalogCache.size > 500) onlineCatalogCache.clear()
            onlineCatalogCache[cacheKey] = itunesTracks
            return@withContext itunesTracks
        }

        // 3. Fallback to YouTube Search API
        val ytTracks = YouTubeExtractor.searchYouTube(cleanQ)
        if (ytTracks.isNotEmpty()) {
            if (onlineCatalogCache.size > 500) onlineCatalogCache.clear()
            onlineCatalogCache[cacheKey] = ytTracks
        }
        return@withContext ytTracks
    }

    /**
     * Identify-only: Deezer often returns unrelated fuzzy hits for romanized leftover
     * titles, which skips iTunes/YouTube/MusicBrainz. Fetch those providers anyway.
     */
    suspend fun searchIdentifyFallbacks(
        query: String,
        limit: Int = 25,
        durationMs: Long = 0L
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) return@withContext emptyList()
        val pageLimit = limit.coerceIn(1, 100)
        val itunesTracks = buildList {
            addAll(this@MetadataFetcher.searchItunesSongs(cleanQ, pageLimit, country = null))
            addAll(this@MetadataFetcher.searchItunesSongs(cleanQ, pageLimit, country = "JP"))
            addAll(
                if (IdentifyQueryVariants.hasHan(cleanQ)) {
                    this@MetadataFetcher.searchItunesSongs(cleanQ, pageLimit, country = "TW")
                } else {
                    emptyList()
                }
            )
        }
        val mbTracks = try {
            MusicBrainzClient.searchRecordings(
                query = cleanQ,
                durationMs = durationMs.takeIf { it > 0L },
                limit = pageLimit
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        val youtubeTracks = YouTubeExtractor.searchYouTube(cleanQ)
        return@withContext buildList {
            addAll(itunesTracks)
            addAll(mbTracks)
            addAll(youtubeTracks)
        }
    }

    internal fun searchItunesSongs(
        query: String,
        limit: Int,
        country: String? = null
    ): List<OnlineCatalogTrack> {
        val countryParam = if (country.isNullOrBlank()) "" else "&country=$country"
        val itunesUrl = endpoint(
            endpoints.itunesBaseUrl,
            "search?term=${encodeQuery(query)}&entity=song&limit=$limit$countryParam"
        )
        return parseItunesSongResults(
            getJson(itunesUrl, userAgent = "Mozilla/5.0")?.optJSONArray("results")
        )
    }

    /** First 4-digit year from ISO-ish release strings (`2012-03-01`, `2012`). */
    fun parseReleaseYear(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.length < 4) return 0
        val y = s.take(4).toIntOrNull() ?: return 0
        return if (y in 1000..9999) y else 0
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
        val url = endpoint(endpoints.deezerBaseUrl, "artist/$artistId/radio")
        val json = getJson(url) ?: return@withContext emptyList()
        return@withContext parseDeezerTrackArray(json.optJSONArray("data"))
    }

    /** Related Deezer artist ids (for diversity). */
    suspend fun fetchDeezerRelatedArtistIds(artistId: Long, limit: Int = 5): List<Long> =
        withContext(Dispatchers.IO) {
            if (artistId <= 0L || limit <= 0) return@withContext emptyList()
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "artist/$artistId/related?limit=$limit"
            )
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
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "artist/$artistId/top?limit=$limit"
            )
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
            val url = endpoint(
                endpoints.itunesBaseUrl,
                "search?term=${encodeQuery(cleanArtistName)}&entity=song&limit=$limit"
            )
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
        val itunes = searchItunesSong(queryText)
        if (deezer == null && itunes == null) return@withContext null
        if (deezer == null) return@withContext itunes
        if (itunes == null) return@withContext deezer
        return@withContext deezer.mergePreferring(itunes)
    }

    suspend fun fetchLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanString(title)
            val cleanArtistName = cleanArtist(artist)

            if (cleanArtistName.isNotEmpty()) {
                val artistEnc = encodeQuery(cleanArtistName)
                val trackEnc = encodeQuery(cleanTitle)
                val url = endpoint(
                    endpoints.lyricsBaseUrl,
                    "api/get?artist_name=$artistEnc&track_name=$trackEnc"
                )
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
            val searchUrl = endpoint(endpoints.lyricsBaseUrl, "api/search?q=$q")
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

    suspend fun searchAlbums(
        query: String,
        limit: Int = 15,
        index: Int = 0
    ): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "rock hits" }
        val pageLimit = limit.coerceIn(1, 50)
        val pageIndex = index.coerceAtLeast(0)
        val cacheKey = "${cleanQ.lowercase()}|$pageLimit|$pageIndex"
        albumSearchCache[cacheKey]?.let { return@withContext it }
        val list = mutableListOf<CatalogAlbum>()
        try {
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "search/album?q=${encodeQuery(cleanQ)}&limit=$pageLimit&index=$pageIndex"
            )
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            list.addAll(parseDeezerAlbums(data))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to iTunes if Deezer returned empty
        if (list.isEmpty() && pageIndex == 0) {
            try {
                val url = endpoint(
                    endpoints.itunesBaseUrl,
                    "search?term=${encodeQuery(cleanQ)}&entity=album&limit=$pageLimit"
                )
                val results = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("results")
                list.addAll(parseItunesAlbums(results, limit = pageLimit))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (list.isNotEmpty()) {
            if (albumSearchCache.size > 200) albumSearchCache.clear()
            albumSearchCache[cacheKey] = list
        }
        return@withContext list
    }

    /**
     * Fetch discography / online albums of an artist via Deezer artist endpoint or search fallback.
     */
    suspend fun fetchArtistAlbums(
        artistName: String,
        deezerArtistId: Long? = null
    ): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        val cleanArtist = cleanArtist(artistName)
        if (cleanArtist.isEmpty()) return@withContext emptyList()
        val artistId = deezerArtistId?.takeIf { it > 0L } ?: searchDeezerArtist(cleanArtist)?.id
        val list = mutableListOf<CatalogAlbum>()
        if (artistId != null && artistId > 0L) {
            try {
                val url = endpoint(
                    endpoints.deezerBaseUrl,
                    "artist/$artistId/albums?limit=50"
                )
                val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
                list.addAll(parseDeezerAlbums(data, defaultArtist = cleanArtist))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (list.isEmpty()) {
            list.addAll(searchAlbums("artist:\"$cleanArtist\"", limit = 30))
            if (list.isEmpty()) {
                list.addAll(searchAlbums(cleanArtist, limit = 20))
            }
        }
        return@withContext list
    }

    /**
     * Fetch top tracks of an artist via Deezer top endpoint or search fallback.
     */
    suspend fun fetchArtistTopTracks(
        artistName: String,
        deezerArtistId: Long? = null
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleanArtist = cleanArtist(artistName)
        if (cleanArtist.isEmpty()) return@withContext emptyList()
        val artistId = deezerArtistId?.takeIf { it > 0L } ?: searchDeezerArtist(cleanArtist)?.id
        val list = mutableListOf<OnlineCatalogTrack>()
        if (artistId != null && artistId > 0L) {
            try {
                val url = endpoint(
                    endpoints.deezerBaseUrl,
                    "artist/$artistId/top?limit=30"
                )
                val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
                list.addAll(parseDeezerSearchTracks(data))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (list.isEmpty()) {
            list.addAll(searchOnlineCatalog(cleanArtist, limit = 25))
        }
        return@withContext list
    }

    suspend fun searchPlaylists(query: String): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "top hits" }
        val list = mutableListOf<CatalogPlaylist>()
        try {
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "search/playlist?q=${encodeQuery(cleanQ)}&limit=15"
            )
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
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "album/$albumId/tracks?limit=50"
            )
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null && data.length() > 0) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val identity = obj.toDeezerTrackIdentity(
                        defaultTitle = "Pista ${i + 1}",
                        defaultArtist = artistName,
                        defaultAlbum = albumTitle
                    )?.let { base ->
                        base.copy(artworkUri = albumCoverUrl ?: base.artworkUri)
                    } ?: continue
                    val isrc = obj.optString("isrc").trim().takeIf { it.isNotBlank() }
                    resultCandidates.add(
                        toCatalogCandidate(
                            identity.toCatalogTrack(
                                provider = "YouTube",
                                audioUrl = identity.youtubeSearchQuery()
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
                val url = endpoint(
                    endpoints.itunesBaseUrl,
                    "search?term=${encodeQuery(queryTerm)}&entity=song&limit=30"
                )
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
            val url = endpoint(
                endpoints.deezerBaseUrl,
                "playlist/$playlistId/tracks?limit=50"
            )
            val data = getJson(url, userAgent = "Mozilla/5.0")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val identity = obj.toDeezerTrackIdentity(
                        defaultTitle = "Pista ${i + 1}",
                        defaultAlbum = playlistTitle
                    ) ?: continue
                    val isrc = obj.optString("isrc").trim().takeIf { it.isNotBlank() }
                    resultCandidates.add(
                        toCatalogCandidate(
                            identity.toCatalogTrack(
                                provider = "YouTube",
                                audioUrl = identity.youtubeSearchQuery()
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
