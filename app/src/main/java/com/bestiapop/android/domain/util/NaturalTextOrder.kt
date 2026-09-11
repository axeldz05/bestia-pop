package com.bestiapop.android.domain.util

import java.text.Normalizer
import java.util.Locale

/**
 * Natural text sorting and section categorization for music collections.
 *
 * Rules:
 * 1. Letters A through Z come first (Category 0).
 * 2. Numbers (0-9) come second (Category 1).
 * 3. Special symbols and others come last (Category 2).
 *
 * Foreign/bilingual titles:
 * - Bilingual titles with romanized portions in brackets/parentheses (e.g. `夜鷹 (Yodaka)`)
 *   extract the romanized Latin portion for sorting and letter sectioning.
 * - Non-Latin scripts (CJK, Cyrillic, Greek, Arabic, etc.) are transliterated to Latin/ASCII
 *   where possible (via ICU Transliterator) so they naturally fall into A-Z.
 * - Accents and diacritics are normalized (Á -> A, etc.) so accented titles sort under their base letter.
 */
object NaturalTextOrder {

    enum class SectionType {
        LETTER,
        NUMBER,
        SYMBOL
    }

    data class SectionDescriptor(
        val type: SectionType,
        val label: String,
        val popupLabel: String
    )

    private val PARENTHESIS_REGEX = Regex("""[\(\[\{]([^\)\]\}]+)[\)\]\}]""")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val LEADING_QUOTE_AND_BRACKET_CHARS = charArrayOf(
        '"', '\'', '(', '[', '{', '<', '¿', '¡', '“', '”', '‘', '’', '`', '´', ' '
    )

    private val icuTransliteratorPair by lazy {
        try {
            val clazz = Class.forName("android.icu.text.Transliterator")
            val getInstance = clazz.getMethod("getInstance", String::class.java)
            val instance = getInstance.invoke(null, "Any-Latin; Latin-ASCII")
            val transliterate = clazz.getMethod("transliterate", String::class.java)
            Pair(instance, transliterate)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Transliterates non-Latin characters to Latin ASCII if an ICU transliterator is available.
     */
    fun transliterateToLatin(text: String): String {
        if (text.isEmpty() || text.all { it.code <= 0x024F }) return text
        val pair = icuTransliteratorPair ?: return text
        return try {
            pair.second.invoke(pair.first, text) as? String ?: text
        } catch (_: Throwable) {
            text
        }
    }

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    /**
     * Extracts the romanized or parsed Latin candidate from a title if one exists,
     * or transliterates non-Latin scripts to Latin.
     */
    fun extractRomanizedOrParsed(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val firstChar = trimmed[0]
        if ((firstChar.isLatinLetter() || firstChar in '0'..'9') &&
            !trimmed.contains('(') && !trimmed.contains('[') && !trimmed.contains('{')
        ) {
            return trimmed
        }

        // 1. Check if the string after stripping leading/trailing quotes/brackets already has a Latin letter or digit
        val stripped = trimmed.trim(*LEADING_QUOTE_AND_BRACKET_CHARS)
        if (stripped.isNotEmpty() && (stripped[0].isLatinLetter() || stripped[0] in '0'..'9')) {
            return stripped
        }

        // 2. Look for romanized text inside parentheses or brackets: e.g. `夜鷹 (Yodaka)` -> `Yodaka`
        for (match in PARENTHESIS_REGEX.findAll(trimmed)) {
            val inside = match.groupValues[1].trim()
            if (inside.any { it.isLatinLetter() }) {
                val cleanInside = inside.trimStart(*LEADING_QUOTE_AND_BRACKET_CHARS)
                if (cleanInside.isNotEmpty() && cleanInside[0].isLatinLetter()) {
                    return cleanInside
                }
            }
        }

        // 3. Look for romanized text after a delimiter like " / " or " - "
        val splitParts = trimmed.split('/', '-', '—')
        if (splitParts.size > 1) {
            for (part in splitParts) {
                val candidate = part.trim().trimStart(*LEADING_QUOTE_AND_BRACKET_CHARS)
                if (candidate.isNotEmpty() && candidate[0].isLatinLetter()) {
                    return candidate
                }
            }
        }

        // 4. Transliterate non-Latin scripts (Cyrillic, CJK, Greek, Arabic, etc.) to Latin
        val transliterated = transliterateToLatin(stripped)
        val cleanTransliterated = transliterated.trimStart(*LEADING_QUOTE_AND_BRACKET_CHARS)
        if (cleanTransliterated.isNotEmpty() && cleanTransliterated[0].isLatinLetter()) {
            return cleanTransliterated
        }

        return stripped
    }

    /**
     * Normalizes a string by stripping diacritics and converting to uppercase.
     */
    fun normalizeToLatinBase(text: String): String {
        if (text.isEmpty()) return ""
        var isAscii = true
        for (i in 0 until text.length) {
            if (text[i].code > 0x7F) {
                isAscii = false
                break
            }
        }
        if (isAscii) {
            return text.uppercase(Locale.ROOT)
        }
        val unaccented = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
        return unaccented.uppercase(Locale.ROOT)
    }

    /**
     * Determines the fast-scroll section descriptor for a given string:
     * - LETTER: "A".."Z"
     * - NUMBER: "0-9"
     * - SYMBOL: "*"
     */
    fun sectionDescriptor(raw: String?): SectionDescriptor {
        val parsed = extractRomanizedOrParsed(raw)
        if (parsed.isEmpty()) {
            return SectionDescriptor(SectionType.SYMBOL, "*", "Símbolos")
        }

        val base = normalizeToLatinBase(parsed)
        val first = base.firstOrNull() ?: return SectionDescriptor(SectionType.SYMBOL, "*", "Símbolos")

        return when {
            first in 'A'..'Z' -> {
                val letterStr = first.toString()
                SectionDescriptor(SectionType.LETTER, letterStr, letterStr)
            }

            first in '0'..'9' -> {
                SectionDescriptor(SectionType.NUMBER, "0-9", "0-9")
            }

            else -> {
                SectionDescriptor(SectionType.SYMBOL, "*", "Símbolos")
            }
        }
    }

    data class NaturalSortKey(
        val type: SectionType,
        val normalized: String,
        val original: String
    ) : Comparable<NaturalSortKey> {
        override fun compareTo(other: NaturalSortKey): Int {
            val typeCmp = type.compareTo(other.type)
            if (typeCmp != 0) return typeCmp
            val normCmp = normalized.compareTo(other.normalized, ignoreCase = true)
            if (normCmp != 0) return normCmp
            return original.compareTo(other.original, ignoreCase = true)
        }
    }

    fun toSortKey(raw: String?): NaturalSortKey {
        if (raw.isNullOrBlank()) {
            return NaturalSortKey(SectionType.SYMBOL, "", "")
        }
        val parsed = extractRomanizedOrParsed(raw)
        val normalized = normalizeToLatinBase(parsed)
        val type = when (normalized.firstOrNull()) {
            in 'A'..'Z' -> SectionType.LETTER
            in '0'..'9' -> SectionType.NUMBER
            else -> SectionType.SYMBOL
        }
        return NaturalSortKey(type, normalized, raw)
    }

    /**
     * Creates a comparator for objects of type [T] based on a string selector,
     * ordering: A-Z -> Numbers -> Symbols (or reversed if [ascending] is false).
     */
    fun <T> comparator(
        ascending: Boolean = true,
        selector: (T) -> String?
    ): Comparator<T> {
        val baseCmp = Comparator<T> { a, b ->
            val keyA = toSortKey(selector(a))
            val keyB = toSortKey(selector(b))
            keyA.compareTo(keyB)
        }
        return if (ascending) baseCmp else baseCmp.reversed()
    }
}

/**
 * Level 1/Level 2: Top-level extension to sort any list using [NaturalTextOrder] without receiver ceremony.
 */
fun <T> List<T>.sortedWithNaturalOrder(
    ascending: Boolean = true,
    selector: (T) -> String?
): List<T> {
    if (size <= 1) return this
    val n = size
    val keys = Array(n) { i -> NaturalTextOrder.toSortKey(selector(this[i])) }
    val indices = Array(n) { it }
    val cmp = if (ascending) {
        Comparator<Int> { i, j -> keys[i].compareTo(keys[j]) }
    } else {
        Comparator<Int> { i, j -> keys[j].compareTo(keys[i]) }
    }
    indices.sortWith(cmp)
    return List(n) { i -> this[indices[i]] }
}
