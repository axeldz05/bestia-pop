package com.bestiapop.android.domain.util

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
    fun unicodeEllipsisMatchesAsciiDots() {
        assertEquals("Takk...", normalizeAlbumName(takkUnicodeEllipsis))
        assertEquals("Takk...", normalizeAlbumName(takkAsciiDots))
        assertTrue(albumNamesMatch(takkUnicodeEllipsis, takkAsciiDots))
    }

    @Test
    fun deviceMojibakeEllipsisMatchesAsciiDots() {
        assertEquals("Takk...", normalizeAlbumName(takkMojibake))
        assertTrue(albumNamesMatch(takkMojibake, takkAsciiDots))
        assertTrue(albumNamesMatch(takkMojibake, takkUnicodeEllipsis))
    }

    @Test
    fun singlePeriodDoesNotMatchEllipsis() {
        assertEquals("Takk.", normalizeAlbumName(takkPeriod))
        assertFalse(albumNamesMatch(takkPeriod, takkAsciiDots))
        assertFalse(albumNamesMatch(takkPeriod, takkUnicodeEllipsis))
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
}
