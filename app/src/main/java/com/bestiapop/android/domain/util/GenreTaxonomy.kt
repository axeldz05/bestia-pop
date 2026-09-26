package com.bestiapop.android.domain.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Curated bilingual (Spanish/English) taxonomy for music genre search and categorization.
 * Combines hardcoded baseline mappings, pre-populated asset dataset, and dynamic runtime translations.
 */
object GenreTaxonomy {
    private val SPANISH_TO_ENGLISH: Map<String, List<String>> =
        mapOf(
            "clasica" to listOf("classical"),
            "clasico" to listOf("classical"),
            "musica clasica" to listOf("classical"),
            "banda sonora" to listOf("soundtrack", "ost", "score"),
            "bandas sonoras" to listOf("soundtrack", "ost", "score"),
            "bso" to listOf("soundtrack", "ost", "score"),
            "bsi" to listOf("soundtrack", "ost", "score"),
            "electronica" to listOf("electronic", "electro", "dance", "edm"),
            "electronico" to listOf("electronic", "electro", "dance", "edm"),
            "electro" to listOf("electronic"),
            "musica urbana" to listOf("urban", "reggaeton", "trap", "latin"),
            "urbano" to listOf("urban", "reggaeton", "trap", "latin"),
            "acustico" to listOf("acoustic"),
            "folklore" to listOf("folk"),
            "latino" to listOf("latin"),
            "musica latina" to listOf("latin"),
        )

    private val ENGLISH_TO_SPANISH: Map<String, List<String>> =
        mapOf(
            "classical" to listOf("clasica", "clasico", "musica clasica"),
            "soundtrack" to listOf("banda sonora", "bandas sonoras", "ost", "bso", "score"),
            "electronic" to listOf("electronica", "electro"),
            "dance" to listOf("electronica"),
            "edm" to listOf("electronica"),
            "acoustic" to listOf("acustico"),
            "folk" to listOf("folklore"),
            "latin" to listOf("latino", "musica latina"),
            "urban" to listOf("urbano", "musica urbana"),
        )

    private val dynamicTranslations = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Registers a bidirectional dynamic translation in memory.
     */
    fun registerDynamicTranslation(
        source: String,
        translation: String,
    ) {
        val sNorm = TrackMatchKeys.normalize(source)
        val tNorm = TrackMatchKeys.normalize(translation)
        if (sNorm.isEmpty() || tNorm.isEmpty()) return

        dynamicTranslations.computeIfAbsent(sNorm) { ConcurrentHashMap.newKeySet() }.add(tNorm)
        dynamicTranslations.computeIfAbsent(tNorm) { ConcurrentHashMap.newKeySet() }.add(sNorm)
    }

    /**
     * Checks if [genre] is already known in baseline or dynamic translations.
     */
    fun hasMapping(genre: String): Boolean {
        val norm = TrackMatchKeys.normalize(genre)
        if (norm.isEmpty()) return true
        if (ENGLISH_TO_SPANISH.containsKey(norm) || SPANISH_TO_ENGLISH.containsKey(norm)) return true
        return dynamicTranslations.containsKey(norm)
    }

    /**
     * Expands a song's genre into related bilingual tags and synonyms.
     * E.g. "Classical" -> {"clasica", "clasico", "musica clasica"}
     */
    fun expandAliases(genre: String): Set<String> {
        val trimmed = genre.trim()
        if (trimmed.isEmpty() || trimmed.equals("Unknown Genre", ignoreCase = true)) {
            return emptySet()
        }
        val normalized = TrackMatchKeys.normalize(trimmed)
        val out = LinkedHashSet<String>()

        // 1. Direct English to Spanish mapping
        ENGLISH_TO_SPANISH[normalized]?.let { out.addAll(it) }

        // 2. Direct Spanish to English mapping
        SPANISH_TO_ENGLISH[normalized]?.let { out.addAll(it) }

        // 3. Dynamic in-memory translations
        dynamicTranslations[normalized]?.let { out.addAll(it) }

        // 4. Sub-token check (e.g. "Electronic Dance" matches "electronic")
        val tokens = normalized.split(' ').filter { it.length >= 3 }
        for (token in tokens) {
            ENGLISH_TO_SPANISH[token]?.let { out.addAll(it) }
            SPANISH_TO_ENGLISH[token]?.let { out.addAll(it) }
            dynamicTranslations[token]?.let { out.addAll(it) }
        }

        return out
    }

    /**
     * Checks if a user's search token corresponds to [songGenre] in English or Spanish.
     */
    fun matchesGenre(
        songGenre: String,
        searchQuery: String,
    ): Boolean {
        val qNorm = TrackMatchKeys.normalize(searchQuery)
        val gNorm = TrackMatchKeys.normalize(songGenre)
        if (qNorm.isEmpty() || gNorm.isEmpty()) return false
        if (qNorm == gNorm || gNorm.contains(qNorm) || qNorm.contains(gNorm)) return true

        val spanishEn = SPANISH_TO_ENGLISH[qNorm]
        if (spanishEn != null && spanishEn.any { gNorm.contains(it) }) return true

        val englishEs = ENGLISH_TO_SPANISH[qNorm]
        if (englishEs != null && englishEs.any { gNorm.contains(it) }) return true

        val dyn = dynamicTranslations[qNorm]
        if (dyn != null && dyn.any { gNorm.contains(it) || it.contains(gNorm) }) return true

        return false
    }

    /** Clears dynamic translations (useful for tests). */
    internal fun clearDynamicTranslations() {
        dynamicTranslations.clear()
    }
}
