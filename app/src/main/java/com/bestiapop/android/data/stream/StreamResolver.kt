package com.bestiapop.android.data.stream

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeExtractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves YouTube audio stream URLs for remote playable items.
 * Keeps a short-lived in-memory cache; CDN URLs must not be written to Room.
 */
class StreamResolver(
    private val extract: suspend (String) -> YouTubeExtractResult = { YouTubeExtractor.extractAudioStreamDetailed(it) },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ResolvedStream>()

    suspend fun resolve(item: PlayableItem.Remote): Result<ResolvedStream> {
        val key = cacheKey(item)
        mutex.withLock {
            cache[key]?.let { cached ->
                if (isFresh(cached)) return Result.success(cached)
                cache.remove(key)
            }
        }

        val query = item.youtubeQueryOrId?.takeIf { it.isNotBlank() }
            ?: "${item.artist} ${item.title}".trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube query for remote item"))
        }

        return when (val result = extract(query)) {
            is YouTubeExtractResult.Success -> {
                val stream = result.result
                val resolved = ResolvedStream(
                    audioUrl = stream.audioUrl,
                    userAgent = stream.userAgent,
                    videoId = stream.videoId,
                    resolvedAtEpochMs = clockMs()
                )
                mutex.withLock {
                    cache[key] = resolved
                    if (stream.videoId.isNotBlank()) {
                        cache["id:${stream.videoId}"] = resolved
                    }
                }
                Result.success(resolved)
            }
            is YouTubeExtractResult.Error -> Result.failure(IllegalStateException(result.message))
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
    }

    private fun cacheKey(item: PlayableItem.Remote): String {
        item.resolved?.videoId?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        val query = item.youtubeQueryOrId?.takeIf { it.isNotBlank() }
            ?: "${item.artist} ${item.title}".trim().lowercase()
        return "q:${query.lowercase().trim()}"
    }

    companion object {
        const val DEFAULT_TTL_MS = 4 * 60 * 1000L
    }
}
