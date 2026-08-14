package com.bestiapop.android.domain.util

/**
 * Artist/title recovered from rip-style filenames or weak tags
 * (`NN_-_Title`, `NN - Title`, `Artist - Song`, BestiaPop `Artist_Title`).
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
 * Split on spaced dashes / `_-_`. Artist = first segment; title = last
 * (handles `Artist - Album - Title`).
 */
fun splitArtistTitleDash(value: String): Pair<String, String>? {
    val normalized = value.replace("_-_", " - ").trim()
    val parts = normalized
        .split(SPACED_DASH)
        .map { it.replace('_', ' ').trim() }
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
    val cleanedTitle = stripLeadingTitleJunk(title.trim())
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

    TRACK_DASH_TITLE.matchEntire(dashed)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }
    TRACK_DOT_TITLE.matchEntire(dashed)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }
    // `02_Title` / leftover after `_-_` normalize missed (digits + underscore)
    TRACK_UNDERSCORE_TITLE.matchEntire(raw)?.let { m ->
        return hintsFromTrackRest(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }

    splitArtistTitleDash(dashed)?.let { (a, t) ->
        return FilenameMetadataHints(a, stripLeadingTitleJunk(t))
    }

    val idx = raw.indexOf('_')
    if (idx > 0 && idx < raw.lastIndex) {
        val artistPart = raw.substring(0, idx).replace('_', ' ').trim()
        val titlePart = raw.substring(idx + 1)
        if (isTrackNumberLabel(artistPart)) {
            return hintsFromTrackRest(artistPart.toIntOrNull(), titlePart)
        }
        val title = stripLeadingTitleJunk(titlePart.replace('_', ' ').trim()).ifBlank { null }
        return FilenameMetadataHints(artistPart.ifBlank { null }, title)
    }

    return FilenameMetadataHints(
        null,
        stripLeadingTitleJunk(raw.replace('_', ' ')).ifBlank { null }
    )
}

private fun hintsFromTrackRest(track: Int?, rest: String): FilenameMetadataHints {
    val restDashed = rest.replace("_-_", " - ").trim()
    val spaced = if (SPACED_DASH.containsMatchIn(restDashed)) {
        restDashed.split(SPACED_DASH)
            .joinToString(" - ") { it.replace('_', ' ').trim() }
    } else {
        restDashed.replace('_', ' ').trim()
    }
    val cleaned = stripLeadingTitleJunk(spaced)
    splitArtistTitleDash(cleaned)?.let { (a, t) ->
        return FilenameMetadataHints(a, t, track)
    }
    return FilenameMetadataHints(null, cleaned.ifBlank { null }, track)
}
