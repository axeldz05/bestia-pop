package com.bestiapop.android.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalTextOrderTest {

    @Test
    fun extractRomanizedOrParsed_extractsLatinCandidate() {
        // Bilingual parenthesized
        assertEquals("Yodaka", NaturalTextOrder.extractRomanizedOrParsed("夜鷹 (Yodaka)"))
        assertEquals("Shingeki no Kyojin", NaturalTextOrder.extractRomanizedOrParsed("進撃の巨人 [Shingeki no Kyojin]"))
        assertEquals("Cruel Angel", NaturalTextOrder.extractRomanizedOrParsed("殘酷天使 / Cruel Angel"))

        // Already Latin
        assertEquals("Yesterday", NaturalTextOrder.extractRomanizedOrParsed("Yesterday"))
        assertEquals("Yesterday", NaturalTextOrder.extractRomanizedOrParsed("\"Yesterday\""))
        assertEquals("Hola!", NaturalTextOrder.extractRomanizedOrParsed("¡Hola!"))

        // Numbers & symbols
        assertEquals("1999", NaturalTextOrder.extractRomanizedOrParsed("1999"))
        assertEquals("...", NaturalTextOrder.extractRomanizedOrParsed("..."))
        assertEquals("★ Star", NaturalTextOrder.extractRomanizedOrParsed("★ Star"))
    }

    @Test
    fun sectionDescriptor_categorizesCorrectly() {
        // Letters
        val descA = NaturalTextOrder.sectionDescriptor("Árbol")
        assertEquals(NaturalTextOrder.SectionType.LETTER, descA.type)
        assertEquals("A", descA.label)

        val descY = NaturalTextOrder.sectionDescriptor("夜鷹 (Yodaka)")
        assertEquals(NaturalTextOrder.SectionType.LETTER, descY.type)
        assertEquals("Y", descY.label)

        // Numbers
        val descNum = NaturalTextOrder.sectionDescriptor("24K Magic")
        assertEquals(NaturalTextOrder.SectionType.NUMBER, descNum.type)
        assertEquals("0-9", descNum.label)

        // Symbols
        val descSym = NaturalTextOrder.sectionDescriptor("★ Special")
        assertEquals(NaturalTextOrder.SectionType.SYMBOL, descSym.type)
        assertEquals("*", descSym.label)
        assertEquals("Símbolos", descSym.popupLabel)
    }

    @Test
    fun comparator_sortsLettersThenNumbersThenSymbols() {
        val titles = listOf(
            "★★★ Star",
            "1999",
            "Zebra",
            "夜鷹 (Yodaka)",
            "Ángel",
            "50 Cent",
            "Beta",
            "...Dots",
            "Canción"
        )

        val ascending = titles.sortedWith(NaturalTextOrder.comparator(ascending = true) { it })
        assertEquals(
            listOf(
                "Ángel",
                "Beta",
                "Canción",
                "夜鷹 (Yodaka)",
                "Zebra",
                "1999",
                "50 Cent",
                "...Dots",
                "★★★ Star"
            ),
            ascending
        )

        val descending = titles.sortedWith(NaturalTextOrder.comparator(ascending = false) { it })
        assertEquals(ascending.reversed(), descending)
    }
}
