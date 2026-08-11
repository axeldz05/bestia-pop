package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.network.MetadataFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyCatalogQueryTest {

    @Test
    fun build_advancedArtistAlbumAndYear() {
        val q = IdentifyCatalogQuery.build(
            freeText = "Creep",
            filters = IdentifySearchFilters(artist = "Radiohead", album = "Pablo Honey", year = 1992)
        )
        assertTrue(q.contains("artist:\"Radiohead\""))
        assertTrue(q.contains("album:\"Pablo Honey\""))
        assertTrue(q.contains("Creep"))
        assertTrue(q.endsWith("1992") || q.contains(" 1992"))
    }

    @Test
    fun build_filtersOnly() {
        val q = IdentifyCatalogQuery.build(
            freeText = null,
            filters = IdentifySearchFilters(artist = "Muse", album = "Absolution")
        )
        assertEquals("artist:\"Muse\" album:\"Absolution\"", q)
    }

    @Test
    fun filters_hasAnyIgnoresBlank() {
        assertFalse(IdentifySearchFilters().hasAny)
        assertTrue(IdentifySearchFilters(year = 2001).hasAny)
        assertTrue(IdentifySearchFilters(artist = "  A  ").normalized().artist == "A")
    }

    @Test
    fun parseReleaseYear_isoPrefix() {
        assertEquals(2012, MetadataFetcher.parseReleaseYear("2012-03-01"))
        assertEquals(1997, MetadataFetcher.parseReleaseYear("1997"))
        assertEquals(0, MetadataFetcher.parseReleaseYear(""))
        assertEquals(0, MetadataFetcher.parseReleaseYear(null))
    }
}
