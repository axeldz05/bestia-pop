package com.bestiapop.android.domain.util

/**
 * Artist/title recovered from rip-style filenames or weak tags
 * (`NN_-_Title`, `NN - Title`, `Artist - Song`, BestiaPop `Artist_Title`).
 *
 * BestiaPop downloads use `{artist}_{title}` with spaces and non-ASCII turned into `_`.
 * A single remaining `_` is the artist/title split (`Radiohead_Creep`); several `_`
 * tokens stay one searchable phrase so `The_Doors_Roadhouse_Blues` is not artist=`The`.
 */
data class FilenameMetadataHints(
    val artist: String?,
    val title: String?,
    val trackNumber: Int? = null
)

private val TRACK_NUM_ONLY = Regex("""^\d{1,3}$""")
private val DISC_TRACK = Regex("""^\d{1,2}[-.]\d{1,2}\.?$""")
private val SPACED_DASH = Regex("""\s+[-–—]\s+""")
private val DISC_TRACK_FILE = Regex("""^(\d{1,2})[-.](\d{1,2})\.?\s*(.+)$""")
private data class TrackFilenamePattern(val regex: Regex, val trackGroup: Int, val phraseGroup: Int)

private val FILENAME_TRACK_PATTERNS = listOf(
    TrackFilenamePattern(DISC_TRACK_FILE, trackGroup = 2, phraseGroup = 3),
    TrackFilenamePattern(Regex("""^(\d{1,3})(?:[-.]\d{1,2})?\s+-\s+(.+)$"""), trackGroup = 1, phraseGroup = 2),
    TrackFilenamePattern(Regex("""^(\d{1,3})\.\s*(.+)$"""), trackGroup = 1, phraseGroup = 2),
    TrackFilenamePattern(Regex("""^(\d{1,3})_(.+)$"""), trackGroup = 1, phraseGroup = 2),
    TrackFilenamePattern(Regex("""^(\d{1,3})\s+([^\d\s].*)$"""), trackGroup = 1, phraseGroup = 2),
    TrackFilenamePattern(Regex("""^(.+?)\s*[\(\[]\s*(\d{1,3})\s*[\)\]]$"""), trackGroup = 2, phraseGroup = 1),
    TrackFilenamePattern(Regex("""^(.+?)\s*[-_]\s*(\d{1,3})$"""), trackGroup = 2, phraseGroup = 1)
)
private val HOLE_RUN = Regex("""_{2,}""")
private val WHITESPACE = Regex("""\s+""")
private val CONTRACTION = Regex(
    """\b([A-Za-z]+) (s|t|d|m|ll|re|ve)\b""",
    RegexOption.IGNORE_CASE
)
private val KHZ_SUFFIX = Regex(
    """\s*\(?\s*\d{1,2}\s*[-–]\s*\d{1,2}(?:\.\d)?\s*kHz\s*\)?\s*$""",
    RegexOption.IGNORE_CASE
)
private val DUP_INDEX_SUFFIX = Regex("""\s*\(\d+\)\s*$""")
private val AUDIO_EXT_SUFFIX = Regex(
    """\.(mp3|flac|m4a|wav|ogg|opus|aac|wma|alac)$""",
    RegexOption.IGNORE_CASE
)
private val SINGLE_LETTER_WORDS = setOf("a", "e", "i", "o", "u", "y")
private val TAIL_SKIP = setOf(
    "part", "pt", "live", "remaster", "remastered", "bonus", "album", "version",
    "feat", "ft", "instrumental", "demo", "remix"
)

/** `02`, `1-12`, `1.03` — not real artist names (unlike `65daysofstatic`). */
fun isTrackNumberLabel(value: String): Boolean {
    val a = value.trim()
    return TRACK_NUM_ONLY.matches(a) || DISC_TRACK.matches(a)
}

fun stripLeadingTitleJunk(value: String): String {
    var s = value.trim()
    while (s.isNotEmpty()) {
        val next = when {
            s.startsWith("- ") || s.startsWith("– ") || s.startsWith("— ") ->
                s.drop(2).trimStart()
            s.first() == '-' || s.first() == '–' || s.first() == '—' || s.first() == '_' ->
                s.drop(1).trimStart()
            else -> break
        }
        if (next == s) break
        s = next
    }
    return IdentifyRanking.stripLeadingTrackNumber(s.trim())
}

/**
 * Collapse sanitizer holes (`__` from CJK/accents), restore `It's` / `I'm`,
 * and drop rip suffixes (`16-44.1 kHz`, `(1)`).
 */
fun tidyFilenamePhrase(value: String): String {
    var s = value.replace(HOLE_RUN, " ").replace('_', ' ')
    s = s.replace(WHITESPACE, " ").trim()
    s = restoreFilenameContractions(s)
    s = stripFilenameTechnicalNoise(s)
    return stripLeadingTitleJunk(s)
}

/** Search strings for identify: full phrase, script runs, pinyin head, OST, tail. */
fun identifySearchTexts(primary: String, filename: String? = null): List<String> =
    IdentifyQueryVariants.expand(primary, filename)

/** `1-07. Midnight Channel` / `1-03_Insisto` — disc-track rip filenames. */
fun looksLikeDiscTrackRip(nameWithoutExtension: String): Boolean {
    val raw = nameWithoutExtension.substringAfterLast('/')
        .replace(AUDIO_EXT_SUFFIX, "")
        .replace("_-_", " - ")
        .trim()
    return raw.isNotEmpty() && DISC_TRACK_FILE.matches(raw)
}

/**
 * Split on spaced dashes / `_-_`. Artist = first segment; title = last
 * (handles `Artist - Album - Title`).
 */
fun splitArtistTitleDash(value: String): Pair<String, String>? {
    val normalized = value.replace("_-_", " - ").trim()
    val parts = normalized
        .split(SPACED_DASH)
        .map { tidyFilenamePhrase(it) }
        .filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val artist = parts.first()
    val title = parts.last()
    if (isTrackNumberLabel(artist) || artist.equals(title, ignoreCase = true)) return null
    return artist to title
}

fun mergeIdentityHints(
    primary: FilenameMetadataHints,
    secondary: FilenameMetadataHints
): FilenameMetadataHints = FilenameMetadataHints(
    artist = primary.artist?.takeIf { it.isNotBlank() } ?: secondary.artist,
    title = primary.title?.takeIf { it.isNotBlank() } ?: secondary.title,
    trackNumber = primary.trackNumber ?: secondary.trackNumber
)

/**
 * When tags look like track-number rips (`02` / `- Title` / embedded `Artist - Song`),
 * recover searchable artist+title.
 */
fun resolveWeakIdentityHints(artist: String, title: String): FilenameMetadataHints {
    val a = artist.trim()
    val cleanedTitle = tidyFilenamePhrase(title)
    val track = if (isTrackNumberLabel(a)) {
        a.takeWhile { it.isDigit() }.toIntOrNull()
            ?: a.filter { it.isDigit() }.take(3).toIntOrNull()
    } else {
        null
    }
    val artistWeak = IdentifyRanking.isPlaceholderArtist(a)

    if (artistWeak) {
        return hintsFromPhraseAndTrack(cleanedTitle, track)
    }

    val titleWithoutArtist = IdentifyRanking.cleanIdentityTitle(cleanedTitle, a)
    val finalTitle = titleWithoutArtist.ifBlank { cleanedTitle.ifBlank { null } }
    return FilenameMetadataHints(a, finalTitle, track)
}

/** Parse BestiaPop `Artist_Title` and common rip filename shapes. */
fun parseFilenameMetadataHints(nameWithoutExtension: String): FilenameMetadataHints {
    val raw = nameWithoutExtension.trim()
    if (raw.isEmpty()) return FilenameMetadataHints(null, null)

    val dashed = raw.replace("_-_", " - ")
    val (phrase, track) = extractTrackAndPhrase(dashed) ?: (raw to null)
    return hintsFromPhraseAndTrack(phrase, track)
}

fun parseFilenameTrackNumber(nameWithoutExtension: String): Int? =
    parseFilenameMetadataHints(nameWithoutExtension).trackNumber

/**
 * When `{artist}_{title}` downloads collapse spaces to `_`, recover artist/title by
 * matching the longest known library artist as a prefix of [phrase].
 */
fun splitUsingKnownArtists(
    phrase: String,
    knownArtists: Collection<String>
): FilenameMetadataHints? {
    val tidy = tidyFilenamePhrase(phrase)
    val haystack = TrackMatchKeys.normalize(tidy)
    if (haystack.isEmpty()) return null
    var bestArtist: String? = null
    var bestNorm = ""
    for (artist in knownArtists) {
        if (IdentifyRanking.isPlaceholderArtist(artist)) continue
        val n = TrackMatchKeys.normalize(artist)
        if (!isUsableKnownArtistKey(n)) continue
        if (n.length < bestNorm.length) continue
        val isPrefix = haystack == n || haystack.startsWith("$n ")
        if (!isPrefix) continue
        bestArtist = artist.trim()
        bestNorm = n
    }
    val artist = bestArtist ?: return null
    val title = titleTailAfterArtist(tidy, artist)
    return FilenameMetadataHints(artist, title)
}

private fun isUsableKnownArtistKey(normalized: String): Boolean {
    if (normalized.length < 4) return false
    val tokens = normalized.split(' ').filter { it.isNotEmpty() }
    return tokens.size >= 2 || normalized.length >= 6
}

private fun titleTailAfterArtist(tidyPhrase: String, artist: String): String? {
    val nArtist = TrackMatchKeys.normalize(artist)
    val tokens = tidyPhrase.split(' ').filter { it.isNotEmpty() }
    var used = 0
    var acc = ""
    for (i in tokens.indices) {
        acc = if (acc.isEmpty()) tokens[i] else "$acc ${tokens[i]}"
        if (TrackMatchKeys.normalize(acc) == nArtist) {
            used = i + 1
            break
        }
    }
    if (used == 0 || used >= tokens.size) return null
    return tokens.drop(used).joinToString(" ").ifBlank { null }
}

private fun extractTrackAndPhrase(value: String): Pair<String, Int>? {
    for (pattern in FILENAME_TRACK_PATTERNS) {
        val m = pattern.regex.matchEntire(value) ?: continue
        val track = m.groupValues[pattern.trackGroup].toIntOrNull()
        if (track != null && track in 1..999) {
            return m.groupValues[pattern.phraseGroup] to track
        }
    }
    return null
}

/**
 * Splits artist and title from a phrase, checking spaced dashes first,
 * then a single BestiaPop underscore.
 */
internal fun splitArtistAndTitle(phrase: String): Pair<String, String>? =
    splitArtistTitleDash(phrase) ?: splitSingleUnderscoreArtistTitle(phrase)

private fun hintsFromPhraseAndTrack(phrase: String, track: Int?): FilenameMetadataHints {
    splitArtistAndTitle(phrase)?.let { (a, t) ->
        return FilenameMetadataHints(a, t, track)
    }
    return FilenameMetadataHints(null, tidyFilenamePhrase(phrase).ifBlank { null }, track)
}

/**
 * After collapsing `__+` holes to spaces, a single leftover `_` is the BestiaPop
 * artist/title join. Several `_` mean a multi-word artist — keep as one phrase.
 */
private fun splitSingleUnderscoreArtistTitle(raw: String): Pair<String, String>? {
    val withHoles = raw.replace(HOLE_RUN, " ").trim()
    if (withHoles.count { it == '_' } != 1) return null
    val idx = withHoles.indexOf('_')
    if (idx <= 0 || idx >= withHoles.lastIndex) return null
    val artist = tidyFilenamePhrase(withHoles.substring(0, idx))
    val title = tidyFilenamePhrase(withHoles.substring(idx + 1))
    if (artist.isEmpty() || title.isEmpty()) return null
    if (isTrackNumberLabel(artist) || artist.equals(title, ignoreCase = true)) return null
    return artist to title
}

internal fun restoreFilenameContractions(value: String): String =
    CONTRACTION.replace(value) { m -> "${m.groupValues[1]}'${m.groupValues[2]}" }

internal fun stripFilenameTechnicalNoise(value: String): String {
    var s = KHZ_SUFFIX.replace(value, "").trim()
    s = DUP_INDEX_SUFFIX.replace(s, "").trim()
    return s
}

internal fun glueSingleLetterTokens(text: String): String {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    if (tokens.size < 2) return text
    val out = ArrayList<String>(tokens.size)
    var i = 0
    while (i < tokens.size) {
        val tok = tokens[i]
        val hole = tok.length == 1 &&
            tok[0].isLetter() &&
            tok.lowercase() !in SINGLE_LETTER_WORDS
        if (!hole) {
            out.add(tok)
            i += 1
            continue
        }
        val glueForward = tok[0].isUpperCase() && i + 1 < tokens.size
        when {
            glueForward -> {
                out.add(tok + tokens[i + 1])
                i += 2
            }
            out.isNotEmpty() -> {
                out[out.lastIndex] = out.last() + tok
                i += 1
            }
            i + 1 < tokens.size -> {
                out.add(tok + tokens[i + 1])
                i += 2
            }
            else -> {
                out.add(tok)
                i += 1
            }
        }
    }
    val glued = out.joinToString(" ")
    return if (glued == text) text else glued
}

internal fun distinctiveSearchTail(text: String): String? {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    if (tokens.size < 5) return null
    val useful = tokens.dropLastWhile { token ->
        val n = token.lowercase().trim('(', ')', ',', '.', '-', '–', '—')
        n in TAIL_SKIP || n.all { it.isDigit() }
    }
    val tail = useful.takeLast(3).joinToString(" ")
    return tail.takeIf { it.length >= 8 && !it.equals(text, ignoreCase = true) }
}

internal fun headAndTailSearch(text: String): String? {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    if (tokens.size < 6) return null
    val combined = (tokens.take(2) + tokens.takeLast(2)).joinToString(" ")
    return combined.takeIf { !it.equals(text, ignoreCase = true) }
}
