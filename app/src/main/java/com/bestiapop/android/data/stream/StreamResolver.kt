package com.bestiapop.android.data.stream

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.network.YouTubeStreamResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves YouTube audio stream URLs for remote playable items and downloads.
 * Keeps a short-lived in-memory cache; CDN URLs must not be written to Room.
 */
class StreamResolver internal constructor(
    private val extract: suspend (String) -> YouTubeExtractResult = { YouTubeExtractor.extractAudioStreamDetailed(it) },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val onKeyLockReserved: suspend (String, Any) -> Unit = { _, _ -> }
) {
    private data class CachedExtraction(
        val stream: YouTubeStreamResult,
        val resolved: ResolvedStream
    )

    private data class KeyLock(
        val mutex: Mutex = Mutex(),
        var references: Int = 0
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ResolvedStream>()
    /** One lock per query so playback, prefetch and the 403 retry cannot extract the same video at once. */
    private val keyLocks = mutableMapOf<String, KeyLock>()

    /**
     * L2: resolve by query or video id. Updates the same TTL cache used by [resolve].
     * On a fresh cache hit, metadata fields may be empty (URL + UA + videoId are filled).
     * [forceRefresh] skips cache reads (download must re-extract; CDN URLs expire).
     */
    suspend fun resolveQuery(queryOrId: String, forceRefresh: Boolean = false): Result<YouTubeStreamResult> {
        val query = queryOrId.trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube query"))
        }

        val qKey = queryCacheKey(query)
        // Serialized per key: without it, ensureRemoteReadyAt + prefetch + retry each ran a full
        // extraction (several HTTP round trips) for the same video and the last writer won.
        return withKeyLock(qKey) {
            if (!forceRefresh) {
                cachedStream(qKey, query, ttlMs)?.let {
                    return@withKeyLock Result.success(it)
                }
            }
            extractAndCache(query, qKey).map { it.stream }
        }
    }

    private suspend fun cachedStream(
        qKey: String,
        query: String,
        maxCachedAgeMs: Long
    ): YouTubeStreamResult? =
        mutex.withLock {
            freshestCachedLocked(
                keys = buildList {
                    add(qKey)
                    if (looksLikeVideoId(query)) add("id:$query")
                },
                maxCachedAgeMs = maxCachedAgeMs
            )?.toStreamResultStub()
        }

    private suspend fun <T> withKeyLock(
        key: String,
        block: suspend () -> T
    ): T {
        val keyLock = mutex.withLock {
            pruneKeyLocksLocked()
            keyLocks.getOrPut(key) { KeyLock() }.also { it.references++ }
        }
        return try {
            // Reservation happens before the mutex is handed to the caller. Pruning therefore
            // cannot replace a lock while a coroutine is suspended between lookup and acquisition.
            onKeyLockReserved(key, keyLock.mutex)
            keyLock.mutex.withLock { block() }
        } finally {
            mutex.withLock {
                check(keyLock.references > 0)
                keyLock.references--
                pruneKeyLocksLocked()
            }
        }
    }

    private fun pruneKeyLocksLocked() {
        if (keyLocks.size <= MAX_CACHE_ENTRIES) return
        val iterator = keyLocks.entries.iterator()
        while (keyLocks.size > MAX_CACHE_ENTRIES && iterator.hasNext()) {
            if (iterator.next().value.references == 0) iterator.remove()
        }
    }

    /**
     * Resolves a stream for a playback selection, accepting cache entries no older than
     * [maxCachedAgeMs]. The age check and any required extraction share the query lock, so
     * concurrent playback/prefetch calls cannot put the over-age entry back into use.
     */
    suspend fun resolveForPlayback(
        item: PlayableItem.Remote,
        maxCachedAgeMs: Long
    ): Result<ResolvedStream> {
        val query = queryFor(item)
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube query for remote item"))
        }

        val qKey = queryCacheKey(query)
        return withKeyLock(qKey) {
            cachedResolvedForPlayback(item, qKey, query, maxCachedAgeMs)?.let {
                return@withKeyLock Result.success(it)
            }
            extractAndCache(query, qKey).map { it.resolved }
        }
    }

    /** Compatibility API: playback cache remains bounded by the resolver's general TTL. */
    suspend fun resolve(item: PlayableItem.Remote): Result<ResolvedStream> =
        resolveForPlayback(item, maxCachedAgeMs = ttlMs)

    suspend fun prefetch(items: List<PlayableItem.Remote>) {
        for (item in items) {
            if (item.resolved != null && isFresh(item.resolved)) continue
            resolve(item)
        }
    }

    fun isFresh(resolved: ResolvedStream): Boolean =
        isFreshAt(resolved, clockMs())

    /**
     * Drops every entry that could hand [item]'s dead URL back. Each resolution is cached under both
     * `q:<query>` and `id:<videoId>`, so clearing only the id key left the query key serving the
     * expired URL for the rest of the TTL and the post-403 retry re-prepared the same stream.
     */
    suspend fun invalidate(item: PlayableItem.Remote) {
        val videoId = item.resolved?.videoId?.takeIf { it.isNotBlank() }
        val query = item.youtubeQueryOrId?.takeIf { it.isNotBlank() } ?: item.youtubeSearchQuery()
        mutex.withLock {
            if (videoId != null) {
                cache.remove("id:$videoId")
                cache.values.removeAll { it.videoId == videoId }
            }
            if (query.isNotBlank()) cache.remove(queryCacheKey(query))
        }
    }

    private suspend fun cachedResolvedForPlayback(
        item: PlayableItem.Remote,
        qKey: String,
        query: String,
        maxCachedAgeMs: Long
    ): ResolvedStream? = mutex.withLock {
        freshestCachedLocked(
            keys = buildList {
                add(cacheKey(item))
                add(qKey)
                if (looksLikeVideoId(query)) add("id:$query")
            },
            maxCachedAgeMs = maxCachedAgeMs
        )
    }

    private fun freshestCachedLocked(
        keys: List<String>,
        maxCachedAgeMs: Long
    ): ResolvedStream? {
        val now = clockMs()
        val maxAge = maxCachedAgeMs.coerceAtLeast(0L)
        return keys.distinct()
            .mapNotNull { key ->
                val cached = cache[key] ?: return@mapNotNull null
                if (!isFreshAt(cached, now)) {
                    cache.remove(key)
                    null
                } else {
                    cached.takeIf { cacheAgeMs(it, now) <= maxAge }
                }
            }
            .maxByOrNull { it.resolvedAtEpochMs }
    }

    private suspend fun extractAndCache(
        query: String,
        qKey: String
    ): Result<CachedExtraction> = when (val result = extract(query)) {
        is YouTubeExtractResult.Success -> {
            val stream = result.result
            val resolved = stream.toResolvedStream()
            putResolved(qKey, resolved)
            Result.success(CachedExtraction(stream, resolved))
        }
        is YouTubeExtractResult.Error ->
            Result.failure(IllegalStateException(result.message))
    }

    private suspend fun putResolved(qKey: String, resolved: ResolvedStream) {
        mutex.withLock {
            cache[qKey] = resolved
            if (resolved.videoId.isNotBlank()) {
                cache["id:${resolved.videoId}"] = resolved
            }
            pruneLocked()
        }
    }

    /** Entries were only dropped when read after expiry, so a long browsing session grew the map. */
    private fun pruneLocked() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val now = clockMs()
        cache.entries.removeAll { !isFreshAt(it.value, now) }
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldest = cache.minByOrNull { it.value.resolvedAtEpochMs }?.key ?: break
            cache.remove(oldest)
        }
    }

    private fun queryFor(item: PlayableItem.Remote): String =
        item.youtubeQueryOrId?.takeIf { it.isNotBlank() }
            ?: item.youtubeSearchQuery()

    private fun cacheKey(item: PlayableItem.Remote): String {
        item.resolved?.videoId?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        return queryCacheKey(queryFor(item))
    }

    private fun queryCacheKey(query: String): String = "q:${query.lowercase().trim()}"

    private fun looksLikeVideoId(value: String): Boolean {
        val trimmed = value.trim()
        return YouTubeExtractor.extractYouTubeId(trimmed) == trimmed
    }

    private fun cacheAgeMs(resolved: ResolvedStream, now: Long): Long =
        if (now >= resolved.resolvedAtEpochMs) now - resolved.resolvedAtEpochMs else 0L

    private fun isFreshAt(resolved: ResolvedStream, now: Long): Boolean =
        cacheAgeMs(resolved, now) < ttlMs

    private fun YouTubeStreamResult.toResolvedStream() = ResolvedStream(
        audioUrl = audioUrl,
        userAgent = userAgent,
        videoId = videoId,
        resolvedAtEpochMs = clockMs()
    )

    private fun ResolvedStream.toStreamResultStub() = YouTubeStreamResult(
        identity = TrackIdentity(title = ""),
        videoId = videoId,
        audioUrl = audioUrl,
        userAgent = userAgent
    )

    companion object {
        const val DEFAULT_TTL_MS = 4 * 60 * 1000L
        /** Two keys per resolution (`q:` + `id:`), so this holds a few hundred distinct tracks. */
        private const val MAX_CACHE_ENTRIES = 256
    }
}
