package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload
import com.bestiapop.android.data.listenbrainz.CfRecommendedRecording
import com.bestiapop.android.data.listenbrainz.LbApiResult
import com.bestiapop.android.data.listenbrainz.LbRecordingMetadata
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchAndMatchCfRecommendationsUseCaseTest {

    private fun song(id: Long, title: String, artist: String) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 180_000L
    )

    @Test
    fun matchesLocalAndEmitsRemoteForUnmatched() = runBlocking {
        val useCase = FetchAndMatchCfRecommendationsUseCase(
            fetchCf = { _, _, _, _, _ ->
                LbApiResult.Success(
                    CfRecommendationsPayload(
                        userName = "user",
                        recordings = listOf(
                            CfRecommendedRecording("mbid-local", 9.0),
                            CfRecommendedRecording("mbid-remote", 8.0),
                            CfRecommendedRecording("mbid-missing-meta", 7.0)
                        )
                    )
                )
            },
            fetchRecordingMetadata = { _, _ ->
                LbApiResult.Success(
                    mapOf(
                        "mbid-local" to LbRecordingMetadata(
                            recordingMbid = "mbid-local",
                            title = "Local Hit",
                            artist = "Artist A"
                        ),
                        "mbid-remote" to LbRecordingMetadata(
                            recordingMbid = "mbid-remote",
                            title = "Remote Hit",
                            artist = "Artist B",
                            album = "EP"
                        )
                    )
                )
            }
        )

        val result = useCase.execute(
            username = "user",
            token = "token",
            library = listOf(song(1, "Local Hit", "Artist A"))
        )

        assertTrue(result is LbApiResult.Success)
        val matched = (result as LbApiResult.Success).data
        assertEquals(2, matched.matches.size)
        assertEquals(1, matched.matchedCount)
        assertEquals(1, matched.streamCount)
        assertEquals("Local Hit", matched.matches[0].title)
        assertEquals(1L, matched.matches[0].localSong?.id)
        assertEquals("Remote Hit", matched.matches[1].title)
        assertNull(matched.matches[1].localSong)
        assertEquals("EP", matched.matches[1].album)
    }

    @Test
    fun emptyPayloadReturnsEmptyMatches() = runBlocking {
        val useCase = FetchAndMatchCfRecommendationsUseCase(
            fetchCf = { _, _, _, _, _ ->
                LbApiResult.Success(
                    CfRecommendationsPayload(userName = "user", recordings = emptyList())
                )
            },
            fetchRecordingMetadata = { _, _ ->
                error("should not fetch metadata for empty CF")
            }
        )

        val result = useCase.execute(
            username = "user",
            token = "token",
            library = emptyList()
        )

        assertTrue(result is LbApiResult.Success)
        assertTrue((result as LbApiResult.Success).data.matches.isEmpty())
    }
}
