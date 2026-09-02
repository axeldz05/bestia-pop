package com.bestiapop.android.data.network

import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.util.encodeAlbumTrack
import com.bestiapop.android.domain.util.IdentifyRanking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class MusicBrainzEndpoints(
    val apiBaseUrl: String = "https://musicbrainz.org/ws/2",
    val coverArtBaseUrl: String = "https://coverartarchive.org"
)

private data class MbGetResult(val json: JSONObject? = null, val failed: Boolean)
private data class MbSearchResult(val tracks: List<OnlineCatalogTrack>, val failed: Boolean)

/**
 * MusicBrainz WS2 search for identify only. Public rate limit is 1 req/s/IP;
 * all callers share one mutex so IDENTIFY_PARALLEL cannot burst.
 */
object MusicBrainzClient {

    private const val DEFAULT_INTERVAL_MS = 1_100L
    private const val FAIL_FAST_AFTER = 3
    private const val SKIP_AFTER_FAIL_MS = 5 * 60_000L

    private val defaultClient = HttpClients.api.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var client: OkHttpClient = defaultClient
    @Volatile
    private var endpoints = MusicBrainzEndpoints()
    @Volatile
    private var minIntervalMs = DEFAULT_INTERVAL_MS

    private val mutex = Mutex()
    private var lastRequestAtMs = 0L
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile
    private var skipUntilMs = 0L

    internal fun configureForTest(
        http: OkHttpClient,
        endpoints: MusicBrainzEndpoints,
        minIntervalMs: Long = 0L
    ) {
        client = http
        this.endpoints = endpoints
        this.minIntervalMs = minIntervalMs.coerceAtLeast(0L)
        lastRequestAtMs = 0L
        consecutiveFailures.set(0)
        skipUntilMs = 0L
    }

    internal fun resetTestOverrides() {
        client = defaultClient
        endpoints = MusicBrainzEndpoints()
        minIntervalMs = DEFAULT_INTERVAL_MS
        lastRequestAtMs = 0L
        consecutiveFailures.set(0)
        skipUntilMs = 0L
    }

    suspend fun searchRecordings(
        query: String,
        durationMs: Long? = null,
        limit: Int = 25
    ): List<OnlineCatalogTrack> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty() || endpoints.apiBaseUrl.isBlank()) return@withContext emptyList()
        if (nowMs() < skipUntilMs) return@withContext emptyList()
        val pageLimit = limit.coerceIn(1, 100)
        val withDur = durationMs?.takeIf { it > 0L }
        val first = searchOnce(luceneRecordingQuery(cleaned, withDur), pageLimit)
        if (first.failed) {
            noteFailure()
            return@withContext emptyList()
        }
        noteSuccess()
        if (first.tracks.isNotEmpty() || withDur == null) return@withContext first.tracks
        val second = searchOnce(luceneRecordingQuery(cleaned, durationMs = null), pageLimit)
        if (second.failed) {
            noteFailure()
            return@withContext emptyList()
        }
        noteSuccess()
        second.tracks
    }

    private suspend fun searchOnce(lucene: String, limit: Int): MbSearchResult {
        val url = endpoint(
            "recording/?query=${encode(lucene)}&fmt=json&limit=$limit"
        )
        val got = throttledGet(url)
        if (got.failed) return MbSearchResult(emptyList(), failed = true)
        val json = got.json ?: return MbSearchResult(emptyList(), failed = false)
        return MbSearchResult(
            tracks = parseMusicBrainzRecordingSearch(json, endpoints.coverArtBaseUrl),
            failed = false
        )
    }

    private suspend fun throttledGet(url: String): MbGetResult = mutex.withLock {
        val wait = lastRequestAtMs + minIntervalMs - nowMs()
        if (wait > 0L) delay(wait)
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withLock MbGetResult(failed = true)
                if (body.isBlank()) return@withLock MbGetResult(failed = false)
                MbGetResult(json = JSONObject(body), failed = false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MbGetResult(failed = true)
        } finally {
            lastRequestAtMs = nowMs()
        }
    }

    private fun noteFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAIL_FAST_AFTER) {
            skipUntilMs = nowMs() + SKIP_AFTER_FAIL_MS
            consecutiveFailures.set(0)
        }
    }

    private fun noteSuccess() {
        consecutiveFailures.set(0)
        skipUntilMs = 0L
    }

    private fun endpoint(pathAndQuery: String): String =
        "${endpoints.apiBaseUrl.trimEnd('/')}/${pathAndQuery.trimStart('/')}"

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

internal fun luceneRecordingQuery(text: String, durationMs: Long?): String {
    val rec = "recording:\"${escapeLucene(text)}\""
    if (durationMs == null || durationMs <= 0L) return rec
    val lo = (durationMs - 2_000L).coerceAtLeast(0L)
    val hi = durationMs + 2_000L
    return "$rec AND dur:[$lo TO $hi]"
}

internal fun parseMusicBrainzRecordingSearch(
    json: JSONObject,
    coverArtBaseUrl: String = "https://coverartarchive.org"
): List<OnlineCatalogTrack> {
    val recordings = json.optJSONArray("recordings") ?: return emptyList()
    if (recordings.length() == 0) return emptyList()
    val out = ArrayList<OnlineCatalogTrack>(recordings.length())
    for (i in 0 until recordings.length()) {
        val rec = recordings.optJSONObject(i) ?: continue
        toCatalogTrack(rec, coverArtBaseUrl)?.let(out::add)
    }
    return out
}

private fun toCatalogTrack(rec: JSONObject, coverArtBaseUrl: String): OnlineCatalogTrack? {
    val recordingId = rec.optString("id").trim()
    val title = rec.optString("title").trim()
    if (recordingId.isEmpty() || title.isBlank()) return null
    val artist = artistCreditName(rec.optJSONArray("artist-credit"))
    if (IdentifyRanking.isPlaceholderArtist(artist)) return null
    val release = pickRelease(rec.optJSONArray("releases"))
    val album = release?.optString("title")?.trim().orEmpty()
    val releaseId = release?.optString("id")?.trim().orEmpty()
    val artworkUri = if (releaseId.isNotEmpty()) {
        "${coverArtBaseUrl.trimEnd('/')}/release/$releaseId/front-500"
    } else {
        null
    }
    val durationMs = rec.optLong("length", 0L).coerceAtLeast(0L)
    val trackNumber = trackNumberOf(release, title)
    val year = MetadataFetcher.parseReleaseYear(release?.optString("date"))
    val bilingualTitle = bilingualFromAliases(title, rec.optJSONArray("aliases"))
    val identity = TrackIdentity(
        title = bilingualTitle,
        artist = artist,
        album = album,
        artworkUri = artworkUri,
        durationMs = durationMs,
        trackNumber = trackNumber
    )
    return OnlineCatalogTrack(
        identity = identity,
        id = recordingId,
        audioUrl = identity.youtubeSearchQuery(),
        provider = "MusicBrainz",
        year = year
    )
}

private fun bilingualFromAliases(title: String, aliases: JSONArray?): String {
    if (aliases == null || aliases.length() == 0) return title
    for (i in 0 until aliases.length()) {
        val name = aliases.optJSONObject(i)?.optString("name")?.trim().orEmpty()
        if (name.isEmpty()) continue
        val merged = IdentifyRanking.preferBilingualTitle(title, name)
        if (merged != title) return merged
    }
    return title
}

private fun artistCreditName(credits: JSONArray?): String {
    if (credits == null || credits.length() == 0) return ""
    return buildString {
        for (i in 0 until credits.length()) {
            val item = credits.optJSONObject(i) ?: continue
            val name = item.optString("name").ifBlank {
                item.optJSONObject("artist")?.optString("name").orEmpty()
            }.trim()
            if (name.isEmpty()) continue
            append(name)
            append(item.optString("joinphrase"))
        }
    }.trim()
}

private fun pickRelease(releases: JSONArray?): JSONObject? {
    if (releases == null || releases.length() == 0) return null
    var fallback: JSONObject? = null
    for (i in 0 until releases.length()) {
        val rel = releases.optJSONObject(i) ?: continue
        val status = rel.optString("status")
        if (status.equals("Official", ignoreCase = true)) return rel
        if (fallback == null) fallback = rel
    }
    return fallback
}

private fun trackNumberOf(release: JSONObject?, recordingTitle: String): Int {
    val media = release?.optJSONArray("media") ?: return 0
    for (i in 0 until media.length()) {
        val medium = media.optJSONObject(i) ?: continue
        val disc = medium.optInt("position", i + 1)
        val tracks = medium.optJSONArray("track") ?: continue
        for (j in 0 until tracks.length()) {
            val track = tracks.optJSONObject(j) ?: continue
            val name = track.optString("title").trim()
            val num = track.optString("number").substringBefore('.').toIntOrNull()
                ?: track.optInt("position", 0)
            if (name.equals(recordingTitle, ignoreCase = true) || tracks.length() == 1) {
                return encodeAlbumTrack(num, disc)
            }
        }
        if (tracks.length() > 0) {
            val first = tracks.optJSONObject(0) ?: continue
            val num = first.optString("number").substringBefore('.').toIntOrNull()
                ?: first.optInt("position", 0)
            return encodeAlbumTrack(num, disc)
        }
    }
    return 0
}

private fun escapeLucene(raw: String): String {
    val specials = charArrayOf(
        '\\', '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':'
    )
    val sb = StringBuilder(raw.length + 4)
    for (ch in raw) {
        if (ch in specials) sb.append('\\')
        sb.append(ch)
    }
    return sb.toString()
}

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun userAgent(): String {
    val repo = BuildConfig.GITHUB_REPOSITORY.trim().ifBlank { "axeldz05/bestia-pop" }
    return "BestiaPop/${BuildConfig.VERSION_NAME} (https://github.com/$repo)"
}
