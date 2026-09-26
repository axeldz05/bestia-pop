package com.bestiapop.android.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryParserTest {
    @Test
    fun parse_singleDecadeNumber() {
        val parsed70 = SearchQueryParser.parse("70")
        assertTrue(parsed70.hasYearConstraint)
        assertTrue(parsed70.matchesYear(1975))
        assertFalse(parsed70.matchesYear(1985))
        assertFalse(parsed70.matchesYear(0))
        assertEquals(emptyList<String>(), parsed70.textTokens)

        val parsed80s = SearchQueryParser.parse("80s")
        assertTrue(parsed80s.hasYearConstraint)
        assertTrue(parsed80s.matchesYear(1982))
        assertFalse(parsed80s.matchesYear(1972))
    }

    @Test
    fun parse_spanishDecadePrefixes() {
        val parsedLos80 = SearchQueryParser.parse("los 80")
        assertTrue(parsedLos80.hasYearConstraint)
        assertTrue(parsedLos80.matchesYear(1989))
        assertEquals(emptyList<String>(), parsedLos80.textTokens)

        val parsedOchentas = SearchQueryParser.parse("ochentas")
        assertTrue(ochentasYear(parsedOchentas, 1984))

        val parsedSetentas = SearchQueryParser.parse("de los setentas")
        assertTrue(parsedSetentas.matchesYear(1971))
    }

    private fun ochentasYear(
        parsed: ParsedSearchQuery,
        year: Int,
    ): Boolean = parsed.matchesYear(year)

    @Test
    fun parse_multiDecades_combinedWithY() {
        val parsed = SearchQueryParser.parse("70 y 80")
        assertTrue(parsed.hasYearConstraint)
        assertTrue(parsed.matchesYear(1973))
        assertTrue(parsed.matchesYear(1988))
        assertFalse(parsed.matchesYear(1995))
        assertEquals(emptyList<String>(), parsed.textTokens)

        val parsedAnd = SearchQueryParser.parse("80s and 90s")
        assertTrue(parsedAnd.matchesYear(1985))
        assertTrue(parsedAnd.matchesYear(1991))
        assertFalse(parsedAnd.matchesYear(1975))
    }

    @Test
    fun parse_decade2000_andSpans() {
        val parsed2000 = SearchQueryParser.parse("2000")
        assertTrue(parsed2000.hasYearConstraint)
        assertTrue(parsed2000.matchesYear(2003))
        assertTrue(parsed2000.matchesYear(2010))
        assertFalse(parsed2000.matchesYear(1999))
        assertFalse(parsed2000.matchesYear(2015))

        val parsedSpan = SearchQueryParser.parse("2000 a 2010")
        assertTrue(parsedSpan.matchesYear(2005))
        assertTrue(parsedSpan.matchesYear(2010))
        assertFalse(parsedSpan.matchesYear(2012))
    }

    @Test
    fun parse_exactFourDigitYear() {
        val parsed = SearchQueryParser.parse("1984")
        assertTrue(parsed.hasYearConstraint)
        assertTrue(parsed.matchesYear(1984))
        assertFalse(parsed.matchesYear(1985))
    }

    @Test
    fun parse_mixedQuery_artistAndDecade() {
        val parsed = SearchQueryParser.parse("queen 70s")
        assertTrue(parsed.hasYearConstraint)
        assertTrue(parsed.matchesYear(1975))
        assertEquals(listOf("queen"), parsed.textTokens)
        assertEquals("queen", parsed.textQuery)

        val parsedRock80 = SearchQueryParser.parse("rock de los 80")
        assertTrue(parsedRock80.hasYearConstraint)
        assertTrue(parsedRock80.matchesYear(1985))
        assertEquals(listOf("rock"), parsedRock80.textTokens)
    }

    @Test
    fun parse_lyricCandidateHeuristic() {
        val lyricQuery = SearchQueryParser.parse("mama just killed a man")
        assertTrue(lyricQuery.isLyricSearchCandidate)

        val shortQuery = SearchQueryParser.parse("queen")
        assertFalse(shortQuery.isLyricSearchCandidate)
    }
}
