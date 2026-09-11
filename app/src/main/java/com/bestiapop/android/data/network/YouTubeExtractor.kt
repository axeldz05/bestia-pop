package com.bestiapop.android.data.network

import kotlinx.coroutines.CancellationException
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
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.data.util.CrashReporter

data class YouTubeStreamResult(
    val identity: TrackIdentity,
    val videoId: String,
    val audioUrl: String,
    val userAgent: String
) : TrackMeta by identity {
    companion object {
        /** L2: flat stream construction (identity is Level 1). */
        operator fun invoke(
            videoId: String,
            title: String,
            artist: String = "",
            artworkUrl: String? = null,
            durationMs: Long = 0L,
            audioUrl: String,
            userAgent: String
        ): YouTubeStreamResult = YouTubeStreamResult(
            identity = TrackIdentity(
                title = title,
                artist = artist,
                artworkUri = artworkUrl,
                durationMs = durationMs
            ),
            videoId = videoId,
            audioUrl = audioUrl,
            userAgent = userAgent
        )
    }
}

sealed class YouTubeExtractResult {
    data class Success(val result: YouTubeStreamResult) : YouTubeExtractResult()
    data class Error(val message: String) : YouTubeExtractResult()
}

internal data class YouTubeEndpoints(
    val webBaseUrl: String = "https://www.youtube.com",
    val googleApiBaseUrl: String = "https://youtubei.googleapis.com"
)

object YouTubeExtractor {

    private val defaultClient = HttpClients.api.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    @Volatile
    private var client: OkHttpClient = defaultClient
    @Volatile
    private var endpoints = YouTubeEndpoints()

    internal fun configureForTest(
        http: OkHttpClient,
        endpoints: YouTubeEndpoints
    ) {
        client = http
        this.endpoints = endpoints
        cachedVisitorData = null
    }

    internal fun resetTestOverrides() {
        client = defaultClient
        endpoints = YouTubeEndpoints()
        cachedVisitorData = null
    }

    private fun endpoint(baseUrl: String, pathAndQuery: String): String =
        "${baseUrl.trimEnd('/')}/${pathAndQuery.trimStart('/')}"

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

    // yt-dlp: TVHTML5 7.x is SABR-only; 5.x still returns HTTPS URLs without PO token.
    private val TV_DOWNGRADED = ClientProfile(
        name = "TVHTML5",
        version = "5.20260707",
        apiKey = "",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        clientId = "7",
        osName = "TV",
        osVersion = "5.0",
        extraContextJson = null
    )

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

    private val VISION_OS = ClientProfile(
        name = "VISIONOS",
        version = "1.02",
        apiKey = "",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        clientId = "101",
        osName = "visionOS",
        osVersion = "26.5.23O471",
        extraContextJson = """{"deviceMake":"Apple","deviceModel":"RealityDevice17,1"}"""
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
        version = "21.26.364",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        clientId = "3",
        osName = "Android",
        osVersion = "11",
        extraContextJson = """{"androidSdkVersion":30}"""
    )

    // ANDROID_VR 1.65.10 omitted: since 2026.08.17 those URLs 403 after ~1MB.
    private val AUDIO_CLIENTS = listOf(TV_DOWNGRADED, VISION_OS, ANDROID_MAIN, ANDROID_MUSIC, TV_EMBED)

    private val AUDIO_ONLY_TITLE = Regex(
        """(?i)(?:\b(?:official\s+)?audio\b|\baudio\s+oficial\b|\báudio\s+oficial\b)"""
    )
    private val LYRICS_TITLE = Regex("""(?i)\b(?:lyrics?|letra(?:s)?)\b""")
    private val VISUALIZER_TITLE = Regex("""(?i)\bvisuali[sz]er\b""")
    private val MUSIC_VIDEO_TITLE = Regex(
        """(?i)(?:official\s+(?:music\s+)?video|music\s*video|\bm\s*/\s*v\b|\bmv\b|\(video\)|\[video\])"""
    )
    private val LIVE_TITLE = Regex("""(?i)\b(?:live|concert|performance|session)\b""")
    private val COVER_OR_NOISE_TITLE = Regex(
        """(?i)\b(?:cover|karaoke|react(?:ion)?s?|mashup)\b"""
    )
    private val ISRC_REGEX = Regex("""^[A-Z]{2}[A-Z0-9]{3}\d{7}$""")
    private val SNIPPET_OR_PART_TITLE = Regex(
        """(?i)\b(?:(?:best|end|intro|first|second)\s+part|looped?|loop|parts?|snippet|shorts?|clip|preview|sample|edit|sped\s*up|slowed(?:\s*\+\s*reverb)?|nightcore|reverb|8d\s*audio|ringtone)\b"""
    )

    private val YOUTUBE_ID_EXACT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$")
    private val YOUTUBE_URL_PATTERN = Pattern.compile(
        "(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/" +
            "|.*[?&]v=)|youtu\\.be\\/|music\\.youtube\\.com\\/watch\\?v=)" +
            "([a-zA-Z0-9_-]{11})"
    )

    private val OFFICIAL_MUSIC_VIDEO_PAREN = Regex("""(?i)\(Official\s+(?:Music\s+)?Video\)""")
    private val OFFICIAL_MUSIC_VIDEO_BRACKET = Regex("""(?i)\[Official\s+(?:Music\s+)?Video\]""")
    private val OFFICIAL_AUDIO_PAREN = Regex("""(?i)\(Official\s+Audio\)""")
    private val OFFICIAL_AUDIO_BRACKET = Regex("""(?i)\[Official\s+Audio\]""")
    private val VIDEO_PAREN = Regex("""(?i)\(Video\)""")
    private val LYRICS_BRACKET = Regex("""(?i)\[Lyrics?\]""")
    private val LYRICS_PAREN = Regex("""(?i)\(Lyrics?\)""")
    private val HD_4K = Regex("""(?i)\s*[\(\[]\s*(?:HD|4K)\s*[\)\]]|\b(?:HD|4K)\b""")

    private val FALLBACK_PAREN = Regex("""(?i)\(.*?(?:remaster|version|edition|deluxe|feat).*?\)""")
    private val FALLBACK_BRACKET = Regex("""(?i)\[.*?(?:remaster|version|edition|deluxe|feat).*?\]""")
    private val NON_ALPHANUM_SPACE = Regex("""[^a-zA-Z0-9\s]""")

    private val VISITOR_DATA_REGEX = Regex(""""visitorData"\s*:\s*"([^"]+)"""")

    fun extractYouTubeId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.length == 11 && YOUTUBE_ID_EXACT_PATTERN.matcher(trimmed).matches()) {
            return trimmed
        }
        val matcher = YOUTUBE_URL_PATTERN.matcher(trimmed)
        return if (matcher.find()) matcher.group(1) else null
    }

    private val YOUTUBE_TITLE_SEPARATOR = Regex("""\s*[\-–—:|~]\s+|\s+[\-–—]\s*|_-_""")

    private fun isTrackNumberPrefix(s: String): Boolean {
        val trimmed = s.trim()
        return trimmed.isNotEmpty() && (trimmed.all { it.isDigit() } || trimmed.matches(Regex("""^(?:track\s*)?\d{1,3}\.?$""", RegexOption.IGNORE_CASE)))
    }

    fun formatTitleAndArtist(rawTitle: String, rawAuthor: String): Pair<String, String> {
        var cleanTitle = rawTitle
            .replace(OFFICIAL_MUSIC_VIDEO_PAREN, "")
            .replace(OFFICIAL_MUSIC_VIDEO_BRACKET, "")
            .replace(OFFICIAL_AUDIO_PAREN, "")
            .replace(OFFICIAL_AUDIO_BRACKET, "")
            .replace(VIDEO_PAREN, "")
            .replace(LYRICS_BRACKET, "")
            .replace(LYRICS_PAREN, "")
            .replace(HD_4K, "")
            .trim()

        var artist = rawAuthor
            .replace(" - Topic", "")
            .replace("VEVO", "", ignoreCase = true)
            .trim()

        val sepMatch = YOUTUBE_TITLE_SEPARATOR.find(cleanTitle)
        if (sepMatch != null) {
            val part0 = cleanTitle.substring(0, sepMatch.range.first).trim()
            val part1 = cleanTitle.substring(sepMatch.range.last + 1).trim()
            val sepChar = sepMatch.value.trim()
            val isWeakSeparator = sepChar == ":" || sepChar == "|" || sepChar == "~"

            if (part0.isNotEmpty() && part1.isNotEmpty()) {
                val normAuthor = TrackMatchKeys.normalize(artist)
                val normPart0 = TrackMatchKeys.normalize(part0)
                val normPart1 = TrackMatchKeys.normalize(part1)

                val part0MatchesAuthor = normAuthor.isNotEmpty() && (normPart0 == normAuthor || normPart0.startsWith(normAuthor))
                val part1MatchesAuthor = normAuthor.isNotEmpty() && (normPart1 == normAuthor || normPart1.startsWith(normAuthor))

                if (part1MatchesAuthor && !part0MatchesAuthor) {
                    // "Title - Artist"
                    artist = part1
                    cleanTitle = part0
                } else if (part0MatchesAuthor) {
                    // "Artist - Title"
                    artist = part0
                    cleanTitle = part1
                } else if (isTrackNumberPrefix(part0)) {
                    // "01 - Title" -> preserve author, don't set artist = "01"
                    cleanTitle = part1
                } else if (!isWeakSeparator) {
                    // Standard dash separator where neither part matches author: "Artist - Title"
                    artist = part0
                    cleanTitle = part1
                }
            }
        }
        cleanTitle = IdentifyRanking.cleanIdentityTitle(cleanTitle, artist).ifBlank { cleanTitle }
        return Pair(cleanTitle, artist.ifEmpty { "YouTube Artist" })
    }

    /**
     * Preference heuristic for YouTube audio downloads and playback candidates.
     * Higher = better match for downloading/streaming the song itself (not a music video).
     * Uses raw YouTube title/channel before [formatTitleAndArtist] stripping.
     * Incorporates expected track metadata (duration, title, artist, album) when available to penalize snippets/loops.
     */
    internal fun audioPreferenceScore(
        rawTitle: String,
        rawAuthor: String,
        candidateDurationMs: Long = 0L,
        expected: TrackMeta? = null,
        isLive: Boolean = false
    ): Int {
        val expectedDurationMs = expected?.durationMs ?: 0L
        val expectedTitle = expected?.title
        val expectedArtist = expected?.artist
        val expectedAlbum = expected?.album
        val title = rawTitle.lowercase()
        val author = rawAuthor.lowercase()
        var score = 0

        if (isLive || title.contains("en vivo") || title.contains("en directo") || title.contains("live stream") ||
            title.contains("gameplay") || title.contains("walkthrough") || title.contains("directo")
        ) {
            score -= 300
        }

        val isSnippetOrPart = SNIPPET_OR_PART_TITLE.containsMatchIn(title) ||
            title.contains("#shorts") || title.contains("#short")
        if (isSnippetOrPart) {
            score -= 200
        }

        // YouTube Music auto-generated uploads are typically album/single audio only.
        if (author.endsWith(" - topic") || author.contains(" - topic")) score += 100

        if (AUDIO_ONLY_TITLE.containsMatchIn(title)) score += 80
        // Lyrics bonus only for full non-snippet videos
        if (LYRICS_TITLE.containsMatchIn(title) && !MUSIC_VIDEO_TITLE.containsMatchIn(title) && !isSnippetOrPart) {
            score += 35
        }
        if (VISUALIZER_TITLE.containsMatchIn(title) && !isSnippetOrPart) score += 25

        if (MUSIC_VIDEO_TITLE.containsMatchIn(title)) score -= 80
        if (author.contains("vevo")) score -= 25
        if (LIVE_TITLE.containsMatchIn(title)) score -= 45
        if (COVER_OR_NOISE_TITLE.containsMatchIn(title)) score -= 50

        // Multi-signal: Duration comparison
        if (expectedDurationMs > 0L && candidateDurationMs > 0L) {
            val diffMs = kotlin.math.abs(candidateDurationMs - expectedDurationMs)
            if (candidateDurationMs < expectedDurationMs * 0.70) {
                // Fragment, snippet, or short cut (e.g. 143s vs 282s)
                score -= 180
            } else if (candidateDurationMs > expectedDurationMs * 1.35) {
                // Extended / 1-hour loop / full album / stream
                score -= 180
            } else if (diffMs <= 4_000L) {
                score += 80
            } else if (diffMs <= 10_000L) {
                score += 50
            } else if (diffMs <= 20_000L) {
                score += 25
            } else if (diffMs > 60_000L) {
                score -= 80
            }
        }

        // Multi-signal: Expected title & artist similarity
        if (!expectedTitle.isNullOrBlank()) {
            val qNorm = TrackMatchKeys.normalize(expectedTitle)
            val tNorm = TrackMatchKeys.normalize(rawTitle)
            val titleSim = IdentifyRanking.titleFieldSimilarity(qNorm, tNorm)
            if (titleSim >= 0.70f) {
                score += (titleSim * 60).toInt()
            } else if (qNorm.isNotBlank() && !tNorm.contains(qNorm)) {
                val qTokens = qNorm.split(" ").filter { it.length > 2 }
                if (qTokens.isNotEmpty() && qTokens.none { tNorm.contains(it) }) {
                    score -= 150
                }
            }
        }
        if (!expectedArtist.isNullOrBlank()) {
            val aNorm = TrackMatchKeys.normalize(expectedArtist)
            val authorNorm = TrackMatchKeys.normalize(rawAuthor)
            val tNorm = TrackMatchKeys.normalize(rawTitle)
            if (authorNorm.contains(aNorm) || tNorm.contains(aNorm) ||
                IdentifyRanking.fieldSimilarity(aNorm, authorNorm) >= 0.70f
            ) {
                score += 40
            } else {
                score -= 80
            }
        }

        // Multi-signal: Expected album bonus
        if (!expectedAlbum.isNullOrBlank() && !IdentifyRanking.isGenericAlbum(expectedAlbum)) {
            val albumNorm = TrackMatchKeys.normalize(expectedAlbum)
            val tNorm = TrackMatchKeys.normalize(rawTitle)
            if (albumNorm.length >= 3 && tNorm.contains(albumNorm)) {
                score += 25
            }
        }

        return score
    }

    /** L1 primitive overload to preserve continuous granularity for tests or standalone calls. */
    internal fun audioPreferenceScore(
        rawTitle: String,
        rawAuthor: String,
        candidateDurationMs: Long = 0L,
        expectedDurationMs: Long = 0L,
        expectedTitle: String? = null,
        expectedArtist: String? = null,
        expectedAlbum: String? = null,
        isLive: Boolean = false
    ): Int = audioPreferenceScore(
        rawTitle = rawTitle,
        rawAuthor = rawAuthor,
        candidateDurationMs = candidateDurationMs,
        expected = if (expectedDurationMs > 0L || !expectedTitle.isNullOrBlank() || !expectedArtist.isNullOrBlank() || !expectedAlbum.isNullOrBlank()) {
            TrackIdentity(
                title = expectedTitle.orEmpty(),
                artist = expectedArtist.orEmpty(),
                album = expectedAlbum.orEmpty(),
                durationMs = expectedDurationMs
            )
        } else null,
        isLive = isLive
    )

    /** Prefer audio-oriented uploads while keeping relative YouTube order among equal scores. */
    internal fun <T> rankByAudioPreference(
        items: List<T>,
        rawTitle: (T) -> String,
        rawAuthor: (T) -> String,
        isLiveOf: ((T) -> Boolean)? = null,
        durationMsOf: ((T) -> Long)? = null,
        expected: TrackMeta? = null
    ): List<T> {
        if (items.size <= 1) return items
        return items
            .mapIndexed { index, item ->
                val candDur = durationMsOf?.invoke(item) ?: 0L
                val isLive = isLiveOf?.invoke(item) ?: false
                val s = audioPreferenceScore(
                    rawTitle = rawTitle(item),
                    rawAuthor = rawAuthor(item),
                    candidateDurationMs = candDur,
                    expected = expected,
                    isLive = isLive
                )
                Triple(s, index, item)
            }
            .sortedWith(compareByDescending<Triple<Int, Int, T>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    /** L1 primitive overload to preserve continuous granularity. */
    internal fun <T> rankByAudioPreference(
        items: List<T>,
        rawTitle: (T) -> String,
        rawAuthor: (T) -> String,
        isLiveOf: ((T) -> Boolean)? = null,
        durationMsOf: ((T) -> Long)? = null,
        expectedDurationMs: Long = 0L,
        expectedTitle: String? = null,
        expectedArtist: String? = null,
        expectedAlbum: String? = null
    ): List<T> = rankByAudioPreference(
        items = items,
        rawTitle = rawTitle,
        rawAuthor = rawAuthor,
        isLiveOf = isLiveOf,
        durationMsOf = durationMsOf,
        expected = if (expectedDurationMs > 0L || !expectedTitle.isNullOrBlank() || !expectedArtist.isNullOrBlank() || !expectedAlbum.isNullOrBlank()) {
            TrackIdentity(
                title = expectedTitle.orEmpty(),
                artist = expectedArtist.orEmpty(),
                album = expectedAlbum.orEmpty(),
                durationMs = expectedDurationMs
            )
        } else null
    )

    /**
     * Resolve a catalog track to a YouTube video id or search query.
     * Catalog providers (Deezer/iTunes) store numeric ids — those must not be sent to YouTube search.
     */
    fun resolveYouTubeQueryOrId(track: OnlineCatalogTrack): String {
        extractYouTubeId(track.id)?.let { return it }
        extractYouTubeId(track.audioUrl)?.let { return track.audioUrl.trim() }
        val audioHint = track.audioUrl.trim()
        if (audioHint.isNotBlank() && !audioHint.startsWith("http", ignoreCase = true) &&
            audioHint.any { it.isLetter() } && !ISRC_REGEX.matches(audioHint)
        ) {
            return audioHint
        }
        return track.youtubeSearchQuery().ifBlank { track.id }
    }

    internal fun parseSearchContents(
        contents: JSONArray,
        expected: TrackMeta? = null
    ): List<OnlineCatalogTrack> {
        data class ParsedHit(
            val rawTitle: String,
            val rawAuthor: String,
            val isLive: Boolean,
            val track: OnlineCatalogTrack
        )

        fun rank(hits: List<ParsedHit>): List<OnlineCatalogTrack> =
            rankByAudioPreference(
                items = hits,
                rawTitle = { it.rawTitle },
                rawAuthor = { it.rawAuthor },
                isLiveOf = { it.isLive },
                durationMsOf = { it.track.durationMs },
                expected = expected
            ).map { it.track }

        val hits = mutableListOf<ParsedHit>()
        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i)?.optJSONObject("itemSectionRenderer") ?: continue
            val items = section.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val video = items.optJSONObject(j)?.optJSONObject("compactVideoRenderer")
                    ?: items.optJSONObject(j)?.optJSONObject("videoRenderer")
                    ?: continue
                val videoId = video.optString("videoId")
                if (videoId.isEmpty()) continue

                val rawTitle = video.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?: video.optJSONObject("title")?.optString("simpleText", "YouTube Video")
                    ?: "YouTube Video"
                val rawAuthor = video.optJSONObject("ownerText")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?: video.optJSONObject("longBylineText")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text")
                    ?: "YouTube Artist"
                val (title, artist) = formatTitleAndArtist(rawTitle, rawAuthor)
                val thumbnails = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val artworkUrl = thumbnails?.let {
                    if (it.length() > 0) it.optJSONObject(it.length() - 1)?.optString("url")
                    else null
                }

                val lengthTextObj = video.optJSONObject("lengthText")
                var rawDurationText = lengthTextObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: lengthTextObj?.optString("simpleText", "").orEmpty()
                if (rawDurationText.isBlank()) {
                    rawDurationText = video.optString("lengthText", "")
                }

                var isLiveVideo = false
                val overlays = video.optJSONArray("thumbnailOverlays")
                if (overlays != null) {
                    for (k in 0 until overlays.length()) {
                        val timeStatus = overlays.optJSONObject(k)
                            ?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                        val style = timeStatus.optString("style", "")
                        if (style.equals("LIVE", ignoreCase = true)) {
                            isLiveVideo = true
                        }
                        if (rawDurationText.isBlank()) {
                            rawDurationText = timeStatus.optJSONObject("text")?.let { textObj ->
                                textObj.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                    ?: textObj.optString("simpleText", "")
                            }.orEmpty()
                        }
                    }
                }
                val badges = video.optJSONArray("badges")
                if (badges != null) {
                    for (k in 0 until badges.length()) {
                        val badgeLabel = badges.optJSONObject(k)?.optJSONObject("metadataBadgeRenderer")?.optString("label", "")
                        if (badgeLabel?.contains("LIVE", ignoreCase = true) == true ||
                            badgeLabel?.contains("DIRECTO", ignoreCase = true) == true
                        ) {
                            isLiveVideo = true
                        }
                    }
                }

                val durationMs = if (isLiveVideo) 0L else parseDurationTextToMs(rawDurationText)

                hits.add(
                    ParsedHit(
                        rawTitle = rawTitle,
                        rawAuthor = rawAuthor,
                        isLive = isLiveVideo,
                        track = OnlineCatalogTrack(
                            id = videoId,
                            title = title,
                            artist = artist,
                            album = "YouTube",
                            artworkUri = artworkUrl,
                            durationMs = durationMs,
                            audioUrl = "https://www.youtube.com/watch?v=$videoId",
                            provider = "YouTube"
                        )
                    )
                )
                if (hits.size >= 25) {
                    return rank(hits)
                }
            }
            if (hits.isNotEmpty()) {
                return rank(hits)
            }
        }
        return rank(hits)
    }

    suspend fun searchYouTube(
        query: String,
        expected: TrackMeta? = null
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineCatalogTrack>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext results

        // 1. InnerTube API Search (/youtubei/v1/search)
        try {
            val clientCtx = JSONObject().apply {
                put("clientName", ANDROID_MAIN.name)
                put("clientVersion", ANDROID_MAIN.version)
                put("hl", "es")
                put("gl", "US")
                put("userAgent", ANDROID_MAIN.userAgent)
                put("osName", ANDROID_MAIN.osName)
                put("osVersion", ANDROID_MAIN.osVersion)
            }

            val bodyJson = JSONObject().apply {
                put("context", JSONObject().put("client", clientCtx))
                put("query", trimmed)
            }

            val request = Request.Builder()
                .url(endpoint(endpoints.webBaseUrl, "youtubei/v1/search"))
                .header("X-YouTube-Client-Name", ANDROID_MAIN.clientId)
                .header("X-YouTube-Client-Version", ANDROID_MAIN.version)
                .header("User-Agent", ANDROID_MAIN.userAgent)
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
                        results.addAll(
                            parseSearchContents(
                                contents,
                                expected = expected
                            )
                        )
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
                val url = endpoint(endpoints.webBaseUrl, "results?search_query=$encodedQ")
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
                                results.addAll(
                                    parseSearchContents(
                                        contents,
                                        expected = expected
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

        return@withContext results
    }

    /** L1 primitive overload for searchYouTube to preserve continuous granularity. */
    suspend fun searchYouTube(
        query: String,
        expectedDurationMs: Long = 0L,
        expectedTitle: String? = null,
        expectedArtist: String? = null,
        expectedAlbum: String? = null
    ): List<OnlineCatalogTrack> = searchYouTube(
        query = query,
        expected = if (expectedDurationMs > 0L || !expectedTitle.isNullOrBlank() || !expectedArtist.isNullOrBlank() || !expectedAlbum.isNullOrBlank()) {
            TrackIdentity(
                title = expectedTitle.orEmpty(),
                artist = expectedArtist.orEmpty(),
                album = expectedAlbum.orEmpty(),
                durationMs = expectedDurationMs
            )
        } else null
    )

    private fun parseDurationTextToMs(durStr: String): Long {
        if (durStr.isBlank()) return 0L
        val parts = durStr.split(":")
        return try {
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000L
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000L
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun extractAudioStream(
        urlOrQuery: String,
        expected: TrackMeta? = null,
        fallbackQuery: String? = null
    ): YouTubeStreamResult? {
        val res = extractAudioStreamDetailed(
            urlOrQuery = urlOrQuery,
            expected = expected,
            fallbackQuery = fallbackQuery
        )
        return if (res is YouTubeExtractResult.Success) res.result else null
    }

    /** L1 primitive overload for extractAudioStream. */
    suspend fun extractAudioStream(
        urlOrQuery: String,
        expectedDurationMs: Long = 0L,
        expectedTitle: String? = null,
        expectedArtist: String? = null,
        expectedAlbum: String? = null,
        fallbackQuery: String? = null
    ): YouTubeStreamResult? = extractAudioStream(
        urlOrQuery = urlOrQuery,
        expected = if (expectedDurationMs > 0L || !expectedTitle.isNullOrBlank() || !expectedArtist.isNullOrBlank() || !expectedAlbum.isNullOrBlank()) {
            TrackIdentity(
                title = expectedTitle.orEmpty(),
                artist = expectedArtist.orEmpty(),
                album = expectedAlbum.orEmpty(),
                durationMs = expectedDurationMs
            )
        } else null,
        fallbackQuery = fallbackQuery
    )

    suspend fun extractAudioStreamDetailed(
        urlOrQuery: String,
        expected: TrackMeta? = null,
        fallbackQuery: String? = null
    ): YouTubeExtractResult = withContext(Dispatchers.IO) {
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
            val isIsrc = ISRC_REGEX.matches(trimmed)
            val primaryQuery = if (isIsrc) {
                fallbackQuery?.takeIf { it.isNotBlank() && !ISRC_REGEX.matches(it) }
                    ?: listOfNotNull(expected?.artist, expected?.title).joinToString(" ").trim()
            } else {
                trimmed
            }

            if (primaryQuery.isNotBlank()) {
                val searchResults = searchYouTube(
                    query = primaryQuery,
                    expected = expected
                )
                if (searchResults.isNotEmpty()) {
                    videoId = searchResults.first().id
                } else {
                    // Fallback query: Limpiar paréntesis, "Remastered", "Deluxe", "feat.", y caracteres especiales
                    val candidateFallback = fallbackQuery?.takeIf { it != primaryQuery && !ISRC_REGEX.matches(it) } ?: run {
                        primaryQuery
                            .replace(FALLBACK_PAREN, "")
                            .replace(FALLBACK_BRACKET, "")
                            .replace(NON_ALPHANUM_SPACE, " ")
                            .trim()
                    }
                    if (candidateFallback.isNotBlank() && candidateFallback != primaryQuery) {
                        val fallbackResults = searchYouTube(
                            query = candidateFallback,
                            expected = expected
                        )
                        if (fallbackResults.isNotEmpty()) {
                            videoId = fallbackResults.first().id
                        }
                    }
                }
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
                CrashReporter.recordNonFatal(
                    e,
                    mapOf(
                        "yt_phase" to "player_api",
                        "yt_client" to clientProfile.name,
                        "yt_video_id" to videoId
                    )
                )
            }
        }

        val finalErrorMsg = if (lastErrorReason.isNotBlank()) {
            "El video de YouTube no está disponible ($lastErrorReason)"
        } else {
            "No se pudo extraer la pista de audio de este video de YouTube"
        }

        CrashReporter.recordNonFatal(
            IllegalStateException(finalErrorMsg),
            mapOf(
                "yt_phase" to "extract_exhausted",
                "yt_video_id" to videoId,
                "yt_last_reason" to lastErrorReason.ifBlank { "none" }
            )
        )

        return@withContext YouTubeExtractResult.Error(finalErrorMsg)
    }

    /** L1 primitive overload for extractAudioStreamDetailed to preserve continuous granularity. */
    suspend fun extractAudioStreamDetailed(
        urlOrQuery: String,
        expectedDurationMs: Long = 0L,
        expectedTitle: String? = null,
        expectedArtist: String? = null,
        expectedAlbum: String? = null,
        fallbackQuery: String? = null
    ): YouTubeExtractResult = extractAudioStreamDetailed(
        urlOrQuery = urlOrQuery,
        expected = if (expectedDurationMs > 0L || !expectedTitle.isNullOrBlank() || !expectedArtist.isNullOrBlank() || !expectedAlbum.isNullOrBlank()) {
            TrackIdentity(
                title = expectedTitle.orEmpty(),
                artist = expectedArtist.orEmpty(),
                album = expectedAlbum.orEmpty(),
                durationMs = expectedDurationMs
            )
        } else null,
        fallbackQuery = fallbackQuery
    )

    @Volatile
    private var cachedVisitorData: String? = null

    private fun fetchVisitorData(videoId: String): String? {
        val cached = cachedVisitorData
        if (cached != null) {
            return cached.ifEmpty { null }
        }
        return try {
            val url = endpoint(endpoints.webBaseUrl, "watch?v=$videoId")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val match = VISITOR_DATA_REGEX.find(html)
                    val vData = match?.groupValues?.get(1)
                    cachedVisitorData = vData ?: ""
                    vData
                } else {
                    cachedVisitorData = ""
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            cachedVisitorData = ""
            null
        }
    }


    private fun callPlayerApi(clientProfile: ClientProfile, videoId: String): Pair<YouTubeStreamResult?, String?> {
        val playerEndpoint = if (clientProfile.apiKey.isEmpty()) {
            endpoint(endpoints.webBaseUrl, "youtubei/v1/player")
        } else {
            endpoint(
                endpoints.googleApiBaseUrl,
                "youtubei/v1/player?key=${clientProfile.apiKey}"
            )
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
            .url(playerEndpoint)
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
                val isAdaptive = (arr === adaptiveFormats)
                for (i in 0 until arr.length()) {
                    val fmt = arr.getJSONObject(i)
                    val url = fmt.optString("url")
                    val mime = fmt.optString("mimeType")
                    val bitrate = fmt.optInt("bitrate", 0)

                    if (url.isEmpty()) continue

                    val isAudioOnly = mime.contains("audio/")
                    val isMuxedMp4 = !isAdaptive && mime.contains("video/mp4")

                    if (!isAudioOnly && !isMuxedMp4) continue

                    val isMp4Audio = isAudioOnly && (mime.contains("audio/mp4") || mime.contains("mp4a"))

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
                    userAgent = clientProfile.userAgent.removeSuffix(" gzip").trim()
                )
                return Pair(streamResult, null)
            }
        }
        return Pair(null, "No se encontraron URLs de audio sin descifrar")
    }
}
