package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAudioPreferenceTest {

    @Test
    fun audioPreferenceScore_prefersTopicAndOfficialAudio_overMusicVideo() {
        val topic = YouTubeExtractor.audioPreferenceScore(
            "Song Title",
            "Artist Name - Topic"
        )
        val officialAudio = YouTubeExtractor.audioPreferenceScore(
            "Artist - Song (Official Audio)",
            "Artist"
        )
        val musicVideo = YouTubeExtractor.audioPreferenceScore(
            "Artist - Song (Official Music Video)",
            "ArtistVEVO"
        )

        assertTrue(topic > musicVideo)
        assertTrue(officialAudio > musicVideo)
        assertTrue(topic >= officialAudio)
    }

    @Test
    fun rankByAudioPreference_movesAudioHitsAhead_preservingRelativeOrder() {
        data class Hit(val title: String, val author: String, val id: String)

        val ranked = YouTubeExtractor.rankByAudioPreference(
            listOf(
                Hit("Song (Official Music Video)", "ArtistVEVO", "mv1"),
                Hit("Song (Official Audio)", "Artist", "aud1"),
                Hit("Song", "Artist - Topic", "topic1"),
                Hit("Song (Lyric Video)", "Artist", "lyr1"),
                Hit("Song (Official Video)", "Artist", "mv2")
            ),
            rawTitle = { it.title },
            rawAuthor = { it.author }
        )

        assertEquals(listOf("topic1", "aud1", "lyr1", "mv2", "mv1"), ranked.map { it.id })
    }

    @Test
    fun resolveYouTubeQueryOrId_ignoresCatalogNumericIds() {
        val deezerTrack = OnlineCatalogTrack(
            id = "3135556",
            title = "Harder Better Faster Stronger",
            artist = "Daft Punk",
            album = "Discovery",
            artworkUrl = null,
            durationMs = 224000L,
            audioUrl = "Daft Punk Harder Better Faster Stronger",
            provider = "Deezer/YouTube"
        )
        assertEquals(
            "Daft Punk Harder Better Faster Stronger",
            YouTubeExtractor.resolveYouTubeQueryOrId(deezerTrack)
        )

        val youtubeTrack = deezerTrack.copy(
            id = "yT_8xqE9x0w",
            audioUrl = "https://www.youtube.com/watch?v=yT_8xqE9x0w"
        )
        assertEquals("yT_8xqE9x0w", YouTubeExtractor.resolveYouTubeQueryOrId(youtubeTrack))
    }
}
