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

    @Test
    fun audioPreferenceScore_penalizesLiveStreamsAndGameplay() {
        val liveScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "eFootball en directo",
            rawAuthor = "GamerStreamer",
            candidateDurationMs = 0L,
            expectedDurationMs = 200_000L,
            expectedTitle = "Developments",
            expectedArtist = "Hands Like Houses",
            isLive = true
        )

        val studioScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Hands Like Houses - Developments",
            rawAuthor = "riserecords",
            candidateDurationMs = 220_000L,
            expectedDurationMs = 200_000L,
            expectedTitle = "Developments",
            expectedArtist = "Hands Like Houses"
        )

        assertTrue(
            "Studio track ($studioScore) must beat live stream ($liveScore)",
            studioScore > liveScore + 250
        )
    }

    @Test
    fun audioPreferenceScore_rewardsAlbumMatch() {
        val withAlbum = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Hands Like Houses - Developments (Unimagine)",
            rawAuthor = "riserecords",
            candidateDurationMs = 200_000L,
            expectedDurationMs = 200_000L,
            expectedTitle = "Developments",
            expectedArtist = "Hands Like Houses",
            expectedAlbum = "Unimagine"
        )

        val withoutAlbum = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Hands Like Houses - Developments",
            rawAuthor = "riserecords",
            candidateDurationMs = 200_000L,
            expectedDurationMs = 200_000L,
            expectedTitle = "Developments",
            expectedArtist = "Hands Like Houses",
            expectedAlbum = "Unimagine"
        )

        assertTrue(
            "Title containing expected album should score higher ($withAlbum vs $withoutAlbum)",
            withAlbum > withoutAlbum
        )
    }

    @Test
    fun rankByAudioPreference_rejectsIrrelevantShortsAndPrefersCorrectSong() {
        data class TestHit(
            val title: String,
            val author: String,
            val durationMs: Long,
            val isLive: Boolean = false
        )

        val hits = listOf(
            TestHit(
                title = "Con este truco NO se quemará mas adios a la obsolescencia programada",
                author = "MICROTECNICA",
                durationMs = 0L
            ),
            TestHit(
                title = "Hands Like Houses - Developments",
                author = "riserecords",
                durationMs = 220_000L
            ),
            TestHit(
                title = "YUJUUUUUUUUUUUUU- EFOOTBALL",
                author = "Flaco Luks",
                durationMs = 0L,
                isLive = true
            )
        )

        val ranked = YouTubeExtractor.rankByAudioPreference(
            items = hits,
            rawTitle = { it.title },
            rawAuthor = { it.author },
            isLiveOf = { it.isLive },
            durationMsOf = { it.durationMs },
            expectedDurationMs = 200_000L,
            expectedTitle = "Developments",
            expectedArtist = "Hands Like Houses",
            expectedAlbum = "Unimagine"
        )

        assertTrue("First ranked must be the official track", ranked.first().author == "riserecords")
        assertTrue("Irrelevant trending video must not be first", ranked.first().author != "MICROTECNICA")
    }

    @Test
    fun resolveYouTubeQueryOrId_neverReturnsBareIsrc() {
        val trackWithIsrc = com.bestiapop.android.data.model.OnlineCatalogTrack(
            identity = com.bestiapop.android.data.model.TrackIdentity(
                title = "Developments",
                artist = "Hands Like Houses",
                album = "Unimagine"
            ),
            id = "USEK71320601",
            audioUrl = "USEK71320601"
        )

        val query = YouTubeExtractor.resolveYouTubeQueryOrId(trackWithIsrc)
        assertTrue(
            "Query must be artist + title instead of raw ISRC ($query)",
            query == "Hands Like Houses Developments"
        )
    }

    @Test
    fun audioPreferenceScore_withTrackMeta_rewardsMatchingSong() {
        val targetTrack = com.bestiapop.android.data.model.TrackIdentity(
            title = "Developments",
            artist = "Hands Like Houses",
            album = "Unimagine",
            durationMs = 220_000L
        )

        val matchingScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Hands Like Houses - Developments (Unimagine)",
            rawAuthor = "riserecords",
            candidateDurationMs = 220_000L,
            expected = targetTrack
        )

        val irrelevantScore = YouTubeExtractor.audioPreferenceScore(
            rawTitle = "Reparacion de focos LED en casa",
            rawAuthor = "MICROTECNICA",
            candidateDurationMs = 0L,
            expected = targetTrack
        )

        assertTrue(
            "Matching track ($matchingScore) must significantly beat irrelevant video ($irrelevantScore)",
            matchingScore > irrelevantScore + 200
        )
    }
}

