package com.bestiapop.android.data.network

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAudioPreferenceTest {

    @Test
    fun audioPreferenceScore_severelyPenalizesSnippetsAndLoops() {
        val snippetScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Aoi, Koi, Daidaiiro No Hi (best part looped)",
            rawAuthor = "AnimeVibes",
            candidateDurationMs = 143_000L,
            expectedDurationMs = 282_000L,
            expectedTitle = "Aoi, Koi, Daidaiiro No Hi",
            expectedArtist = "MASS OF THE FERMENTING DREGS"
        )

        val fullTrackScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Aoi, Koi, Daidaiiro No Hi",
            rawAuthor = "MASS OF THE FERMENTING DREGS - Topic",
            candidateDurationMs = 282_000L,
            expectedDurationMs = 282_000L,
            expectedTitle = "Aoi, Koi, Daidaiiro No Hi",
            expectedArtist = "MASS OF THE FERMENTING DREGS"
        )

        assertTrue(
            "Full track ($fullTrackScore) must dramatically beat looped snippet ($snippetScore)",
            fullTrackScore > snippetScore + 200
        )
    }

    @Test
    fun audioPreferenceScore_rewardsDurationProximity() {
        val exactDurationScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Kakuiumono",
            rawAuthor = "MASS OF THE FERMENTING DREGS",
            candidateDurationMs = 243_000L,
            expectedDurationMs = 243_000L,
            expectedTitle = "Kakuiumono",
            expectedArtist = "MASS OF THE FERMENTING DREGS"
        )

        val wrongDurationScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Kakuiumono",
            rawAuthor = "MASS OF THE FERMENTING DREGS",
            candidateDurationMs = 100_000L,
            expectedDurationMs = 243_000L,
            expectedTitle = "Kakuiumono",
            expectedArtist = "MASS OF THE FERMENTING DREGS"
        )

        assertTrue(
            "Exact duration matching must beat truncated version",
            exactDurationScore > wrongDurationScore + 100
        )
    }

    @Test
    fun rankByAudioPreference_sortsBestCandidateFirst() {
        data class Item(val title: String, val author: String, val durationMs: Long)

        val items = listOf(
            Item("Aoi, Koi, Daidaiiro No Hi (best part looped) [Lyrics]", "LoopChannel", 143_000L),
            Item("Aoi, Koi, Daidaiiro No Hi", "MASS OF THE FERMENTING DREGS - Topic", 282_000L),
            Item("MASS OF THE FERMENTING DREGS - Aoi, Koi (Music Video)", "Official Channel", 310_000L)
        )

        val ranked = YouTubeExtractor.rankByAudioPreference(
            items = items,
            rawTitle = { it.title },
            rawAuthor = { it.author },
            durationMsOf = { it.durationMs },
            expectedDurationMs = 282_000L,
            expectedTitle = "Aoi, Koi, Daidaiiro No Hi",
            expectedArtist = "MASS OF THE FERMENTING DREGS"
        )

        assertTrue("Topic track should rank first", ranked.first().author.contains("Topic"))
        assertTrue("Looped track should not rank first", !ranked.first().title.contains("looped"))
    }
}
