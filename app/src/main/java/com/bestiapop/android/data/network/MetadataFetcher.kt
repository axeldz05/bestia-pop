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
}
