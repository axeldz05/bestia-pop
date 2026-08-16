package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumMergeTest {

    private fun album(name: String, displayName: String = name, songCount: Int = 1) = Album(
        name = name,
        displayName = displayName,
        artist = "Artist",
        songCount = songCount
    )

    private val takkPeriod = "Takk."
    private val takkAsciiDots = "Takk..."
    private val takkUnicodeEllipsis = "Takk\u2026"
    private val takkMojibake = "Takk\u00E2\u0080\u00A6"

    @Test
    fun findsTargetByExactName() {
        val albums = listOf(album("Alpha"), album("Beta"))
        val target = findAlbumMergeTarget(albums, "Alpha", "Beta")
        assertEquals("Beta", target?.name)
    }

    @Test
    fun findsTargetIgnoreCaseAndWhitespace() {
        val albums = listOf(album("Alpha"), album("Beta"))
        val target = findAlbumMergeTarget(albums, "Alpha", "  beta  ")
        assertEquals("Beta", target?.name)
    }

    @Test
    fun findsTargetByDisplayName() {
        val albums = listOf(
            album("key-a", displayName = "Shown A"),
            album("key-b", displayName = "Shown B")
        )
        val target = findAlbumMergeTarget(albums, "key-a", "Shown B")
        assertEquals("key-b", target?.name)
    }

    @Test
    fun ignoresSelfEvenWithDifferentCasing() {
        val albums = listOf(album("Alpha"), album("Beta"))
        assertNull(findAlbumMergeTarget(albums, "Alpha", "alpha"))
    }

    @Test
    fun returnsNullWhenNoConflict() {
        val albums = listOf(album("Alpha"), album("Beta"))
        assertNull(findAlbumMergeTarget(albums, "Alpha", "Gamma"))
    }

    @Test
    fun renamingTakkPeriodToAsciiDotsIsAlreadySameAlbum() {
        val albums = listOf(
            album(takkPeriod, songCount = 8),
            album(takkUnicodeEllipsis, songCount = 1),
            album(takkMojibake, songCount = 1)
        )
        assertNull(findAlbumMergeTarget(albums, takkPeriod, takkAsciiDots))
    }

    @Test
    fun renamingToUnicodeEllipsisFindsUnrelatedAlbumOnlyWhenIdentityDiffers() {
        val albums = listOf(
            album(takkPeriod, songCount = 8),
            album("Other", songCount = 2)
        )
        assertNull(findAlbumMergeTarget(albums, takkPeriod, takkUnicodeEllipsis))
        assertEquals("Other", findAlbumMergeTarget(albums, takkPeriod, "Other")?.name)
    }

    @Test
    fun deluxeRenameFindsPlainAlbum() {
        val albums = listOf(
            album("Absolution (Deluxe)", songCount = 2),
            album("Origin of Symmetry", songCount = 5)
        )
        assertNull(findAlbumMergeTarget(albums, "Absolution (Deluxe)", "Absolution"))
        assertEquals(
            "Origin of Symmetry",
            findAlbumMergeTarget(albums, "Absolution (Deluxe)", "Origin of Symmetry")?.name
        )
    }

    @Test
    fun prefersLargerConflictingAlbum() {
        val albums = listOf(
            album("Alpha", songCount = 8),
            album("Beta", songCount = 1),
            album("beta", songCount = 5)
        )
        val target = findAlbumMergeTarget(albums, "Alpha", "BETA")
        assertEquals("beta", target?.name)
    }

    @Test
    fun findEquivalentAlbumKeysIncludesEllipsisAndPunctuationVariants() {
        val keys = listOf(takkPeriod, takkUnicodeEllipsis, takkMojibake, "Other")
        val equiv = findEquivalentAlbumKeys(keys, takkAsciiDots, excludeKey = takkUnicodeEllipsis)
        assertEquals(setOf(takkPeriod, takkMojibake), equiv.toSet())
    }

    @Test
    fun findEquivalentAlbumKeysEmptyWhenOnlyCanonical() {
        val keys = listOf(takkUnicodeEllipsis, "Other")
        assertEquals(
            emptyList<String>(),
            findEquivalentAlbumKeys(keys, takkAsciiDots, excludeKey = takkUnicodeEllipsis)
        )
    }
}
