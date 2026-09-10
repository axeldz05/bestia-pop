package com.bestiapop.android.data.stream

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.FileOutputStream

/**
 * Process-scoped singleton Media3 [SimpleCache] bridging streaming playback and downloads.
 * Both ExoPlayer streaming and [MusicRepository.downloadAndSaveOnlineTrack] share this cache
 * using YouTube video IDs as cache keys.
 */
@OptIn(UnstableApi::class)
object BestiaPopMediaCache {

    private const val MAX_CACHE_BYTES = 150 * 1024 * 1024L // 150 MB
    private const val CACHE_DIR_NAME = "bestiapop_media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null
    private val lock = Any()

    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(lock) {
            simpleCache ?: run {
                val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(cacheDir, evictor, databaseProvider).also {
                    simpleCache = it
                }
            }
        }
    }

    /**
     * Builds a [CacheDataSource.Factory] wrapping [upstreamFactory] for ExoPlayer.
     * When [cacheKey] is provided, uses it as the fixed cache key for all requests through this source.
     */
    fun createCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
        cacheKey: String? = null
    ): CacheDataSource.Factory {
        val cache = getCache(context)
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (!cacheKey.isNullOrBlank()) {
            factory.setCacheKeyFactory { _ -> cacheKey }
        }
        return factory
    }

    /**
     * Formats the cache key for a given YouTube video ID or stream query.
     */
    fun cacheKey(videoId: String): String = "yt:$videoId"

    /**
     * Inspects the cache for [videoId] starting from offset 0. If contiguous cached bytes
     * are found, reads them from the cache and writes them directly into [destination].
     *
     * @return Number of contiguous bytes successfully copied from cache (0 if none cached).
     */
    fun copyCachedPrefixToFile(
        context: Context,
        videoId: String,
        destination: File
    ): Long {
        if (videoId.isBlank()) return 0L
        val cache = getCache(context)
        val key = cacheKey(videoId)
        val spans: Set<CacheSpan> = cache.getCachedSpans(key)
        if (spans.isEmpty()) return 0L

        // Find contiguous cached span starting at offset 0
        var contiguousLength = 0L
        val sortedSpans = spans.sortedBy { it.position }
        for (span in sortedSpans) {
            if (span.position <= contiguousLength) {
                val spanEnd = span.position + span.length
                if (spanEnd > contiguousLength) {
                    contiguousLength = spanEnd
                }
            } else {
                break
            }
        }

        if (contiguousLength <= 0L) return 0L

        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null) // No network; cache-only
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri("dummy://$key")
            .setKey(key)
            .setPosition(0L)
            .setLength(contiguousLength)
            .build()

        return try {
            cacheDataSource.open(dataSpec)
            val buffer = ByteArray(65536)
            var totalRead = 0L
            FileOutputStream(destination, false).use { out ->
                while (totalRead < contiguousLength) {
                    val toRead = minOf(buffer.size.toLong(), contiguousLength - totalRead).toInt()
                    val read = cacheDataSource.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    totalRead += read
                }
                out.flush()
            }
            totalRead
        } catch (e: Exception) {
            // If reading cache failed partially, return what was written or clean up
            if (destination.exists() && destination.length() > 0) destination.length() else 0L
        } finally {
            runCatching { cacheDataSource.close() }
        }
    }

    /**
     * Writes downloaded chunk bytes into [SimpleCache] so streaming playback can use them.
     */
    fun writeChunkToCache(
        context: Context,
        videoId: String,
        position: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ) {
        if (videoId.isBlank() || length <= 0) return
        val cache = getCache(context)
        val key = cacheKey(videoId)
        val sink = CacheDataSink.Factory()
            .setCache(cache)
            .createDataSink()

        val dataSpec = DataSpec.Builder()
            .setUri("dummy://$key")
            .setKey(key)
            .setPosition(position)
            .setLength(length.toLong())
            .build()

        try {
            sink.open(dataSpec)
            sink.write(bytes, offset, length)
        } catch (_: Exception) {
            // Cache write failure is non-fatal
        } finally {
            runCatching { sink.close() }
        }
    }
}
