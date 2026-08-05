package com.bestiapop.android.data.stream

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.network.YouTubeStreamResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves YouTube audio stream URLs for remote playable items and downloads.
 * Keeps a short-lived in-memory cache; CDN URLs must not be written to Room.
 */
class StreamResolver(
    private val extract: suspend (String) -> YouTubeExtractResult = { YouTubeExtractor.extractAudioStreamDetailed(it) },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ResolvedStream>()

    /**
     * L2: resolve by query or video id. Updates the same TTL cache used by [resolve].
     * On a fresh cache hit, metadata fields may be empty (URL + UA + videoId are filled).
     */
    suspend fun resolveQuery(queryOrId: String): Result<YouTubeStreamResult> {
        val query = queryOrId.trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube query"))
        }

        val qKey = queryCacheKey(query)
        mutex.withLock {
            freshCached(qKey)?.let { return Result.success(it.toStreamResultStub()) }
            if (looksLikeVideoId(query)) {
                freshCached("id:$query")?.let { return Result.success(it.toStreamResultStub()) }
            }
        }

        return when (val result = extract(query)) {
            is YouTubeExtractResult.Success -> {
                val stream = result.result
                putResolved(qKey, stream)
                Result.success(stream)
            }
            is YouTubeExtractResult.Error -> Result.failure(IllegalStateException(result.message))
        }
    }

    suspend fun resolve(item: PlayableItem.Remote): Result<ResolvedStream> {
        val key = cacheKey(item)
        mutex.withLock {
            freshCached(key)?.let { return Result.success(it) }
        }

        val query = item.youtubeQueryOrId?.takeIf { it.isNotBlank() }
            ?: "${item.artist} ${item.title}".trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube query for remote item"))
        }

        return resolveQuery(query).map { stream ->
            ResolvedStream(
                audioUrl = stream.audioUrl,
                userAgent = stream.userAgent,
                videoId = stream.videoId,
                resolvedAtEpochMs = clockMs()
            )
        }
    }

    suspend fun prefetch(items: List<PlayableItem.Remote>) {
        for (item in items) {
            if (item.resolved != null && isFresh(item.resolved)) continue
            resolve(item)
        }
    }

    fun isFresh(resolved: ResolvedStream): Boolean {
        return clockMs() - resolved.resolvedAtEpochMs < ttlMs
    }

    fun invalidate(key: String) {
        cache.remove(key)
        cache.remove("id:$key")
        cache.remove(queryCacheKey(key))
    }

    private fun freshCached(key: String): ResolvedStream? {
        val cached = cache[key] ?: return null
        return if (isFresh(cached)) cached else {
            cache.remove(key)
            null
        }
    }

    private suspend fun putResolved(qKey: String, stream: YouTubeStreamResult) {
        val resolved = ResolvedStream(
            audioUrl = stream.audioUrl,
            userAgent = stream.userAgent,
            videoId = stream.videoId,
            resolvedAtEpochMs = clockMs()
        )
        mutex.withLock {
            cache[qKey] = resolved
            if (stream.videoId.isNotBlank()) {
                cache["id:${stream.videoId}"] = resolved
            }
        }
    }

    private fun cacheKey(item: PlayableItem.Remote): String {
        item.resolved?.videoId?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        val query = item.youtubeQueryOrId?.takeIf { it.isNotBlank() }
            ?: "${item.artist} ${item.title}".trim().lowercase()
        return queryCacheKey(query)
    }

    private fun queryCacheKey(query: String): String = "q:${query.lowercase().trim()}"

    private fun looksLikeVideoId(value: String): Boolean =
        value.length == 11 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun ResolvedStream.toStreamResultStub() = YouTubeStreamResult(
        videoId = videoId,
        title = "",
        artist = "",
        artworkUrl = null,
        durationMs = 0L,
        audioUrl = audioUrl,
        userAgent = userAgent
    )

    companion object {
        const val DEFAULT_TTL_MS = 4 * 60 * 1000L
    }
}
