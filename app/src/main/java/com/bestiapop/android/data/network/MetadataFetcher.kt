package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object MetadataFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAlbumArtUrl(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val item = results.getJSONObject(0)
                    val artworkUrl100 = item.optString("artworkUrl100")
                    if (artworkUrl100.isNotEmpty()) {
                        // Request high res image (600x600)
                        return@withContext artworkUrl100.replace("100x100bb", "600x600bb")
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
            val artistEnc = URLEncoder.encode(artist, StandardCharsets.UTF_8.name())
            val trackEnc = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
            val url = "https://lrclib.net/api/get?artist_name=$artistEnc&track_name=$trackEnc"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BestiaPop/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val syncedLyrics = json.optString("syncedLyrics")
                    val plainLyrics = json.optString("plainLyrics")
                    return@withContext syncedLyrics.ifEmpty { plainLyrics.ifEmpty { null } }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
