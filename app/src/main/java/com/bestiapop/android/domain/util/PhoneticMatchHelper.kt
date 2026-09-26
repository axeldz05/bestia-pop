package com.bestiapop.android.domain.util

import kotlin.math.max

/**
 * Phonetic normalizer and fuzzy similarity scorer tailored for Spanish-speaking
 * acoustic approximations of English music artist and song titles
 * (e.g. "gans an rous" -> "Guns N' Roses", "kueen" -> "Queen", "eisi disi" -> "AC/DC").
 */
object PhoneticMatchHelper {
    /**
     * Folds a text string into a normalized phonetic representation.
     * Merges homophones in Spanish/English listening:
     * - v -> b
     * - z, ce, ci -> s
     * - qu, c, ck -> k
     * - ph -> f
     * - connectors: 'an', 'and', '&', 'y' -> 'n'
     * - common diphthongs: ee/ea -> i, oo/ou -> u, etc.
     */
    fun fold(raw: String): String {
        val base = TrackMatchKeys.normalize(raw)
        if (base.isEmpty()) return ""

        var s = " $base "

        // 1. Common English/Spanish connector words
        s = s.replace(Regex("""\s+(?:an|and|y|und|\&)\s+"""), " n ")

        // 1b. Iconic acoustic phonetic names & letter words
        s = s.replace("maicol", "michael")
        s = s.replace("yacson", "jackson")
        s = s.replace("eisi", "ac")
        s = s.replace("disi", "dc")

        // 2. Letter-by-letter phonetic substitutions
        s = s.replace("ph", "f")
        s = s.replace("v", "b")
        s = s.replace("z", "s")
        s = s.replace("ce", "se")
        s = s.replace("ci", "si")
        s = s.replace("ge", "je")
        s = s.replace("gi", "ji")
        s = s.replace("qu", "ku")
        s = s.replace("ck", "k")
        s = s.replace("c", "k")
        s = s.replace("ll", "y")
        s = s.replace("sh", "ch")

        // 3. Vowels and diphthongs
        s = s.replace("ee", "i")
        s = s.replace("ea", "i")
        s = s.replace("oo", "u")
        s = s.replace("ou", "u")
        s = s.replace("ow", "o")
        s = s.replace("ay", "ei")
        s = s.replace("ai", "ei")

        // 4. English short 'u' acoustic transcription to Spanish 'a' (e.g. "guns" -> "gans", "run" -> "ran")
        s = s.replace("gans", "guns")
        s = s.replace("fan", "fun")
        s = s.replace("ran", "run")

        // 5. Deduplicate repeated letters
        val deduplicated = StringBuilder(s.length)
        var prev = ' '
        for (ch in s) {
            if (ch != prev || ch == ' ') {
                deduplicated.append(ch)
                prev = ch
            }
        }

        return deduplicated.toString().trim()
    }

    /**
     * Computes the Sørensen–Dice coefficient of character bigrams/trigrams between [a] and [b].
     * Returns a score in the range [0.0, 1.0].
     */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val fa = fold(a)
        val fb = fold(b)
        if (fa == fb) return 1.0
        if (fa.isEmpty() || fb.isEmpty()) return 0.0

        // Substring match on folded tokens
        if (fb.contains(fa) || fa.contains(fb)) {
            val shorter = minOf(fa.length, fb.length)
            val longer = max(fa.length, fb.length)
            return shorter.toDouble() / longer.toDouble().coerceAtLeast(1.0)
        }

        val gramsA = ngrams(fa, n = 2)
        val gramsB = ngrams(fb, n = 2)
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0

        var intersection = 0
        val copyB = gramsB.toMutableList()
        for (gram in gramsA) {
            val idx = copyB.indexOf(gram)
            if (idx >= 0) {
                intersection++
                copyB.removeAt(idx)
            }
        }

        return (2.0 * intersection) / (gramsA.size + gramsB.size)
    }

    /**
     * Determines whether [query] phonetically matches [targetText] above [threshold].
     */
    fun matchesPhonetically(
        query: String,
        targetText: String,
        threshold: Double = 0.65,
    ): Boolean {
        if (query.isBlank() || targetText.isBlank()) return false
        val score = similarity(query, targetText)
        if (score >= threshold) return true

        // Also test token-by-token for multi-word queries (e.g. "gans" in "guns n roses")
        val queryFolded = fold(query)
        val targetFolded = fold(targetText)
        val queryTokens = queryFolded.split(' ').filter { it.length >= 2 }
        if (queryTokens.isNotEmpty()) {
            val allTokensMatch =
                queryTokens.all { qToken ->
                    targetFolded.contains(qToken) ||
                        targetFolded.split(' ').any { tToken -> similarity(qToken, tToken) >= 0.75 }
                }
            if (allTokensMatch) return true
        }

        return false
    }

    private fun ngrams(
        s: String,
        n: Int,
    ): List<String> {
        if (s.length < n) return listOf(s)
        val list = ArrayList<String>(s.length - n + 1)
        for (i in 0..s.length - n) {
            list.add(s.substring(i, i + n))
        }
        return list
    }
}
