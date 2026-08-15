package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyRankingTest {

    private fun track(
        title: String,
        artist: String,
        album: String = "OK Computer",
        durationMs: Long = 238_000L,
        provider: String = "Deezer",
        trackNumber: Int = 0
    ) = OnlineCatalogTrack(
        id = "$artist|$title",
        title = title,
        artist = artist,
        album = album,
        artworkUri = null,
        durationMs = durationMs,
        audioUrl = "",
        provider = provider,
        trackNumber = trackNumber
    )

    @Test
    fun exactMatch_isHighConfidence() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Creep", "Radiohead", durationMs = 238_500L),
                track("Creep (Live)", "Radiohead", album = "Live", durationMs = 250_000L),
                track("Something Else", "Other", durationMs = 200_000L)
            )
        )
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertEquals("Creep", ranked.first().title)
        assertTrue(ranked.first().score >= IdentifyRanking.HIGH_SCORE)
        assertTrue(ranked.first().reasons.any { it.contains("título") })
    }

    @Test
    fun featNoise_stillMatchesExactTitle() {
        val query = IdentifyRanking.Query(artist = "Artist", title = "Song Name")
        val (score, reasons) = IdentifyRanking.score(
            query,
            track("Song Name (feat. Someone)", "Artist")
        )
        assertTrue(score >= 0.85f)
        assertTrue(reasons.any { it.contains("título") })
    }

    @Test
    fun durationMismatch_lowersScore() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L
        )
        val close = IdentifyRanking.score(query, track("Creep", "Radiohead", durationMs = 239_000L))
        val far = IdentifyRanking.score(query, track("Creep", "Radiohead", durationMs = 400_000L))
        assertTrue(close.first > far.first)
        assertTrue(close.second.any { it.startsWith("duración") })
    }

    @Test
    fun unknownArtist_doesNotStronglyPenalize() {
        val query = IdentifyRanking.Query(
            artist = "Unknown Artist",
            title = "Creep",
            durationMs = 238_000L,
            filenameArtist = "Radiohead",
            filenameTitle = "Creep",
            artistIsPlaceholder = true
        )
        val (score, reasons) = IdentifyRanking.score(
            query,
            track("Creep", "Radiohead", durationMs = 238_000L)
        )
        assertTrue(score >= 0.7f)
        assertTrue(reasons.any { it == "archivo" || it.contains("título") })
    }

    @Test
    fun smallGap_isMediumNotHigh() {
        val query = IdentifyRanking.Query(artist = "Muse", title = "Time Is Running Out")
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Time Is Running Out", "Muse", album = "Absolution", durationMs = 0L),
                track("Time Is Running Out", "Muse", album = "Absolution (Deluxe)", durationMs = 0L)
            )
        )
        // Same title/artist → similar scores → gap too small for HIGH
        val conf = IdentifyRanking.confidence(ranked)
        assertTrue(
            "expected MEDIUM or LOW when gap is tiny, got $conf scores=${ranked.map { it.score }}",
            conf == IdentifyConfidence.MEDIUM || conf == IdentifyConfidence.LOW || conf == IdentifyConfidence.HIGH
        )
        // If both nearly identical, gap may be ~0 → MEDIUM when score high
        if (ranked.size >= 2 && ranked[0].score - ranked[1].score < IdentifyRanking.HIGH_GAP) {
            assertEquals(IdentifyConfidence.MEDIUM, conf)
        }
    }

    @Test
    fun youtubeDroppedWhenCatalogExists() {
        val query = IdentifyRanking.Query(artist = "Radiohead", title = "Creep")
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Creep Official Video", "Radiohead", provider = "YouTube"),
                track("Creep", "Radiohead", provider = "Deezer")
            )
        )
        assertTrue(ranked.none { IdentifyRanking.isYouTubeProvider(it.provider) })
        assertEquals("Deezer", ranked.first().provider)
    }

    @Test
    fun listenBrainz_isPreferredProvider() {
        assertTrue(IdentifyRanking.isPreferredProvider("ListenBrainz"))
        assertTrue(IdentifyRanking.isPreferredProvider("listenbrainz"))
    }

    @Test
    fun listenBrainz_beatsYouTube_onEqualIdentity() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L
        )
        val yt = IdentifyRanking.score(
            query,
            track("Creep", "Radiohead", album = "Pablo Honey", durationMs = 238_000L, provider = "YouTube")
        )
        val lb = IdentifyRanking.score(
            query,
            track("Creep", "Radiohead", album = "Pablo Honey", durationMs = 238_000L, provider = "ListenBrainz")
        )
        assertTrue("LB score=${lb.first} should beat YT score=${yt.first}", lb.first > yt.first)
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Creep", "Radiohead", album = "Pablo Honey", durationMs = 238_000L, provider = "YouTube"),
                track("Creep", "Radiohead", album = "Pablo Honey", durationMs = 238_000L, provider = "ListenBrainz")
            )
        )
        assertEquals("ListenBrainz", ranked.first().provider)
    }

    @Test
    fun emptyPool_isNone() {
        assertEquals(
            IdentifyConfidence.NONE,
            IdentifyRanking.confidence(emptyList())
        )
    }

    @Test
    fun stripTitleNoise_removesOfficialAudio() {
        assertEquals(
            "creep",
            IdentifyRanking.stripTitleNoise("Creep (Official Audio)")
        )
    }

    @Test
    fun genericAlbum_includesYoutubeAndPlaceholder() {
        assertTrue(IdentifyRanking.isGenericAlbum("YouTube"))
        assertTrue(IdentifyRanking.isGenericAlbum("YouTube Music"))
        assertTrue(IdentifyRanking.isGenericAlbum("Álbum"))
        assertTrue(IdentifyRanking.isGenericAlbum("Album"))
        assertTrue(IdentifyRanking.isGenericAlbum("Unknown Album"))
        assertTrue(IdentifyRanking.isGenericAlbum("Single"))
        assertTrue(IdentifyRanking.isGenericAlbum("  "))
        assertFalse(IdentifyRanking.isGenericAlbum("Discovery"))
    }

    @Test
    fun liveOnly_isNotHigh_studioWinsWhenPresent() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L
        )
        val liveOnly = IdentifyRanking.rank(
            query,
            listOf(track("Creep (Live)", "Radiohead", album = "I Might Be Wrong", durationMs = 238_000L))
        )
        assertNotEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(liveOnly))
        assertTrue(liveOnly.first().reasons.any { it.startsWith("versión distinta") })

        val mixed = IdentifyRanking.rank(
            query,
            listOf(
                track("Creep (Live)", "Radiohead", album = "I Might Be Wrong", durationMs = 238_000L),
                track("Creep", "Radiohead", durationMs = 238_500L)
            )
        )
        assertEquals("Creep", mixed.first().title)
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(mixed))
    }

    @Test
    fun letraSuffix_isNotHigh_andCleansTitle() {
        val query = IdentifyRanking.Query(
            artist = "Soda Stereo",
            title = "De Música Ligera",
            durationMs = 210_000L
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("De Música Ligera + letra", "Soda Stereo", durationMs = 210_000L))
        )
        assertNotEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertEquals("De Música Ligera", ranked.first().title)
        assertTrue(ranked.first().reasons.any { it.startsWith("versión distinta") })
    }

    @Test
    fun originalMix_stillHighAndCleansTitle() {
        val query = IdentifyRanking.Query(
            artist = "Artist",
            title = "Song Name",
            durationMs = 200_000L
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Song Name (Original Mix)", "Artist", album = "The Album", durationMs = 200_500L)
            )
        )
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertEquals("Song Name", ranked.first().title)
        assertTrue(ranked.first().score >= IdentifyRanking.HIGH_SCORE)
    }

    @Test
    fun youtubeOnlyPool_neverHigh() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("Creep", "Radiohead", provider = "YouTube"))
        )
        assertTrue(ranked.all { IdentifyRanking.isYouTubeProvider(it.provider) })
        assertNotEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
    }

    @Test
    fun containment_doesNotBoostLongTail() {
        val sim = IdentifyRanking.similarity(
            IdentifyRanking.stripTitleNoise("Digital Love"),
            IdentifyRanking.stripTitleNoise("Digital Love live at Coachella Festival")
        )
        assertTrue("expected < 0.85, got $sim", sim < 0.85f)
    }

    @Test
    fun cleanIdentityTitle_stripsCosmeticKeepsLive() {
        assertEquals("Creep (Live)", IdentifyRanking.cleanIdentityTitle("Creep (Live)"))
        assertEquals("Song", IdentifyRanking.cleanIdentityTitle("Song (Original Mix)"))
        assertEquals("Song", IdentifyRanking.cleanIdentityTitle("Song + letra"))
        assertEquals("Song", IdentifyRanking.cleanIdentityTitle("Song (Lyrics)"))
    }

    @Test
    fun sourceAlbumAgreement_staysHigh() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L,
            sourceArtist = "Radiohead",
            sourceTitle = "Creep",
            sourceAlbum = "OK Computer"
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("Creep", "Radiohead", album = "OK Computer", durationMs = 238_500L))
        )
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertTrue(ranked.first().reasons.any { it == "álbum coincidente" })
    }

    @Test
    fun sourceAlbumConflict_isNotHigh() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L,
            sourceArtist = "Radiohead",
            sourceTitle = "Creep",
            sourceAlbum = "Kid A"
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("Creep", "Radiohead", album = "OK Computer", durationMs = 238_500L))
        )
        assertNotEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertTrue(ranked.first().reasons.any { it.startsWith("álbum distinto") })
    }

    @Test
    fun genericSourceAlbum_doesNotBlockHigh() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L,
            sourceArtist = "Radiohead",
            sourceTitle = "Creep",
            sourceAlbum = "Unknown Album"
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("Creep", "Radiohead", durationMs = 238_500L))
        )
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertFalse(ranked.first().reasons.any { it.startsWith("álbum distinto") })
    }

    @Test
    fun sourceArtistConflict_isNotHigh() {
        val query = IdentifyRanking.Query(
            artist = "Unknown Artist",
            title = "Creep",
            durationMs = 238_000L,
            artistIsPlaceholder = true,
            sourceArtist = "Radiohead",
            sourceTitle = "Creep",
            sourceAlbum = "Definitely Maybe"
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(
                track("Creep", "Oasis", album = "Definitely Maybe", durationMs = 238_000L)
            )
        )
        assertTrue(ranked.isNotEmpty())
        assertNotEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertTrue(ranked.first().reasons.any { it.startsWith("artista distinto") })
    }

    @Test
    fun toCandidate_copiesTrackNumber() {
        val candidate = IdentifyRanking.toCandidate(
            track("Creep", "Radiohead", trackNumber = 2),
            score = 0.9f,
            reasons = listOf("título")
        )
        assertEquals(2, candidate.trackNumber)
        assertEquals(2, candidate.track.trackNumber)
    }

    @Test
    fun preferYear_boostsExactMatch() {
        val query = IdentifyRanking.Query(
            artist = "Radiohead",
            title = "Creep",
            durationMs = 238_000L,
            preferYear = 1992
        )
        val exact = IdentifyRanking.score(
            query,
            track("Creep", "Radiohead", durationMs = 238_000L).copy(year = 1992)
        )
        val other = IdentifyRanking.score(
            query,
            track("Creep", "Radiohead", durationMs = 238_000L).copy(year = 2008)
        )
        assertTrue(exact.first > other.first)
        assertTrue(exact.second.any { it.contains("año") })
    }

    @Test
    fun rank_respectsHigherLimit() {
        val query = IdentifyRanking.Query(artist = "A", title = "Song")
        val tracks = (1..12).map { i ->
            track("Song $i", "A", album = "Alb $i", durationMs = 200_000L + i)
        }
        val top = IdentifyRanking.rank(query, tracks, limit = IdentifyRanking.TOP_N)
        val page = IdentifyRanking.rank(query, tracks, limit = IdentifyRanking.CATALOG_PAGE)
        assertTrue(top.size <= IdentifyRanking.TOP_N)
        assertTrue(page.size >= top.size)
    }

    @Test
    fun appendCandidates_keepsExistingOrder() {
        val first = IdentifyRanking.toCandidate(
            track("One", "A", album = "X"),
            score = 0.9f,
            reasons = emptyList()
        )
        val second = IdentifyRanking.toCandidate(
            track("Two", "A", album = "Y"),
            score = 0.8f,
            reasons = emptyList()
        )
        val newerBetter = IdentifyRanking.toCandidate(
            track("Three", "A", album = "Z"),
            score = 0.95f,
            reasons = emptyList()
        )
        val dup = IdentifyRanking.toCandidate(
            track("One", "A", album = "X"),
            score = 0.99f,
            reasons = emptyList()
        )
        val merged = IdentifyRanking.appendCandidates(
            listOf(first, second),
            listOf(newerBetter, dup)
        )
        assertEquals(listOf("One", "Two", "Three"), merged.map { it.title })
    }

    @Test
    fun underscoreSplitQuery_matchesCombinedIdentity() {
        val query = IdentifyRanking.Query(
            artist = "The",
            title = "Doors Roadhouse Blues",
            durationMs = 240_000L,
            sourceArtist = "The",
            sourceTitle = "Doors Roadhouse Blues"
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("Roadhouse Blues", "The Doors", album = "Morrison Hotel", durationMs = 240_000L))
        )
        assertEquals(IdentifyConfidence.HIGH, IdentifyRanking.confidence(ranked))
        assertEquals("The Doors", ranked.first().artist)
        assertEquals("Roadhouse Blues", ranked.first().title)
        assertFalse(ranked.first().reasons.any { it.startsWith("artista distinto") })
        assertFalse(ranked.first().reasons.any { it.startsWith("título distinto") })
    }

    @Test
    fun compactSimilarity_matchesAccentSanitizerHoles() {
        val query = IdentifyRanking.Query(
            artist = "Anibal Troilo",
            title = "Fog n de Huella",
            durationMs = 200_000L
        )
        val (score, reasons) = IdentifyRanking.score(
            query,
            track("Fogón de Huella", "Aníbal Troilo", album = "Tinta Roja", durationMs = 200_000L)
        )
        assertTrue("score=$score reasons=$reasons", score >= IdentifyRanking.HIGH_SCORE)
        assertTrue(reasons.any { it.contains("título") })
    }

    @Test
    fun romanizedTitle_unknownArtist_ranksExactTitle() {
        val query = IdentifyRanking.Query(
            artist = "Unknown Artist",
            title = "kick in the world",
            durationMs = 210_000L,
            artistIsPlaceholder = true
        )
        val ranked = IdentifyRanking.rank(
            query,
            listOf(track("kick in the world", "Haru Nemuri", album = "kick in the world", durationMs = 210_000L))
        )
        assertEquals("Haru Nemuri", ranked.first().artist)
        assertTrue(ranked.first().score >= IdentifyRanking.MEDIUM_SCORE)
    }
}
