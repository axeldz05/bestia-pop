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
    fun renamingTakkPeriodToAsciiDotsFindsUnicodeEllipsisAlbum() {
        // Real device case: user edits "Takk." → types "Takk..." to match "Takk…"
        val albums = listOf(
            album(takkPeriod, songCount = 8),
            album(takkUnicodeEllipsis, songCount = 1),
            album(takkMojibake, songCount = 1)
        )
        val target = findAlbumMergeTarget(albums, takkPeriod, takkAsciiDots)
        assertEquals(takkUnicodeEllipsis, target?.name)
    }

    @Test
    fun renamingToUnicodeEllipsisFindsAsciiDotsAlbum() {
        val albums = listOf(
            album(takkPeriod, songCount = 8),
            album(takkAsciiDots, songCount = 2)
        )
        val target = findAlbumMergeTarget(albums, takkPeriod, takkUnicodeEllipsis)
        assertEquals(takkAsciiDots, target?.name)
    }

    @Test
    fun prefersLargerConflictingAlbum() {
        val albums = listOf(
            album(takkPeriod, songCount = 8),
            album(takkUnicodeEllipsis, songCount = 1),
            album(takkMojibake, songCount = 5)
        )
        val target = findAlbumMergeTarget(albums, takkPeriod, takkAsciiDots)
        assertEquals(takkMojibake, target?.name)
    }

    @Test
    fun findEquivalentAlbumKeysIncludesEllipsisVariants() {
        val keys = listOf(takkPeriod, takkUnicodeEllipsis, takkMojibake, "Other")
        val equiv = findEquivalentAlbumKeys(keys, takkAsciiDots, excludeKey = takkUnicodeEllipsis)
        assertEquals(setOf(takkMojibake), equiv.toSet())
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
