package com.bestiapop.android.data.stream

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.network.YouTubeExtractResult
import com.bestiapop.android.data.network.YouTubeStreamResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun resolve_propagatesExtractorError() = runBlocking {
        val resolver = StreamResolver(
            extract = { YouTubeExtractResult.Error("boom") },
            clockMs = { 0L }
        )
        val result = resolver.resolve(PlayableItem.remoteFrom(title = "A", artist = "B"))
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
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
}
