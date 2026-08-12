package com.bestiapop.android.data.stream

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeStreamResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResolverTest {

    @Test
    fun resolve_cachesResultWithinTtl() = runBlocking {
        var extractCalls = 0
        var now = 1_000L
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                YouTubeExtractResult.Success(
                    YouTubeStreamResult(
                        videoId = "vid12345678",
                        title = "Song",
                        artist = "Artist",
                        artworkUrl = null,
                        durationMs = 180_000L,
                        audioUrl = "https://googlevideo.example/audio",
                        userAgent = "TestUA"
                    )
                )
            },
            clockMs = { now },
            ttlMs = 4 * 60 * 1000L
        )

        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")
        val first = resolver.resolve(item).getOrThrow()
        val second = resolver.resolve(item).getOrThrow()

        assertEquals(1, extractCalls)
        assertEquals(first.audioUrl, second.audioUrl)
        assertEquals("vid12345678", first.videoId)
    }

    @Test
    fun resolve_reExtractsAfterTtlExpires() = runBlocking {
        var extractCalls = 0
        var now = 0L
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                successfulExtract(extractCalls)
            },
            clockMs = { now },
            ttlMs = 4 * 60 * 1000L
        )

        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")
        resolver.resolve(item).getOrThrow()
        now = 4 * 60 * 1000L + 1
        val refreshed = resolver.resolve(item).getOrThrow()

        assertEquals(2, extractCalls)
        assertTrue(refreshed.audioUrl.endsWith("-2"))
    }

    @Test
    fun resolveForPlayback_usesCacheAt59Seconds() = runBlocking {
        var extractCalls = 0
        var now = 0L
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                successfulExtract(extractCalls)
            },
            clockMs = { now },
            ttlMs = 4 * 60 * 1000L
        )
        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")

        val first = resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()
        now = 59_000L
        val cached = resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()

        assertEquals(1, extractCalls)
        assertEquals(first, cached)
    }

    @Test
    fun resolveForPlayback_refreshesAt61SecondsBeforeGeneralTtl() = runBlocking {
        var extractCalls = 0
        var now = 0L
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                successfulExtract(extractCalls)
            },
            clockMs = { now },
            ttlMs = 4 * 60 * 1000L
        )
        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")

        resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()
        now = 61_000L
        val refreshed = resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()

        assertEquals(2, extractCalls)
        assertTrue(refreshed.audioUrl.endsWith("-2"))
    }

    @Test(timeout = 5_000L)
    fun resolveForPlayback_concurrentOverAgeCalls_shareSingleRefresh() = runBlocking {
        var extractCalls = 0
        var now = 0L
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                if (extractCalls == 2) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
                successfulExtract(extractCalls)
            },
            clockMs = { now },
            ttlMs = 4 * 60 * 1000L
        )
        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")
        resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()
        now = 61_000L

        val first = async {
            resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()
        }
        refreshStarted.await()
        val second = async {
            resolver.resolveForPlayback(item, maxCachedAgeMs = 60_000L).getOrThrow()
        }
        yield()
        releaseRefresh.complete(Unit)

        assertTrue(first.await().audioUrl.endsWith("-2"))
        assertTrue(second.await().audioUrl.endsWith("-2"))
        assertEquals(2, extractCalls)
    }

    @Test(timeout = 5_000L)
    fun keyLockReservation_survivesPruningBeforeMutexAcquisition() = runBlocking {
        val targetQuery = "Reserved target"
        val targetKey = "q:${targetQuery.lowercase()}"
        val firstReserved = CompletableDeferred<Unit>()
        val releaseFirstReservation = CompletableDeferred<Unit>()
        val reservedTokens = mutableListOf<Any>()
        var targetReservations = 0
        var extractCalls = 0
        var targetExtractCalls = 0
        val resolver = StreamResolver(
            extract = { query ->
                extractCalls++
                if (query == targetQuery) targetExtractCalls++
                successfulExtract(extractCalls)
            },
            clockMs = { 1_000L },
            onKeyLockReserved = { key, token ->
                if (key == targetKey) {
                    reservedTokens += token
                    targetReservations++
                    if (targetReservations == 1) {
                        firstReserved.complete(Unit)
                        releaseFirstReservation.await()
                    }
                }
            }
        )

        val first = async {
            resolver.resolveQuery(targetQuery).getOrThrow()
        }
        try {
            firstReserved.await()

            // Exceed the pruning threshold while the first caller owns a reference but has not
            // acquired its per-key mutex yet.
            repeat(300) { index ->
                resolver.resolveQuery("pressure-$index").getOrThrow()
            }

            val second = async {
                resolver.resolveQuery(targetQuery).getOrThrow()
            }
            second.await()

            assertEquals(2, reservedTokens.size)
            assertSame(reservedTokens[0], reservedTokens[1])
            assertEquals(1, targetExtractCalls)

            releaseFirstReservation.complete(Unit)
            first.await()
            assertEquals(1, targetExtractCalls)
        } finally {
            releaseFirstReservation.complete(Unit)
        }
    }

    @Test
    fun resolve_propagatesExtractorError() = runBlocking {
        val resolver = StreamResolver(
            extract = { YouTubeExtractResult.Error("boom") },
            clockMs = { 0L }
        )
        val result = resolver.resolve(PlayableItem.remoteFrom(title = "A", artist = "B"))
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    /** A 403 retry must not be handed the same expired URL back from the query-keyed entry. */
    @Test
    fun invalidate_dropsQueryKeyNotOnlyVideoId() = runBlocking {
        var extractCalls = 0
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                YouTubeExtractResult.Success(
                    YouTubeStreamResult(
                        videoId = "vid12345678",
                        title = "Song",
                        artist = "Artist",
                        artworkUrl = null,
                        durationMs = 180_000L,
                        audioUrl = "https://googlevideo.example/audio-$extractCalls",
                        userAgent = "TestUA"
                    )
                )
            },
            clockMs = { 1_000L },
            ttlMs = 4 * 60 * 1000L
        )

        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")
        val first = resolver.resolve(item).getOrThrow()
        assertEquals(1, extractCalls)

        // Same shape as handlePlayerError: the item carries the resolved stream that just 403'd.
        resolver.invalidate(item.copy(resolved = first))
        val retried = resolver.resolve(item).getOrThrow()

        assertEquals(2, extractCalls)
        assertTrue(retried.audioUrl.endsWith("-2"))
    }

    @Test
    fun resolveQuery_forceRefresh_bypassesPlaybackCache() = runBlocking {
        var extractCalls = 0
        val resolver = StreamResolver(
            extract = {
                extractCalls++
                YouTubeExtractResult.Success(
                    YouTubeStreamResult(
                        videoId = "vid12345678",
                        title = "Song",
                        artist = "Artist",
                        artworkUrl = null,
                        durationMs = 180_000L,
                        audioUrl = "https://googlevideo.example/audio-$extractCalls",
                        userAgent = "TestUA"
                    )
                )
            },
            clockMs = { 1_000L },
            ttlMs = 4 * 60 * 1000L
        )

        val item = PlayableItem.remoteFrom(title = "Song", artist = "Artist")
        resolver.resolve(item).getOrThrow()
        assertEquals(1, extractCalls)

        val refreshed = resolver.resolveQuery("Artist Song", forceRefresh = true).getOrThrow()
        assertEquals(2, extractCalls)
        assertTrue(refreshed.audioUrl.endsWith("-2"))
        assertEquals("Song", refreshed.title)
    }

    private fun successfulExtract(call: Int): YouTubeExtractResult.Success =
        YouTubeExtractResult.Success(
            YouTubeStreamResult(
                videoId = "vid12345678",
                title = "Song",
                artist = "Artist",
                artworkUrl = null,
                durationMs = 180_000L,
                audioUrl = "https://googlevideo.example/audio-$call",
                userAgent = "TestUA"
            )
        )
}
