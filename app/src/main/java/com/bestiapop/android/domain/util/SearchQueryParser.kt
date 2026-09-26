package com.bestiapop.android.domain.util

/**
 * Parsed representation of a user search query with extracted temporal,
 * genre, and text components.
 */
data class ParsedSearchQuery(
    val rawQuery: String,
    val textQuery: String,
    val textTokens: List<String>,
    val yearRanges: List<IntRange> = emptyList(),
    val isLyricSearchCandidate: Boolean = false,
) {
    /** Whether this query carries year or decade constraints. */
    val hasYearConstraint: Boolean get() = yearRanges.isNotEmpty()

    /**
     * Checks if a [songYear] satisfies any of the parsed year/decade ranges.
     * Songs with year <= 0 never match a year constraint.
     */
    fun matchesYear(songYear: Int): Boolean {
        if (songYear <= 0 || yearRanges.isEmpty()) return false
        return yearRanges.any { songYear in it }
    }
}

/**
 * Intelligent query parser for music search. Extracts:
 * 1. Decades in English & Spanish ("70s", "los 80", "70 y 80", "ochentas", "2000")
 * 2. Exact 4-digit years ("1984", "2023")
 * 3. Year spans ("2000-2010", "1970 a 1985")
 * 4. Lyric candidate heuristic for multi-word queries.
 */
object SearchQueryParser {
    private val YEAR_RANGE_REGEX =
        Regex("""\b(19\d{2}|20\d{2})\s*(?:-|a|al|to)\s*(19\d{2}|20\d{2})\b""", RegexOption.IGNORE_CASE)

    // Spanish named decades
    private val SPANISH_NAMED_DECADES =
        mapOf(
            "cincuentas" to (1950..1959),
            "sesentas" to (1960..1969),
            "setentas" to (1970..1979),
            "ochentas" to (1980..1989),
            "noventas" to (1990..1999),
            "dosmiles" to (2000..2009),
        )

    // Patterns for multi-decade connectors: "70 y 80", "80s & 90s", "70 and 80"
    private val MULTI_DECADE_REGEX =
        Regex(
            """\b(?:(?:de\s+los|los|anos|años)\s+)?(\d{2}s?|\b(?:cincuentas|sesentas|setentas|ochentas|noventas|dosmiles))\s*(?:y|and|&|-)\s*(?:(?:de\s+los|los|anos|años)\s+)?(\d{2}s?|\b(?:cincuentas|sesentas|setentas|ochentas|noventas|dosmiles))\b""",
            RegexOption.IGNORE_CASE,
        )

    // Single decade pattern: "los 80", "80s", "70's", "años 70", "del 80", "2000s", "los 2000"
    private val SINGLE_DECADE_REGEX =
        Regex(
            """\b(?:(?:de\s+los|los|del|anos|años)\s+)?(50|60|70|80|90|00|10|20)(?:'s|s)?\b""",
            RegexOption.IGNORE_CASE,
        )

    // 2000 decade expressions: "2000", "2000s", "los 2000", "anos 2000", "años 2000", "dos mil"
    private val YEAR_2000_DECADE_REGEX =
        Regex(
            """\b(?:(?:de\s+los|los|del|anos|años)\s+)?(?:2000s?|dos\s*mil)\b""",
            RegexOption.IGNORE_CASE,
        )

    // Exact 4-digit years: 1900..2099
    private val EXACT_YEAR_REGEX =
        Regex("""\b(19\d{2}|20\d{2})\b""")

    fun parse(rawQuery: String): ParsedSearchQuery {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            return ParsedSearchQuery(rawQuery = "", textQuery = "", textTokens = emptyList())
        }

        val ranges = mutableListOf<IntRange>()
        var working = TrackMatchKeys.normalize(trimmed)

        // 1. Check explicit year ranges: "2000-2010", "1970 a 1985"
        YEAR_RANGE_REGEX.findAll(working).forEach { match ->
            val y1 = match.groupValues[1].toIntOrNull()
            val y2 = match.groupValues[2].toIntOrNull()
            if (y1 != null && y2 != null) {
                val start = minOf(y1, y2)
                val end = maxOf(y1, y2)
                ranges.add(start..end)
                working = working.replaceRange(match.range, " ")
            }
        }

        // 2. Check multi-decade combinations: "70 y 80", "80s & 90s"
        MULTI_DECADE_REGEX.findAll(working).forEach { match ->
            val d1 = parseDecadeChunk(match.groupValues[1])
            val d2 = parseDecadeChunk(match.groupValues[2])
            if (d1 != null) ranges.add(d1)
            if (d2 != null) ranges.add(d2)
            if (d1 != null || d2 != null) {
                working = working.replaceRange(match.range, " ")
            }
        }

        // 3. Check 2000 decade expressions: "2000", "2000s", "los 2000", "dos mil", "dosmiles"
        YEAR_2000_DECADE_REGEX.findAll(working).forEach { match ->
            // "2000" can span 2000..2009 (and accommodate 2000-2010)
            ranges.add(2000..2010)
            working = working.replaceRange(match.range, " ")
        }

        // 4. Check Spanish named decades: "ochentas", "setentas", "noventas", "sesentas", "cincuentas"
        for ((name, range) in SPANISH_NAMED_DECADES) {
            val nameRegex = Regex("""\b(?:(?:de\s+los|los|del)\s+)?$name\b""", RegexOption.IGNORE_CASE)
            nameRegex.findAll(working).forEach { match ->
                ranges.add(range)
                working = working.replaceRange(match.range, " ")
            }
        }

        // 5. Check single decades: "70s", "los 80", "90", "del 70"
        SINGLE_DECADE_REGEX.findAll(working).forEach { match ->
            val digits = match.groupValues[1]
            val decadeRange = parse2DigitDecade(digits)
            if (decadeRange != null) {
                // If isolated or preceded by decade markers, treat as decade
                ranges.add(decadeRange)
                working = working.replaceRange(match.range, " ")
            }
        }

        // 6. Check single 4-digit exact years (e.g. 1984, 1999) if not already consumed
        EXACT_YEAR_REGEX.findAll(working).forEach { match ->
            val yr = match.groupValues[1].toIntOrNull()
            if (yr != null) {
                ranges.add(yr..yr)
                working = working.replaceRange(match.range, " ")
            }
        }

        // Clean up remaining text query
        // Strip filler phrases left by decade patterns: "de los", "de las", "anos", "del"
        val cleanedText =
            working
                .replace(Regex("""\b(?:de\s+los|de\s+las|de\s+la|de|los|las|del|anos)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        val textTokens = cleanedText.split(' ').filter { it.isNotEmpty() }
        val isLyricCandidate = textTokens.size >= 3 || cleanedText.length >= 14

        return ParsedSearchQuery(
            rawQuery = trimmed,
            textQuery = cleanedText,
            textTokens = textTokens,
            yearRanges = ranges.distinct(),
            isLyricSearchCandidate = isLyricCandidate,
        )
    }

    private fun parseDecadeChunk(chunk: String): IntRange? {
        val clean = chunk.trim().lowercase()
        SPANISH_NAMED_DECADES[clean]?.let { return it }
        val digits = clean.filter { it.isDigit() }
        return when (digits.length) {
            2 -> parse2DigitDecade(digits)
            4 -> if (digits == "2000") 2000..2010 else digits.toIntOrNull()?.let { it..it }
            else -> null
        }
    }

    private fun parse2DigitDecade(digits: String): IntRange? =
        when (digits) {
            "50" -> 1950..1959
            "60" -> 1960..1969
            "70" -> 1970..1979
            "80" -> 1980..1989
            "90" -> 1990..1999
            "00" -> 2000..2009
            "10" -> 2010..2019
            "20" -> 2020..2029
            else -> null
        }
}
