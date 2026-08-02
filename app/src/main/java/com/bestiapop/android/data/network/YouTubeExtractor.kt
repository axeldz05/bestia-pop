package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import com.bestiapop.android.data.model.OnlineCatalogTrack

data class YouTubeStreamResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val audioUrl: String,
    val userAgent: String
)

sealed class YouTubeExtractResult {
    data class Success(val result: YouTubeStreamResult) : YouTubeExtractResult()
    data class Error(val message: String) : YouTubeExtractResult()
}

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ClientProfile(
        val name: String,
        val version: String,
        val apiKey: String,
        val userAgent: String,
        val clientId: String,
        val osName: String,
        val osVersion: String,
        val extraContextJson: String?
    )

    // yt-dlp primary TV & Android VR client profiles
    private val TV_EMBED = ClientProfile(
        name = "TVHTML5",
        version = "7.20260707.07.00",
        apiKey = "",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        clientId = "7",
        osName = "TV",
        osVersion = "7.0",
        extraContextJson = null
    )


    private val ANDROID_VR = ClientProfile(
        name = "ANDROID_VR",
        version = "1.65.10",
        apiKey = "",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        clientId = "28",
        osName = "Android",
        osVersion = "12L",
        extraContextJson = """{"deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32}"""
    )

    private val ANDROID_MUSIC = ClientProfile(
        name = "ANDROID_MUSIC",
        version = "7.27.52",
        apiKey = "AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
        userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14)",
        clientId = "21",
        osName = "Android",
        osVersion = "14",
        extraContextJson = """{"androidSdkVersion":34}"""
    )

    private val ANDROID_MAIN = ClientProfile(
        name = "ANDROID",
        version = "20.10.38",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; es_ES; gts8uwifi Build/UP1A.231005.007) gzip",
        clientId = "1",
        osName = "Android",
        osVersion = "14",
        extraContextJson = """{"androidSdkVersion":34}"""
    )

    private val AUDIO_CLIENTS = listOf(TV_EMBED, ANDROID_VR, ANDROID_MUSIC, ANDROID_MAIN)

    fun extractYouTubeId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.length == 11 && Pattern.matches("^[a-zA-Z0-9_-]{11}$", trimmed)) {
            return trimmed
        }
        val regex = "(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/" +
                "|.*[?&]v=)|youtu\\.be\\/|music\\.youtube\\.com\\/watch\\?v=)" +
                "([a-zA-Z0-9_-]{11})"
        val matcher = Pattern.compile(regex).matcher(trimmed)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun formatTitleAndArtist(rawTitle: String, rawAuthor: String): Pair<String, String> {
        var cleanTitle = rawTitle
            .replace(Regex("(?i)\\(Official\\s+(?:Music\\s+)?Video\\)"), "")
            .replace(Regex("(?i)\\[Official\\s+(?:Music\\s+)?Video\\]"), "")
            .replace(Regex("(?i)\\(Official\\s+Audio\\)"), "")
            .replace(Regex("(?i)\\[Official\\s+Audio\\]"), "")
            .replace(Regex("(?i)\\(Video\\)"), "")
            .replace(Regex("(?i)\\[Lyrics?\\]"), "")
            .replace(Regex("(?i)\\(Lyrics?\\)"), "")
            .replace(Regex("(?i)HD|4K"), "")
            .trim()

        var artist = rawAuthor.replace(" - Topic", "").replace("VEVO", "").trim()

        if (cleanTitle.contains(" - ")) {
            val parts = cleanTitle.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty() && parts[1].trim().isNotEmpty()) {
                artist = parts[0].trim()
                cleanTitle = parts[1].trim()
            }
        }
        return Pair(cleanTitle, artist.ifEmpty { "YouTube Artist" })
    }

    suspend fun searchYouTube(query: String): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineCatalogTrack>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext results

        // 1. InnerTube API Search (/youtubei/v1/search)
        try {
            val clientCtx = JSONObject().apply {
                put("clientName", ANDROID_VR.name)
                put("clientVersion", ANDROID_VR.version)
                put("hl", "es")
                put("gl", "US")
                put("userAgent", ANDROID_VR.userAgent)
                put("osName", ANDROID_VR.osName)
                put("osVersion", ANDROID_VR.osVersion)
            }

            val bodyJson = JSONObject().apply {
                put("context", JSONObject().put("client", clientCtx))
                put("query", trimmed)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search")
                .header("X-YouTube-Client-Name", ANDROID_VR.clientId)
                .header("X-YouTube-Client-Version", ANDROID_VR.version)
                .header("User-Agent", ANDROID_VR.userAgent)
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    val json = JSONObject(bodyStr)

                    val contents = json.optJSONObject("contents")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?: json.optJSONObject("contents")
                            ?.optJSONObject("twoColumnSearchResultsRenderer")
                            ?.optJSONObject("primaryContents")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")

                    if (contents != null) {
                        for (i in 0 until contents.length()) {
                            val section = contents.getJSONObject(i).optJSONObject("itemSectionRenderer") ?: continue
                            val items = section.optJSONArray("contents") ?: continue

                            for (j in 0 until items.length()) {
                                val item = items.getJSONObject(j)
                                val videoRenderer = item.optJSONObject("compactVideoRenderer")
                                    ?: item.optJSONObject("videoRenderer") ?: continue
                                val videoId = videoRenderer.optString("videoId") ?: continue
                                if (videoId.isEmpty()) continue

                                val rawTitle = videoRenderer.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                    ?: videoRenderer.optJSONObject("title")?.optString("simpleText", "YouTube Video") ?: "YouTube Video"

                                val rawAuthor = videoRenderer.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                    ?: videoRenderer.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                    ?: "YouTube Artist"

                                val (cleanTitle, cleanArtist) = formatTitleAndArtist(rawTitle, rawAuthor)

                                var artworkUrl: String? = null
                                val thumbArray = videoRenderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                if (thumbArray != null && thumbArray.length() > 0) {
                                    artworkUrl = thumbArray.getJSONObject(thumbArray.length() - 1).optString("url")
                                }

                                val lengthText = videoRenderer.optJSONObject("lengthText")?.optString("simpleText", "") ?: ""
                                val durationMs = parseDurationTextToMs(lengthText)

                                results.add(
                                    OnlineCatalogTrack(
                                        id = videoId,
                                        title = cleanTitle,
                                        artist = cleanArtist,
                                        album = "YouTube",
                                        artworkUrl = artworkUrl,
                                        durationMs = durationMs,
                                        audioUrl = "https://www.youtube.com/watch?v=$videoId",
                                        provider = "YouTube"
                                    )
                                )

                                if (results.size >= 25) break
                            }
                            if (results.isNotEmpty()) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to HTML Scraping if InnerTube returned empty
        if (results.isEmpty()) {
            try {
                val encodedQ = java.net.URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://www.youtube.com/results?search_query=$encodedQ"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string() ?: ""
                        val p = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>")
                        val m = p.matcher(html)
                        if (m.find()) {
                            val jsonStr = m.group(1) ?: ""
                            val data = JSONObject(jsonStr)
                            val contents = data.optJSONObject("contents")
                                ?.optJSONObject("twoColumnSearchResultsRenderer")
                                ?.optJSONObject("primaryContents")
                                ?.optJSONObject("sectionListRenderer")
                                ?.optJSONArray("contents")

                            if (contents != null) {
                                for (i in 0 until contents.length()) {
                                    val sec = contents.getJSONObject(i).optJSONObject("itemSectionRenderer") ?: continue
                                    val items = sec.optJSONArray("contents") ?: continue
                                    for (j in 0 until items.length()) {
                                        val item = items.getJSONObject(j)
                                        val video = item.optJSONObject("videoRenderer")
                                            ?: item.optJSONObject("compactVideoRenderer") ?: continue
                                        val vidId = video.optString("videoId")
                                        if (vidId.isEmpty()) continue

                                        val rawTitle = video.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                            ?: video.optJSONObject("title")?.optString("simpleText", "YouTube Video") ?: "YouTube Video"

                                        val rawAuthor = video.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                            ?: "YouTube Artist"

                                        val (cleanTitle, cleanArtist) = formatTitleAndArtist(rawTitle, rawAuthor)

                                        var artworkUrl: String? = null
                                        val thumbArray = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                        if (thumbArray != null && thumbArray.length() > 0) {
                                            artworkUrl = thumbArray.getJSONObject(thumbArray.length() - 1).optString("url")
                                        }

                                        results.add(
                                            OnlineCatalogTrack(
                                                id = vidId,
                                                title = cleanTitle,
                                                artist = cleanArtist,
                                                album = "YouTube",
                                                artworkUrl = artworkUrl,
                                                durationMs = 180000L,
                                                audioUrl = "https://www.youtube.com/watch?v=$vidId",
                                                provider = "YouTube"
                                            )
                                        )
                                        if (results.size >= 25) break
                                    }
                                    if (results.isNotEmpty()) break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext results
    }


    private fun parseDurationTextToMs(durStr: String): Long {
        if (durStr.isBlank()) return 180000L
        val parts = durStr.split(":")
        return try {
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000L
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000L
                else -> 180000L
            }
        } catch (e: Exception) {
            180000L
        }
    }

    suspend fun extractAudioStream(urlOrQuery: String): YouTubeStreamResult? {
        val res = extractAudioStreamDetailed(urlOrQuery)
        return if (res is YouTubeExtractResult.Success) res.result else null
    }

    suspend fun extractAudioStreamDetailed(urlOrQuery: String): YouTubeExtractResult = withContext(Dispatchers.IO) {
        val trimmed = urlOrQuery.trim()

        val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (isUrl && !trimmed.contains("youtube.com") && !trimmed.contains("youtu.be")) {
            return@withContext YouTubeExtractResult.Error("Solo se pueden procesar enlaces provenientes de YouTube (youtube.com o youtu.be)")
        }

        var videoId = extractYouTubeId(trimmed)
        if (videoId == null && isUrl) {
            return@withContext YouTubeExtractResult.Error("El enlace ingresado no contiene un ID de video de YouTube válido")
        }

        if (videoId == null) {
            val searchResults = searchYouTube(trimmed)
            if (searchResults.isNotEmpty()) {
                videoId = searchResults.first().id
            }
        }

        if (videoId == null) {
            return@withContext YouTubeExtractResult.Error("No se encontró ningún video en YouTube para la búsqueda ingresada")
        }

        var lastErrorReason = ""

        for (clientProfile in AUDIO_CLIENTS) {
            try {
                val (res, reason) = callPlayerApi(clientProfile, videoId)
                if (res != null) {
                    return@withContext YouTubeExtractResult.Success(res)
                }
                if (!reason.isNullOrBlank()) {
                    lastErrorReason = reason
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalErrorMsg = if (lastErrorReason.isNotBlank()) {
            "El video de YouTube no está disponible ($lastErrorReason)"
        } else {
            "No se pudo extraer la pista de audio de este video de YouTube"
        }

        return@withContext YouTubeExtractResult.Error(finalErrorMsg)
    }

    @Volatile
    private var cachedVisitorData: String? = null

    private fun fetchVisitorData(videoId: String): String? {
        cachedVisitorData?.let { return it }
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val regex = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")
                    val match = regex.find(html)
                    val vData = match?.groupValues?.get(1)
                    if (vData != null) {
                        cachedVisitorData = vData
                    }
                    vData
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun callPlayerApi(clientProfile: ClientProfile, videoId: String): Pair<YouTubeStreamResult?, String?> {
        val endpoint = if (clientProfile.apiKey.isEmpty()) {
            "https://www.youtube.com/youtubei/v1/player"
        } else {
            "https://youtubei.googleapis.com/youtubei/v1/player?key=${clientProfile.apiKey}"
        }

        val visitorData = fetchVisitorData(videoId)

        val clientCtx = JSONObject().apply {
            put("clientName", clientProfile.name)
            put("clientVersion", clientProfile.version)
            put("hl", "es")
            put("gl", "US")
            put("userAgent", clientProfile.userAgent)
            put("osName", clientProfile.osName)
            put("osVersion", clientProfile.osVersion)
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
            clientProfile.extraContextJson?.let {
                val extraObj = JSONObject(it)
                val keys = extraObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, extraObj.get(k))
                }
            }
        }


        val bodyJson = JSONObject().apply {
            put("context", JSONObject().put("client", clientCtx))
            put("videoId", videoId)
            put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("X-YouTube-Client-Name", clientProfile.clientId)
            .header("X-YouTube-Client-Version", clientProfile.version)
            .header("User-Agent", clientProfile.userAgent)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Pair(null, "HTTP ${response.code}")
            val bodyString = response.body?.string() ?: return Pair(null, "Respuesta vacía")
            val json = JSONObject(bodyString)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != null && status != "OK") {
                val reason = playability.optString("reason", status)
                return Pair(null, reason)
            }

            val videoDetails = json.optJSONObject("videoDetails") ?: return Pair(null, "Detalles de video no encontrados")
            val rawTitle = videoDetails.optString("title", "YouTube Track")
            val rawAuthor = videoDetails.optString("author", "YouTube Artist")
            val (title, author) = formatTitleAndArtist(rawTitle, rawAuthor)
            val durationSec = videoDetails.optString("lengthSeconds", "180").toLongOrNull() ?: 180L

            var thumbUrl: String? = null
            val thumbObj = videoDetails.optJSONObject("thumbnail")
            val thumbArr = thumbObj?.optJSONArray("thumbnails")
            if (thumbArr != null && thumbArr.length() > 0) {
                thumbUrl = thumbArr.getJSONObject(thumbArr.length() - 1).optString("url")
            }

            val streamingData = json.optJSONObject("streamingData") ?: return Pair(null, "Formatos de streaming no disponibles")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val regularFormats = streamingData.optJSONArray("formats")

            var bestAudioUrl: String? = null
            var highestBitrate = 0
            var isMp4Selected = false

            fun checkFormatArray(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val fmt = arr.getJSONObject(i)
                    val url = fmt.optString("url")
                    val mime = fmt.optString("mimeType")
                    val bitrate = fmt.optInt("bitrate", 0)

                    if (url.isEmpty()) continue

                    val isMp4Audio = mime.contains("audio/mp4") || mime.contains("mp4a")
                    val isGeneralAudio = mime.contains("audio/") || mime.contains("video/mp4")

                    if (!isGeneralAudio) continue

                    // Prioritize AAC/m4a audio (audio/mp4) over WebM/Opus for 100% ExoPlayer native compatibility
                    if (isMp4Audio) {
                        if (!isMp4Selected || bitrate > highestBitrate) {
                            isMp4Selected = true
                            highestBitrate = bitrate
                            bestAudioUrl = url
                        }
                    } else if (!isMp4Selected) {
                        if (bitrate > highestBitrate) {
                            highestBitrate = bitrate
                            bestAudioUrl = url
                        }
                    }
                }
            }

            checkFormatArray(adaptiveFormats)
            if (bestAudioUrl == null) {
                checkFormatArray(regularFormats)
            }


            if (bestAudioUrl != null) {
                val streamResult = YouTubeStreamResult(
                    videoId = videoId,
                    title = title,
                    artist = author,
                    artworkUrl = thumbUrl,
                    durationMs = durationSec * 1000L,
                    audioUrl = bestAudioUrl!!,
                    userAgent = clientProfile.userAgent
                )
                return Pair(streamResult, null)
            }
        }
        return Pair(null, "No se encontraron URLs de audio sin descifrar")
    }
}
