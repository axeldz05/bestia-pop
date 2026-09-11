package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumNamesTest {

    /** Exact strings observed on device for Sigur Rós "Takk" variants. */
    private val takkPeriod = "Takk."
    private val takkAsciiDots = "Takk..."
    private val takkUnicodeEllipsis = "Takk\u2026" // Takk…
    // hex 54616B6BC3A2C280C2A6 from device DB
    private val takkMojibake = "Takk\u00E2\u0080\u00A6"

    @Test
    fun ellipsisAndPunctuationVariants_normalizeAndMatchAsciiDots() {
        assertEquals("Takk...", normalizeAlbumName(takkUnicodeEllipsis))
        assertEquals("Takk...", normalizeAlbumName(takkAsciiDots))
        assertEquals("Takk...", normalizeAlbumName(takkMojibake))
        assertEquals("Takk.", normalizeAlbumName(takkPeriod))

        listOf(takkUnicodeEllipsis, takkMojibake, takkPeriod).forEach { variant ->
            assertTrue(albumNamesMatch(variant, takkAsciiDots))
            assertTrue(albumNamesMatch(variant, takkUnicodeEllipsis))
        }
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("Takk...", normalizeAlbumName("  Takk…  "))
        assertTrue(albumNamesMatch(" Takk... ", "Takk\u2026"))
    }

    @Test
    fun classicEuroMojibakeEllipsis() {
        val classic = "Takk\u00E2\u20AC\u00A6" // â€¦
        assertEquals("Takk...", normalizeAlbumName(classic))
        assertTrue(albumNamesMatch(classic, takkAsciiDots))
    }

    @Test
    fun caseAndDiacritics_shareIdentity() {
        assertTrue(albumNamesMatch("eureka", "Eureka"))
        assertTrue(albumNamesMatch("Gling-Gló", "Gling-Glo"))
        assertEquals(albumIdentityKey("eureka"), albumIdentityKey("Eureka"))
    }

    @Test
    fun deluxeAndRemaster_shareIdentityWithPlainName() {
        assertTrue(albumNamesMatch("Foo", "Foo (Deluxe)"))
        assertTrue(albumNamesMatch("Foo", "Foo (Deluxe Edition)"))
        assertTrue(albumNamesMatch("Foo", "Foo (Remastered 2011)"))
        assertTrue(albumNamesMatch("Absolution", "Absolution - Remastered"))
        assertEquals("Foo", stripAlbumEditionDecor("Foo (Deluxe)"))
        assertEquals("Foo", stripAlbumEditionDecor("Foo (Remastered 2011)"))
    }

    @Test
    fun epAndOtherScriptSubtitle_shareStem() {
        assertTrue(albumNamesMatch("Balance - EP", "Balance - 平衡"))
        assertEquals("Balance", stripAlbumEditionDecor("Balance - EP"))
        assertEquals("Balance", stripAlbumEditionDecor("Balance - 平衡"))
    }

    @Test
    fun wallStreet_doesNotCollapseToTheWall() {
        assertFalse(albumNamesMatch("The Wall", "The Wall Street"))
        assertEquals("The Wall Street", stripAlbumEditionDecor("The Wall Street"))
    }

    @Test
    fun liveSession_isDetectedWithoutMatchingStudioName() {
        assertTrue(isLiveSessionAlbum("Audiotree Live"))
        assertTrue(isLiveSessionAlbum("This Town Needs Guns (Audiotree Live)"))
        assertFalse(isLiveSessionAlbum("This Town Needs Guns"))
        assertFalse(albumNamesMatch("Audiotree Live", "This Town Needs Guns"))
    }

    @Test
    fun preferredDisplay_studioBeatsSessionEvenIfSessionIsMoreFrequent() {
        assertEquals(
            "This Town Needs Guns",
            preferredAlbumDisplayName(
                listOf("This Town Needs Guns") + List(5) { "Audiotree Live" }
            )
        )
    }

    @Test
    fun preferredDisplay_prefersPlainOverDeluxeWhenTied() {
        assertEquals(
            "Absolution",
            preferredAlbumDisplayName(listOf("Absolution (Deluxe)", "Absolution"))
        )
        assertEquals(
            "Absolution",
            preferredAlbumDisplayName(listOf("Absolution (Deluxe)"))
        )
    }

    @Test
    fun pickPersistedAlbum_reusesExistingPlainName() {
        val library = listOf(
            song(1, album = "Absolution", artist = "Muse"),
            song(2, album = "Absolution", artist = "Muse")
        )
        assertEquals(
            "Absolution",
            pickPersistedAlbumName(
                library,
                proposedAlbum = "Absolution (Deluxe)",
                proposedArtist = "Muse",
                isGeneric = IdentifyRanking::isGenericAlbum
            )
        )
    }

    @Test
    fun pickPersistedAlbum_keepsStudioWhenCandidateIsSession() {
        val library = listOf(
            song(1, album = "This Town Needs Guns", artist = "TTNG")
        )
        assertEquals(
            "This Town Needs Guns",
            pickPersistedAlbumName(
                library,
                proposedAlbum = "Audiotree Live",
                proposedArtist = "TTNG",
                sourceAlbum = "This Town Needs Guns",
                isGeneric = IdentifyRanking::isGenericAlbum
            )
        )
    }

    @Test
    fun pickPersistedAlbum_sessionFoldsIntoSoleStudioWhenSourceGeneric() {
        val library = listOf(
            song(1, album = "This Town Needs Guns", artist = "TTNG"),
            song(2, album = "Unknown Album", artist = "TTNG")
        )
        assertEquals("This Town Needs Guns", pickPersistedAlbumName(
            library,
            proposedAlbum = "Audiotree Live",
            proposedArtist = "TTNG",
            sourceAlbum = "Unknown Album",
            isGeneric = IdentifyRanking::isGenericAlbum
        ))
        assertEquals(
            pickPersistedAlbumName(
                library,
                proposedAlbum = "Audiotree Live",
                proposedArtist = "TTNG",
                sourceAlbum = "Unknown Album",
                isGeneric = IdentifyRanking::isGenericAlbum
            ),
            pickPersistedAlbumName(
                library,
                proposedAlbum = "Audiotree Live",
                proposedArtist = "TTNG",
                sourceAlbum = "Unknown Album",
                isGeneric = IdentifyRanking::isGenericAlbum,
                studioKeysByArtist = studioAlbumKeysByArtist(
                    library,
                    IdentifyRanking::isGenericAlbum
                )
            )
        )
    }

    @Test
    fun pickPersistedArtist_reusesShorterCompatibleName() {
        assertEquals(
            "Björk",
            pickPersistedArtistName(
                listOf("Björk", "Björk Trió"),
                "Björk Guðmundsdóttir & tríó Guðmundar Ingólfssonar"
            )
        )
        assertFalse(artistsCompatible("Simon", "Garfunkel"))
        assertTrue(artistsCompatible("Björk", "Björk Trió"))
    }

    @Test
    fun songsMatchingAlbumBucket_sessionTrackStaysInStudioNotDuplicateLiveHeader() {
        val library = listOf(
            song(1, album = "This Town Needs Guns", artist = "TTNG"),
            song(2, album = "Audiotree Live", artist = "TTNG"),
            song(3, album = "Audiotree Live", artist = "Other")
        )
        val isGeneric = IdentifyRanking::isGenericAlbum
        assertEquals(
            listOf(1L, 2L),
            songsMatchingAlbumBucket(library, "This Town Needs Guns", isGeneric).map { it.id }
        )
        assertEquals(
            listOf(3L),
            songsMatchingAlbumBucket(library, "Audiotree Live", isGeneric).map { it.id }
        )
    }

    private fun song(id: Long, album: String, artist: String) = Song(
        id = id,
        uriString = "file://$id",
        title = "T$id",
        artist = artist,
        album = album
    )
}
