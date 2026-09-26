package com.bestiapop.android.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTaxonomyTest {
    @Test
    fun expandAliases_classical_includesSpanish() {
        val aliases = GenreTaxonomy.expandAliases("Classical")
        assertTrue(aliases.contains("clasica"))
        assertTrue(aliases.contains("clasico"))
        assertTrue(aliases.contains("musica clasica"))
    }

    @Test
    fun expandAliases_soundtrack_includesSpanishAndOst() {
        val aliases = GenreTaxonomy.expandAliases("Soundtrack")
        assertTrue(aliases.contains("banda sonora"))
        assertTrue(aliases.contains("ost"))
        assertTrue(aliases.contains("score"))
    }

    @Test
    fun expandAliases_electronic_includesSpanish() {
        val aliases = GenreTaxonomy.expandAliases("Electronic")
        assertTrue(aliases.contains("electronica"))
        assertTrue(aliases.contains("electro"))
    }

    @Test
    fun matchesGenre_bilingualLookup() {
        assertTrue(GenreTaxonomy.matchesGenre("Classical", "clasica"))
        assertTrue(GenreTaxonomy.matchesGenre("Soundtrack", "banda sonora"))
        assertTrue(GenreTaxonomy.matchesGenre("Electronic", "electronica"))
        assertFalse(GenreTaxonomy.matchesGenre("Classical", "metal"))
    }
}
