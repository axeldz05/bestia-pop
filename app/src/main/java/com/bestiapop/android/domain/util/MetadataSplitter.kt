package com.bestiapop.android.domain.util

/**
 * Splits composite artist and genre strings into distinct individual entities
 * using dynamic detection against known library artists and explicit collaboration syntax,
 * without hardcoded artist lists.
 */
object MetadataSplitter {

    private val COLLAB_REGEX = Regex(
        """(?i)\s+(?:feat\.?|ft\.?|featuring|with|con|pres\.?|presents|vs\.?)\s+|(?<=\S)\s+x\s+(?=\S)"""
    )
    private val TAG_DELIM_REGEX = Regex("""\s*;\s*|\s*\\\\\s*|\u0000+""")
    private val SPACED_SLASH_REGEX = Regex("""\s+/\s+""")
    private val GUEST_DELIM_REGEX = Regex("""\s*,\s*|\s+&\s+""")
    private val CANDIDATE_SPLIT_REGEX = Regex("""\s*,\s*|\s+&\s+""")
    private val GENRE_DELIM_REGEX = Regex("""\s*[,;/|]\s*|\s*\\\\\s*|\u0000+""")

    /**
     * Splits artist strings into individual artists.
     *
     * 1. Universal tag multi-value delimiters (`;`, `\\`) and explicit collaboration markers
     *    (`feat.`, `ft.`, `with`, `vs.`) are always recognized.
     * 2. Delimiters like `&` or `,` are only split dynamically when all resulting parts match
     *    known artists in [knownArtists] (e.g. "Queen & David Bowie" when both exist in the library),
     *    preserving bands like "Simon & Garfunkel", "Earth, Wind & Fire", "Tyler, The Creator", or
     *    "MASS OF THE FERMENTING DREGS" without hardcoded whitelists.
     */
    fun splitArtists(
        rawArtist: String?,
        knownArtists: Collection<String> = emptySet()
    ): List<String> {
        val raw = rawArtist?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("Unknown Artist", ignoreCase = true)) {
            return emptyList()
        }

        // 1. Tag multi-value delimiters (e.g. "Coldplay; BTS")
        if (raw.contains(';') || raw.contains("\\\\") || raw.contains('\u0000')) {
            val parts = raw.split(TAG_DELIM_REGEX)
            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (part in parts) {
                for (artist in splitArtists(part, knownArtists)) {
                    val norm = normalizeKey(artist)
                    if (norm.isNotEmpty() && seen.add(norm)) {
                        result.add(artist)
                    }
                }
            }
            return if (result.isEmpty()) listOf(raw) else result
        }

        // 2. Explicit collaboration keywords (e.g. "Main feat. Guest")
        if (COLLAB_REGEX.containsMatchIn(raw)) {
            val collabParts = raw.split(COLLAB_REGEX)
            if (collabParts.size >= 2) {
                val mainPart = collabParts[0].trim()
                val result = mutableListOf<String>()
                val seen = mutableSetOf<String>()

                if (mainPart.isNotEmpty()) {
                    val mainArtists = splitArtists(mainPart, knownArtists)
                    for (a in mainArtists) {
                        val norm = normalizeKey(a)
                        if (norm.isNotEmpty() && seen.add(norm)) {
                            result.add(a)
                        }
                    }
                }

                for (i in 1 until collabParts.size) {
                    val guestBlock = collabParts[i].trim()
                    val guestNames = guestBlock.split(GUEST_DELIM_REGEX)
                    for (g in guestNames) {
                        val cleaned = g.trim().trim('(', ')', '[', ']', '"', '\'')
                        if (cleaned.isNotEmpty()) {
                            val norm = normalizeKey(cleaned)
                            if (norm.isNotEmpty() && seen.add(norm)) {
                                result.add(cleaned)
                            }
                        }
                    }
                }

                return if (result.isEmpty()) listOf(raw) else result
            }
        }

        // 3. Spaced slash " / " as tag separator (distinct from "AC/DC" which has no spaces)
        if (SPACED_SLASH_REGEX.containsMatchIn(raw)) {
            val parts = raw.split(SPACED_SLASH_REGEX)
            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (part in parts) {
                val cleaned = part.trim()
                if (cleaned.isNotEmpty()) {
                    val norm = normalizeKey(cleaned)
                    if (norm.isNotEmpty() && seen.add(norm)) {
                        result.add(cleaned)
                    }
                }
            }
            if (result.size > 1) {
                return result
            }
        }

        // 4. Dynamic detection against known library artists for "&" or ","
        if (knownArtists.isNotEmpty()) {
            val dynamicSplit = trySplitUsingKnownArtists(raw, knownArtists)
            if (dynamicSplit != null && dynamicSplit.size > 1) {
                return dynamicSplit
            }
        }

        return listOf(raw)
    }

    private fun trySplitUsingKnownArtists(
        raw: String,
        knownArtists: Collection<String>
    ): List<String>? {
        val normKnown = knownArtists.asSequence()
            .map { normalizeKey(it) }
            .filter { it.length >= 2 }
            .toSet()
        if (normKnown.isEmpty()) return null

        val parts = raw.split(CANDIDATE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        // Split only if all sub-parts are known artists in the library
        if (parts.all { normKnown.contains(normalizeKey(it)) }) {
            return parts
        }

        return null
    }

    fun splitGenres(rawGenre: String?): List<String> {
        val raw = rawGenre?.trim().orEmpty()
        if (raw.isEmpty() ||
            raw.equals("Unknown Genre", ignoreCase = true) ||
            raw.equals("Music", ignoreCase = true)
        ) {
            return emptyList()
        }

        val parts = raw.split(GENRE_DELIM_REGEX)
        val result = mutableListOf<String>()
        val seenNormalized = mutableSetOf<String>()

        for (part in parts) {
            val cleaned = part.trim()
            if (cleaned.isNotEmpty() &&
                !cleaned.equals("Music", ignoreCase = true) &&
                !cleaned.equals("Unknown Genre", ignoreCase = true)
            ) {
                val norm = normalizeKey(cleaned)
                if (norm.isNotEmpty() && seenNormalized.add(norm)) {
                    result.add(cleaned)
                }
            }
        }

        return if (result.isEmpty()) listOf(raw) else result
    }

    fun normalizeKey(name: String): String = TrackMatchKeys.normalize(name)
}
