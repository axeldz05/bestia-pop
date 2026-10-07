package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistDiscographyRankingTest {
    @Test
    fun testSelectBestArtistHit_resolvesHomonymByPopularity() {
        val jsonArray =
            JSONArray(
                """
                [
                    {"id": 305087261, "name": "Exo", "nb_fan": 16, "nb_album": 1},
                    {"id": 12973277, "name": "Exo", "nb_fan": 42, "nb_album": 6},
                    {"id": 68752042, "name": "Exo", "nb_fan": 10, "nb_album": 3},
                    {"id": 88684, "name": "EXO", "nb_fan": 441389, "nb_album": 35},
                    {"id": 14256285, "name": "Exo", "nb_fan": 5869, "nb_album": 3}
                ]
                """.trimIndent(),
            )

        val hit = MetadataFetcher.selectBestArtistHit(jsonArray, "Exo")
        assertNotNull(hit)
        assertEquals(88684L, hit?.id)
    }

    @Test
    fun testFilterTracksForArtist_removesUnrelatedKeywordMatches() {
        val tracks =
            listOf(
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Love Shot", artist = "EXO", album = "LOVE SHOT"),
                    id = "1",
                ),
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Ex", artist = "OBOY", album = "OBOY"),
                    id = "2",
                ),
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Dancing King (feat. EXO)", artist = "Yu Jae Seok", album = "SM STATION"),
                    id = "3",
                ),
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Monster", artist = "EXO", album = "EX'ACT"),
                    id = "4",
                ),
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Unrelated Song", artist = "Random Artist", album = "Random Album"),
                    id = "5",
                ),
            )

        val filtered = MetadataFetcher.filterTracksForArtist(tracks, "Exo")
        val titles = filtered.map { it.identity.title }

        assertTrue(titles.contains("Love Shot"))
        assertTrue(titles.contains("Dancing King (feat. EXO)"))
        assertTrue(titles.contains("Monster"))
        assertTrue(!titles.contains("Ex"))
        assertTrue(!titles.contains("Unrelated Song"))
        assertEquals(3, filtered.size)
    }

    @Test
    fun testPartitionDiscography_correctlyClassifiesReleasesAndAppearances() {
        val rawAlbums =
            listOf(
                CatalogAlbum(
                    id = "101",
                    title = "Animaru",
                    artist = "Mei Semones",
                    coverUrl = "http://cover/101",
                    trackCount = 10,
                    recordType = "album",
                ),
                CatalogAlbum(
                    id = "102",
                    title = "Kurage",
                    artist = "Mei Semones",
                    coverUrl = "http://cover/102",
                    trackCount = 4,
                    recordType = "ep",
                ),
                CatalogAlbum(
                    id = "103",
                    title = "Tooth Fairy",
                    artist = "Mei Semones",
                    coverUrl = "http://cover/103",
                    trackCount = 1,
                    recordType = "single",
                ),
                CatalogAlbum(
                    id = "104",
                    title = "Enigami",
                    artist = "Luna Li",
                    coverUrl = "http://cover/104",
                    trackCount = 1,
                    recordType = "single",
                ),
                CatalogAlbum(
                    id = "105",
                    title = "Indie Compilation 2024",
                    artist = "Mei Semones",
                    coverUrl = "http://cover/105",
                    trackCount = 15,
                    recordType = "compile",
                ),
            )

        val topTracks =
            listOf(
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Dumb Feeling", artist = "Mei Semones", album = "Animaru"),
                    id = "t1",
                ),
                OnlineCatalogTrack(
                    identity = TrackIdentity(title = "Crush On Eternity", artist = "Willow", album = "The Thread"),
                    id = "t2",
                ),
            )

        val (albums, singlesAndEps, appearedOn) =
            MetadataFetcher.partitionDiscography(rawAlbums, topTracks, "Mei Semones")

        assertEquals(1, albums.size)
        assertEquals("Animaru", albums[0].title)

        assertEquals(2, singlesAndEps.size)
        val singleTitles = singlesAndEps.map { it.title }
        assertTrue(singleTitles.contains("Kurage"))
        assertTrue(singleTitles.contains("Tooth Fairy"))

        val appearedTitles = appearedOn.map { it.title }
        assertTrue(appearedTitles.contains("Enigami"))
        assertTrue(appearedTitles.contains("Indie Compilation 2024"))
        assertTrue(appearedTitles.contains("The Thread"))
        assertEquals(3, appearedOn.size)
    }

    @Test
    fun testSelectBestArtistHit_preservesArtistName() {
        val jsonArray =
            JSONArray(
                """
                [
                    {"id": 130058152, "name": "Parannoul", "nb_fan": 3150, "nb_album": 18}
                ]
                """.trimIndent(),
            )

        val hit = MetadataFetcher.selectBestArtistHit(jsonArray, "파란노을")
        assertNotNull(hit)
        assertEquals(130058152L, hit?.id)
        assertEquals("Parannoul", hit?.name)
    }

    @Test
    fun testIsAlbumMatching_bilingualAliasMatches() {
        // Without alias, "Parannoul" vs "파란노을" does not match due to slight transliteration variance
        assertFalse(
            MetadataFetcher.isAlbumMatching(
                candidateTitle = "To See the Next Part of the Dream",
                candidateArtist = "Parannoul",
                targetTitle = "To See the Next Part of the Dream",
                targetArtist = "파란노을",
                artistAlias = null,
            ),
        )

        // With alias resolved from catalog (e.g. Parannoul), it matches
        assertTrue(
            MetadataFetcher.isAlbumMatching(
                candidateTitle = "To See the Next Part of the Dream",
                candidateArtist = "Parannoul",
                targetTitle = "To See the Next Part of the Dream",
                targetArtist = "파란노을",
                artistAlias = "Parannoul",
            ),
        )

        // Symmetrically, target Parannoul with candidate 파란노을 and alias 파란노을 matches
        assertTrue(
            MetadataFetcher.isAlbumMatching(
                candidateTitle = "To See the Next Part of the Dream",
                candidateArtist = "파란노을",
                targetTitle = "To See the Next Part of the Dream",
                targetArtist = "Parannoul",
                artistAlias = "파란노을",
            ),
        )
    }

    @Test
    fun testIsAlbumMatching_rejectsMismatchedArtistEvenWithAlias() {
        assertFalse(
            MetadataFetcher.isAlbumMatching(
                candidateTitle = "To See the Next Part of the Dream",
                candidateArtist = "Asian Glow",
                targetTitle = "To See the Next Part of the Dream",
                targetArtist = "파란노을",
                artistAlias = "Parannoul",
            ),
        )
    }

    @Test
    fun testIsArtistMatching_handlesExactAndTransliteration() {
        assertTrue(MetadataFetcher.isArtistMatching("Parannoul", "Parannoul"))
        assertTrue(MetadataFetcher.isArtistMatching("Asian Kung-Fu Generation", "Asian Kung-Fu Generation"))
        // Transliteration exact match (e.g. Cyrillic)
        assertTrue(MetadataFetcher.isArtistMatching("Kino", "Кино"))
        assertFalse(MetadataFetcher.isArtistMatching("Taylor Swift", "Kanye West"))
    }
}
