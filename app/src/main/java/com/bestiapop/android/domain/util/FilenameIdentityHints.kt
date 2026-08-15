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
private val TRACK_DASH_TITLE = Regex("""^(\d{1,3})(?:[-.]\d{1,2})?\s+-\s+(.+)$""")
private val TRACK_DOT_TITLE = Regex("""^(\d{1,3})\.\s*(.+)$""")
private val TRACK_UNDERSCORE_TITLE = Regex("""^(\d{1,3})_(.+)$""")
private val DISC_TRACK_FILE = Regex("""^(\d{1,2})[-.](\d{1,2})\.?\s*(.+)$""")
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
    return s.trim()
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

/** Search strings for identify: full phrase, glued accent holes, distinctive tail. */
fun identifySearchTexts(primary: String): List<String> {
    val cleaned = tidyFilenamePhrase(primary)
    if (cleaned.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>(4)
    out.add(cleaned)
    val glued = glueSingleLetterTokens(cleaned)
    if (glued != cleaned) out.add(glued)
    distinctiveSearchTail(cleaned)?.let { out.add(it) }
    headAndTailSearch(cleaned)?.let { out.add(it) }
    return out.take(3)
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
        splitArtistTitleDash(cleanedTitle)?.let { (art, tit) ->
            return FilenameMetadataHints(art, tit, track)
        }
        return FilenameMetadataHints(null, cleanedTitle.ifBlank { null }, track)
    }

    if (cleanedTitle != title.trim() && cleanedTitle.isNotEmpty()) {
        return FilenameMetadataHints(a, cleanedTitle)
    }
    return FilenameMetadataHints(a, cleanedTitle.ifBlank { null })
}

/** Parse BestiaPop `Artist_Title` and common rip filename shapes. */
fun parseFilenameMetadataHints(nameWithoutExtension: String): FilenameMetadataHints {
    val raw = nameWithoutExtension.trim()
    if (raw.isEmpty()) return FilenameMetadataHints(null, null)

    val dashed = raw.replace("_-_", " - ")

    DISC_TRACK_FILE.matchEntire(dashed)?.let { m ->
        return hintsFromTrackRest(m.groupValues[2].toIntOrNull(), m.groupValues[3])
    }
    TRACK_DASH_TITLE.matchEntire(dashed)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }
    TRACK_DOT_TITLE.matchEntire(dashed)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }
    TRACK_UNDERSCORE_TITLE.matchEntire(raw)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }

    splitArtistTitleDash(dashed)?.let { (a, t) ->
        return FilenameMetadataHints(a, t)
    }

    splitSingleUnderscoreArtistTitle(raw)?.let { (a, t) ->
        return FilenameMetadataHints(a, t)
    }

    return FilenameMetadataHints(null, tidyFilenamePhrase(raw).ifBlank { null })
}

private fun hintsFromTrackRest(track: Int?, rest: String): FilenameMetadataHints {
    val restDashed = rest.replace("_-_", " - ").trim()
    val spaced = if (SPACED_DASH.containsMatchIn(restDashed)) {
        restDashed.split(SPACED_DASH)
            .joinToString(" - ") { tidyFilenamePhrase(it) }
    } else {
        tidyFilenamePhrase(restDashed)
    }
    val cleaned = spaced.ifBlank { null }
    splitArtistTitleDash(spaced)?.let { (a, t) ->
        return FilenameMetadataHints(a, t, track)
    }
    return FilenameMetadataHints(null, cleaned, track)
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
