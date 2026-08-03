package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class FullTrackMetadata(
    val album: String?,
    val artworkUrl: String?,
    val artistName: String?,
    val title: String?
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

    suspend fun getFeaturedDemoCatalog(): List<com.bestiapop.android.data.model.OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val tracks = searchOnlineCatalog("rock hits")
        if (tracks.isNotEmpty()) {
            return@withContext tracks
        }
        return@withContext searchOnlineCatalog("top songs")
    }

    suspend fun searchOnlineCatalog(query: String): List<com.bestiapop.android.data.model.OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) {
            return@withContext getFeaturedDemoCatalog()
        }
        val tracks = mutableListOf<com.bestiapop.android.data.model.OnlineCatalogTrack>()

        // 1. Deezer Song Search API
        try {
            val encoded = URLEncoder.encode(cleanQ, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search?q=$encoded&limit=25"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val title = obj.optString("title", "Canción")
                            val artistObj = obj.optJSONObject("artist")
                            val artistName = artistObj?.optString("name", "Artista") ?: "Artista"
                            val albumObj = obj.optJSONObject("album")
                            val albumTitle = albumObj?.optString("title", "Álbum") ?: "Álbum"
                            val coverXl = albumObj?.optString("cover_xl")?.ifEmpty { albumObj.optString("cover_big") }

                            tracks.add(
                                com.bestiapop.android.data.model.OnlineCatalogTrack(
                                    id = "$artistName $title",
                                    title = title,
                                    artist = artistName,
                                    album = albumTitle,
                                    artworkUrl = coverXl,
                                    durationMs = obj.optLong("duration", 180L) * 1000L,
                                    audioUrl = "$artistName $title",
                                    provider = "Deezer/YouTube"
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to iTunes Song Search API if Deezer returned empty
        if (tracks.isEmpty()) {
            try {
                val encoded = URLEncoder.encode(cleanQ, StandardCharsets.UTF_8.name())
                val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=25"
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                val title = obj.optString("trackName", "Canción")
                                val artistName = obj.optString("artistName", "Artista")
                                val albumTitle = obj.optString("collectionName", "Álbum")
                                val artwork100 = obj.optString("artworkUrl100").replace("100x100bb", "600x600bb")

                                tracks.add(
                                    com.bestiapop.android.data.model.OnlineCatalogTrack(
                                        id = "$artistName $title",
                                        title = title,
                                        artist = artistName,
                                        album = albumTitle,
                                        artworkUrl = artwork100.ifBlank { null },
                                        durationMs = obj.optLong("trackTimeMillis", 180000L),
                                        audioUrl = "$artistName $title",
                                        provider = "iTunes/YouTube"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Fallback to YouTube Search API if both returned empty
        if (tracks.isEmpty()) {
            return@withContext YouTubeExtractor.searchYouTube(cleanQ)
        }

        return@withContext tracks
    }



    suspend fun fetchArtistPhotoUrl(artist: String): String? = withContext(Dispatchers.IO) {
        val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)
        if (cleanArtist.isEmpty()) return@withContext null

        try {
            val query = URLEncoder.encode(cleanArtist, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search/artist?q=$query&limit=1"
            val request = Request.Builder().url(url).header("User-Agent", "BestiaPop/1.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        val pictureXl = item.optString("picture_xl")
                        val pictureBig = item.optString("picture_big")
                        if (pictureXl.isNotEmpty()) return@withContext pictureXl
                        if (pictureBig.isNotEmpty()) return@withContext pictureBig
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanString(titleOrAlbum)
        val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)
        val queryText = if (cleanArtist.isNotEmpty()) "$cleanArtist $cleanTitle" else cleanTitle
        if (queryText.isEmpty()) return@withContext null

        // 1. Try Deezer Search API first (1000x1000 ultra HD)
        try {
            val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search/album?q=$query&limit=1"
            val request = Request.Builder().url(url).header("User-Agent", "BestiaPop/1.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        val coverXl = item.optString("cover_xl")
                        val coverBig = item.optString("cover_big")
                        if (coverXl.isNotEmpty()) return@withContext coverXl
                        if (coverBig.isNotEmpty()) return@withContext coverBig
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to iTunes Search API (600x600 HD)
        try {
            val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val artworkUrl100 = item.optString("artworkUrl100")
                        if (artworkUrl100.isNotEmpty()) {
                            return@withContext artworkUrl100.replace("100x100bb", "600x600bb")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext null
    }

    suspend fun fetchFullTrackMetadata(artist: String, title: String): FullTrackMetadata? = withContext(Dispatchers.IO) {
        val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)
        val cleanTitle = cleanString(title)
        val queryText = if (cleanArtist.isNotEmpty()) "$cleanArtist $cleanTitle" else cleanTitle
        if (queryText.isEmpty()) return@withContext null

        var album: String? = null
        var artworkUrl: String? = null
        var foundArtist: String? = null
        var foundTitle: String? = null

        // 1. Deezer Track Search API
        try {
            val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search?q=$query&limit=1"
            val request = Request.Builder().url(url).header("User-Agent", "BestiaPop/1.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        foundTitle = item.optString("title").ifBlank { null }
                        val artistObj = item.optJSONObject("artist")
                        foundArtist = artistObj?.optString("name")?.ifBlank { null }
                        val albumObj = item.optJSONObject("album")
                        album = albumObj?.optString("title")?.ifBlank { null }
                        artworkUrl = albumObj?.optString("cover_xl")?.ifEmpty { albumObj.optString("cover_big") }?.ifBlank { null }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to iTunes Track Search API
        if (album.isNullOrEmpty()) {
            try {
                val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
                val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val item = results.getJSONObject(0)
                            if (album.isNullOrEmpty()) album = item.optString("collectionName").ifBlank { null }
                            if (artworkUrl.isNullOrEmpty()) {
                                val art = item.optString("artworkUrl100")
                                if (art.isNotEmpty()) artworkUrl = art.replace("100x100bb", "600x600bb")
                            }
                            if (foundArtist.isNullOrEmpty()) foundArtist = item.optString("artistName").ifBlank { null }
                            if (foundTitle.isNullOrEmpty()) foundTitle = item.optString("trackName").ifBlank { null }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext FullTrackMetadata(
            album = album,
            artworkUrl = artworkUrl,
            artistName = foundArtist,
            title = foundTitle,
        )
    }

    suspend fun fetchLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanString(title)
            val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)

            if (cleanArtist.isNotEmpty()) {
                val artistEnc = URLEncoder.encode(cleanArtist, StandardCharsets.UTF_8.name())
                val trackEnc = URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8.name())
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

            // Fallback: General search query on LrcLib
            val q = URLEncoder.encode(if (cleanArtist.isNotEmpty()) "$cleanArtist $cleanTitle" else cleanTitle, StandardCharsets.UTF_8.name())
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
        val cleanTitle = cleanString(title)
        val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanString(artist)
        val queryText = if (cleanArtist.isNotEmpty()) "$cleanArtist $cleanTitle" else cleanTitle
        if (queryText.isEmpty()) return@withContext 0L

        try {
            val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search?q=$query&limit=1"
            val request = Request.Builder().url(url).header("User-Agent", "BestiaPop/1.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val item = data.getJSONObject(0)
                        val durationSec = item.optLong("duration", 0L)
                        if (durationSec > 0) return@withContext durationSec * 1000L
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val trackTimeMillis = item.optLong("trackTimeMillis", 0L)
                        if (trackTimeMillis > 0) return@withContext trackTimeMillis
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext 0L
    }

    suspend fun searchAlbums(query: String): List<com.bestiapop.android.data.model.CatalogAlbum> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "rock hits" }
        val list = mutableListOf<com.bestiapop.android.data.model.CatalogAlbum>()
        try {
            val encoded = URLEncoder.encode(cleanQ, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search/album?q=$encoded&limit=15"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val id = obj.optLong("id").toString()
                            val title = obj.optString("title", "Álbum")
                            val artistObj = obj.optJSONObject("artist")
                            val artistName = artistObj?.optString("name", "Artista") ?: "Artista"
                            val coverXl = obj.optString("cover_xl").ifEmpty { obj.optString("cover_big") }
                            val nbTracks = obj.optInt("nb_tracks", 0)

                            list.add(
                                com.bestiapop.android.data.model.CatalogAlbum(
                                    id = id,
                                    title = title,
                                    artist = artistName,
                                    coverUrl = coverXl.ifBlank { null },
                                    trackCount = nbTracks
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to iTunes if Deezer returned empty
        if (list.isEmpty()) {
            try {
                val encoded = URLEncoder.encode(cleanQ, StandardCharsets.UTF_8.name())
                val url = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=15"
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                val id = obj.optLong("collectionId").toString()
                                val title = obj.optString("collectionName", "Álbum")
                                val artistName = obj.optString("artistName", "Artista")
                                val artwork100 = obj.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                                val trackCount = obj.optInt("trackCount", 0)

                                list.add(
                                    com.bestiapop.android.data.model.CatalogAlbum(
                                        id = id,
                                        title = title,
                                        artist = artistName,
                                        coverUrl = artwork100.ifBlank { null },
                                        trackCount = trackCount
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext list
    }

    suspend fun searchPlaylists(query: String): List<com.bestiapop.android.data.model.CatalogPlaylist> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim().ifEmpty { "top hits" }
        val list = mutableListOf<com.bestiapop.android.data.model.CatalogPlaylist>()
        try {
            val encoded = URLEncoder.encode(cleanQ, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search/playlist?q=$encoded&limit=15"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val id = obj.optLong("id").toString()
                            val title = obj.optString("title", "Playlist")
                            val userObj = obj.optJSONObject("user")
                            val creator = userObj?.optString("name", "Deezer User") ?: "Deezer User"
                            val pictureXl = obj.optString("picture_xl").ifEmpty { obj.optString("picture_big") }
                            val nbTracks = obj.optInt("nb_tracks", 0)

                            list.add(
                                com.bestiapop.android.data.model.CatalogPlaylist(
                                    id = id,
                                    title = title,
                                    creator = creator,
                                    coverUrl = pictureXl.ifBlank { null },
                                    trackCount = nbTracks
                                )
                            )
                        }
                    }
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
    ): List<com.bestiapop.android.data.model.CatalogTrackCandidate> = withContext(Dispatchers.IO) {
        val resultCandidates = mutableListOf<com.bestiapop.android.data.model.CatalogTrackCandidate>()
        try {
            val url = "https://api.deezer.com/album/$albumId/tracks?limit=50"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val trackTitle = obj.optString("title", "Pista ${i + 1}")
                            val trackArtistObj = obj.optJSONObject("artist")
                            val trackArtist = trackArtistObj?.optString("name", artistName) ?: artistName

                            val initialTrack = com.bestiapop.android.data.model.OnlineCatalogTrack(
                                id = "$trackArtist $trackTitle",
                                title = trackTitle,
                                artist = trackArtist,
                                album = albumTitle,
                                artworkUrl = albumCoverUrl,
                                durationMs = obj.optLong("duration", 180L) * 1000L,
                                audioUrl = "$trackArtist $trackTitle",
                                provider = "YouTube"
                            )

                            resultCandidates.add(
                                com.bestiapop.android.data.model.CatalogTrackCandidate(
                                    trackTitle = trackTitle,
                                    artist = trackArtist,
                                    albumName = albumTitle,
                                    coverUrl = albumCoverUrl,
                                    candidates = listOf(initialTrack),
                                    currentCandidateIndex = 0,
                                    isSelected = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to iTunes song search if Deezer album tracks returned empty
        if (resultCandidates.isEmpty()) {
            try {
                val queryTerm = "$artistName $albumTitle".trim()
                val encoded = URLEncoder.encode(queryTerm, StandardCharsets.UTF_8.name())
                val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=30"
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                val trackTitle = obj.optString("trackName", "Pista ${i + 1}")
                                val trackArtist = obj.optString("artistName", artistName)
                                val albumName = obj.optString("collectionName", albumTitle)
                                val artwork100 = obj.optString("artworkUrl100").replace("100x100bb", "600x600bb").ifBlank { albumCoverUrl }

                                val initialTrack = com.bestiapop.android.data.model.OnlineCatalogTrack(
                                    id = "$trackArtist $trackTitle",
                                    title = trackTitle,
                                    artist = trackArtist,
                                    album = albumName,
                                    artworkUrl = artwork100,
                                    durationMs = obj.optLong("trackTimeMillis", 180000L),
                                    audioUrl = "$trackArtist $trackTitle",
                                    provider = "YouTube"
                                )

                                resultCandidates.add(
                                    com.bestiapop.android.data.model.CatalogTrackCandidate(
                                        trackTitle = trackTitle,
                                        artist = trackArtist,
                                        albumName = albumName,
                                        coverUrl = artwork100,
                                        candidates = listOf(initialTrack),
                                        currentCandidateIndex = 0,
                                        isSelected = true
                                    )
                                )
                            }
                        }
                    }
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
    ): List<com.bestiapop.android.data.model.CatalogTrackCandidate> = withContext(Dispatchers.IO) {
        val resultCandidates = mutableListOf<com.bestiapop.android.data.model.CatalogTrackCandidate>()
        try {
            val url = "https://api.deezer.com/playlist/$playlistId/tracks?limit=50"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val trackTitle = obj.optString("title", "Pista ${i + 1}")
                            val trackArtistObj = obj.optJSONObject("artist")
                            val trackArtist = trackArtistObj?.optString("name", "Artista") ?: "Artista"
                            val albumObj = obj.optJSONObject("album")
                            val albumName = albumObj?.optString("title", playlistTitle) ?: playlistTitle
                            val coverXl = albumObj?.optString("cover_xl")?.ifEmpty { albumObj.optString("cover_big") }


                            val initialTrack = com.bestiapop.android.data.model.OnlineCatalogTrack(
                                id = "$trackArtist $trackTitle",
                                title = trackTitle,
                                artist = trackArtist,
                                album = albumName,
                                artworkUrl = coverXl,
                                durationMs = obj.optLong("duration", 180L) * 1000L,
                                audioUrl = "$trackArtist $trackTitle",
                                provider = "YouTube"
                            )

                            resultCandidates.add(
                                com.bestiapop.android.data.model.CatalogTrackCandidate(
                                    trackTitle = trackTitle,
                                    artist = trackArtist,
                                    albumName = albumName,
                                    coverUrl = coverXl,
                                    candidates = listOf(initialTrack),
                                    currentCandidateIndex = 0,
                                    isSelected = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext resultCandidates
    }

}

