package com.bestiapop.android.domain.util

import java.text.Normalizer

/**
 * Splits composite artist and genre strings into distinct individual entities
 * using dynamic detection against known library artists and explicit collaboration syntax,
 * without hardcoded artist lists.
 *
 * Also provides **identity key** and **display-name selector** functions that unify
 * variants differing only in diacritics, capitalization, connectors (`&`/`and`/`y`),
 * word order (genres), or script (CJK ↔ Latin romanizations).
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

    // --- Identity-key internals -----------------------------------------------

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val CONNECTORS = Regex("""(?i)\s+(?:and|y)\s+|\s*&\s*|\s*\+\s*""")
    private val WHITESPACE = Regex("\\s+")
    private val HYPHENS_DASHES = Regex("[\\-–—]")

    private fun foldDiacritics(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    /**
     * Algorithmic genre-token stemmer: strips common English/Spanish inflectional
     * suffixes so variant forms collapse to the same key without a lookup table.
     *
     * `-ks`/`-ts`/`-ns`/`-ps`/`-ds` (regular consonant-cluster plural):
     *   `soundtracks` → `soundtrack`, `elements` → `element`
     * `-ica` → `-ic` (Spanish/Latin feminine): `electronica` → `electronic`
     * `-iva` → `-ive` (Spanish/Latin feminine): `alternativa` → `alternative`
     *
     * Does **not** strip `-es` after vowels or sibilants (`blues`, `oldies`,
     * `bass`, `house`, `grunge`) to avoid false collapses.
     */
    private fun stemGenreToken(token: String): String = when {
        token.length > 4 && token.endsWith("ica") -> token.dropLast(1)
        token.length > 4 && token.endsWith("iva") -> token.dropLast(1) + "e"
        // Only strip trailing 's' after consonants that typically form regular plurals
        token.length > 3 && token.endsWith("s") && !token.endsWith("ss") &&
            token[token.length - 2].let { it in "ktnpd" } -> token.dropLast(1)
        else -> token
    }

    /**
     * Extracts the Latin-script content from a mixed-script string,
     * delegating to [IdentifyQueryVariants.latinLetters].
     *
     * Returns the original text if it's already purely Latin.
     * Used by [artistIdentityKey] to unify mixed-script names with their
     * Latin-only counterparts without hardcoded alias tables:
     * `"elephant gym 大象體操"` → `"elephant gym"`.
     */
    private fun extractLatinContent(text: String): String {
        if (!IdentifyQueryVariants.hasOtherLetterScript(text)) return text
        return IdentifyQueryVariants.latinLetters(text)
    }

    // --- Public identity-key API ----------------------------------------------

    /**
     * Canonical identity key for an **artist** name.
     *
     * Folds diacritics, lowercases, normalizes connectors (`&`/`and`/`y` → space),
     * collapses whitespace.
     *
     * For **mixed-script** names (Latin + CJK/Cyrillic/etc.), the Latin portion
     * becomes the canonical key so that `"Elephant Gym 大象體操"` and
     * `"Elephant Gym"` share the same key — without hardcoded alias tables.
     *
     * Pure non-Latin names (e.g. `きのこ帝国`) keep their original-script key;
     * the search haystack already includes transliterated Latin for findability.
     */
    fun artistIdentityKey(name: String): String {
        val folded = foldDiacritics(name).lowercase()
            .replace(CONNECTORS, " ")
            .replace(HYPHENS_DASHES, " ")
            .replace(WHITESPACE, " ")
            .trim()
        // For mixed-script names, use the Latin portion as canonical key
        val latinContent = extractLatinContent(folded)
        if (latinContent.length >= 3 && latinContent != folded) {
            return latinContent
        }
        return folded
    }

    /**
     * Canonical identity key for a **genre** label.
     *
     * Folds diacritics, lowercases, normalizes connectors (`&`/`and`/`y`/`+`/`-`/`/` → space),
     * applies algorithmic suffix stemming (`soundtracks` → `soundtrack`,
     * `electronica` → `electronic`), and **sorts tokens alphabetically** so
     * `Pop Rock`, `Rock & Pop`, `rock pop`, and `pop-rock` all collapse to the same key.
     */
    fun genreIdentityKey(name: String): String {
        val folded = foldDiacritics(name).lowercase()
            .replace(CONNECTORS, " ")
            .replace(HYPHENS_DASHES, " ")
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace(WHITESPACE, " ")
            .trim()
        val tokens = folded.split(' ').filter { it.isNotEmpty() }
        val stemmed = tokens.map { stemGenreToken(it) }
        return stemmed.sorted().joinToString(" ")
    }

    // --- Display-name selectors -----------------------------------------------

    /**
     * Among a collection of variant surface forms for the **same artist** (they share
     * [artistIdentityKey]), picks the best display name applying these priorities:
     *
     * 1. **Original-script priority**: names containing non-Latin characters (CJK, Cyrillic,
     *    Icelandic diacritics not stripped by NFD, etc.) win over pure-ASCII romanizations.
     * 2. **Diacritics bonus**: `Diego Sáenz` beats `Diego Saenz`.
     * 3. **Mixed/Title Case preference**: `Asian Kung-Fu Generation` beats `ASIAN KUNG-FU GENERATION`
     *    and beats all-lowercase `asian kung-fu generation`.
     * 4. **Tie-break**: longer name (more detail), then lexicographic.
     */
    fun preferredArtistDisplayName(variants: Collection<String>): String {
        if (variants.size <= 1) return variants.firstOrNull().orEmpty()
        return variants.maxWithOrNull(Comparator { a, b -> compareArtistVariants(a, b) }).orEmpty()
    }

    private fun hasNonLatinScript(text: String): Boolean =
        text.any { ch ->
            ch.isLetter() && Character.UnicodeScript.of(ch.code).let { script ->
                script != Character.UnicodeScript.LATIN &&
                    script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED
            }
        }

    private fun hasExtraDiacritics(text: String): Boolean {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return nfd.any { ch ->
            Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
        }
    }

    private fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length > 4 && letters.all { it.isUpperCase() }
    }

    private fun hasMixedCase(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.any { it.isUpperCase() } && letters.any { it.isLowerCase() }
    }

    private fun compareArtistVariants(a: String, b: String): Int {
        // 1. Non-Latin beats Latin-only
        val aNonLatin = hasNonLatinScript(a)
        val bNonLatin = hasNonLatinScript(b)
        if (aNonLatin != bNonLatin) return if (aNonLatin) 1 else -1

        // 2. Diacritics (accented > non-accented)
        val aDiacritics = hasExtraDiacritics(a)
        val bDiacritics = hasExtraDiacritics(b)
        if (aDiacritics != bDiacritics) return if (aDiacritics) 1 else -1

        // 3. Mixed case (Title Case) beats ALL-CAPS and all-lowercase
        val aMixed = hasMixedCase(a)
        val bMixed = hasMixedCase(b)
        if (aMixed != bMixed) return if (aMixed) 1 else -1

        // 4. Penalize ALL-CAPS when neither is mixed
        val aAllCaps = isAllCaps(a)
        val bAllCaps = isAllCaps(b)
        if (aAllCaps != bAllCaps) return if (aAllCaps) -1 else 1

        // 5. Longer name (more detail)
        val lenDiff = a.length.compareTo(b.length)
        if (lenDiff != 0) return lenDiff

        // 6. Lexicographic (case-insensitive first, then prefer uppercase over lowercase)
        val caseInsensitive = a.compareTo(b, ignoreCase = true)
        if (caseInsensitive != 0) return caseInsensitive
        return b.compareTo(a)
    }

    /**
     * Among a collection of variant surface forms for the **same genre** (they share
     * [genreIdentityKey]), picks the best display name:
     *
     * 1. Space-separated beats hyphenated or connector forms (`Pop Rock` > `Rock & Pop`).
     * 2. Singular over plural (`Soundtrack` > `Soundtracks`).
     * 3. Title Case preferred over lowercase or ALL-CAPS.
     * 4. Tie-break: shorter, then lexicographic.
     */
    fun preferredGenreDisplayName(variants: Collection<String>): String {
        if (variants.size <= 1) return toTitleCase(variants.firstOrNull().orEmpty())
        return variants
            .maxWithOrNull(Comparator { a, b -> compareGenreVariants(a, b) })
            ?.let { toTitleCase(it) }
            .orEmpty()
    }

    private fun isTitleCase(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { word ->
            word[0].isUpperCase() && (word.length == 1 || word.drop(1).any { it.isLowerCase() })
        }
    }

    private fun toTitleCase(text: String): String {
        if (text.isEmpty()) return text
        val cleaned = text.replace(HYPHENS_DASHES, " ")
            .replace(CONNECTORS, " ")
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace(WHITESPACE, " ")
            .trim()
        return cleaned.split(' ').joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word[0].uppercaseChar() + word.substring(1).lowercase()
        }
    }

    private fun compareGenreVariants(a: String, b: String): Int {
        // 1. Space-separated beats hyphenated / connector
        val aHasConnector = a.contains('&') || a.contains('-') || a.contains('–') || a.contains('/')
        val bHasConnector = b.contains('&') || b.contains('-') || b.contains('–') || b.contains('/')
        if (aHasConnector != bHasConnector) return if (aHasConnector) -1 else 1

        // 2. Singular beats plural
        val aPlural = a.endsWith("s", ignoreCase = true) && !a.endsWith("ss", ignoreCase = true)
        val bPlural = b.endsWith("s", ignoreCase = true) && !b.endsWith("ss", ignoreCase = true)
        if (aPlural != bPlural) return if (aPlural) -1 else 1

        // 3. Title Case beats non-Title Case
        val aTitle = isTitleCase(a)
        val bTitle = isTitleCase(b)
        if (aTitle != bTitle) return if (aTitle) 1 else -1

        // 4. Shorter (cleaner)
        val lenDiff = b.length.compareTo(a.length) // shorter preferred
        if (lenDiff != 0) return lenDiff

        // 5. Lexicographic
        return a.compareTo(b)
    }

    // --- Artist/genre splitting -----------------------------------------------

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
            // Discard redundant composite headers: if a `;`-delimited field contains a
            // composite header (e.g. "A, B & C") followed by individual artists ("A","B","C"),
            // the individual tokens already cover all artists. Remove any token whose
            // sub-artists are all already present in the result set.
            if (result.size > 1) {
                discardRedundantComposites(result, knownArtists)
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

    /**
     * Removes composite header tokens from [result] when all their sub-artists already
     * appear individually. E.g. "Walter Ríos, Ulises Butrón & Popi Spatocco" is removed
     * if "Walter Ríos", "Ulises Butrón", "Popi Spatocco" are already present.
     */
    private fun discardRedundantComposites(
        result: MutableList<String>,
        knownArtists: Collection<String>
    ) {
        val identityKeys = result.map { artistIdentityKey(it) }.toSet()
        val toRemove = mutableListOf<Int>()
        for (i in result.indices) {
            val token = result[i]
            // Only check tokens that look like composites (contain , or &)
            if (!token.contains(',') && !token.contains('&')) continue
            val subParts = token.split(GUEST_DELIM_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            if (subParts.size < 2) continue
            // If all sub-parts have their identity key already in the result, this composite is redundant
            if (subParts.all { sub -> identityKeys.contains(artistIdentityKey(sub)) }) {
                toRemove.add(i)
            }
        }
        // Remove in reverse to preserve indices
        for (idx in toRemove.asReversed()) {
            result.removeAt(idx)
        }
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

    /**
     * Builds the set of candidate artist names from a collection of items,
     * used by [splitArtists] for dynamic `,`/`&` splitting against known artists.
     *
     * Extracts raw artist strings and their feat-head prefixes, filtering out
     * placeholder values. This centralizes the logic repeated across
     * [GetLibrarySongsUseCase] and [MusicRepository].
     */
    fun <T> buildCandidateArtists(
        items: Collection<T>,
        artistOf: (T) -> String,
        isPlaceholder: (String) -> Boolean = { false }
    ): Set<String> {
        val candidates = HashSet<String>(items.size)
        for (item in items) {
            val a = artistOf(item).trim()
            if (a.isNotEmpty() && !isPlaceholder(a)) {
                val featHead = a.substringBefore(" feat.")
                    .substringBefore(" ft.")
                    .substringBefore(";")
                    .trim()
                if (featHead.isNotEmpty()) candidates.add(featHead)
                candidates.add(a)
            }
        }
        return candidates
    }
}
