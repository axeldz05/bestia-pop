package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song

import java.util.concurrent.ConcurrentHashMap

private val normalizeCache = ConcurrentHashMap<String, String>()
private val stripDecorCache = ConcurrentHashMap<String, String>()
private val liveSessionCache = ConcurrentHashMap<String, Boolean>()

/**
 * Display-level album title cleanup (trim, ellipsis / mojibake). Matching uses
 * [albumIdentityKey], which also folds case, diacritics, edition suffixes, and
 * other-script subtitles.
 */
fun normalizeAlbumName(name: String): String =
    normalizeCache.computeIfAbsent(name) { raw ->
        var s = raw.trim()
        if (s.contains('\u2026') || s.contains("\u00E2\u0080\u00A6") || s.contains("\u00E2\u20AC\u00A6")) {
            s = s.replace("\u2026", "...")
            s = s.replace("\u00E2\u0080\u00A6", "...")
            s = s.replace("\u00E2\u20AC\u00A6", "...")
        }
        s = s.replace(WHITESPACE, " ")
        s
    }

fun albumIdentityKey(name: String): String =
    identityKeyCache.computeIfAbsent(name) {
        TrackMatchKeys.normalize(stripAlbumEditionDecor(normalizeAlbumName(it)))
    }

fun albumArtistKey(artist: String, album: String): String =
    "${TrackMatchKeys.normalize(artist)}|${albumIdentityKey(album)}"

fun albumNamesMatch(a: String, b: String): Boolean {
    val ka = albumIdentityKey(a)
    val kb = albumIdentityKey(b)
    return ka.isNotEmpty() && ka == kb
}

fun stripAlbumEditionDecor(name: String): String =
    stripDecorCache.computeIfAbsent(name) { raw ->
        var s = raw.trim()
        if (s.isEmpty()) return@computeIfAbsent s
        var previous = ""
        while (s != previous) {
            previous = s
            s = EDITION_PAREN.replace(s, " ").trim()
            s = EDITION_BRACKET.replace(s, " ").trim()
            s = EDITION_SUFFIX.replace(s, " ").trim()
            s = OTHER_SCRIPT_SUFFIX.replace(s) { match ->
                if (isOtherScriptSubtitle(match.groupValues[1])) "" else match.value
            }.trim()
            s = s.replace(WHITESPACE, " ").trim()
        }
        s.ifBlank { raw.trim() }
    }

fun isLiveSessionAlbum(name: String): Boolean =
    liveSessionCache.computeIfAbsent(name) { raw ->
        val n = TrackMatchKeys.normalize(raw)
        if (n.isEmpty()) return@computeIfAbsent false
        SESSION_PHRASES.any { phrase ->
            n == phrase || n.endsWith(" $phrase") || n.startsWith("$phrase ") || n.contains(" $phrase ")
        }
    }

fun preferredAlbumDisplayName(names: Collection<String>): String {
    if (names.isEmpty()) return ""

    val counts = HashMap<String, Int>(names.size)
    for (name in names) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            counts[trimmed] = (counts[trimmed] ?: 0) + 1
        }
    }
    if (counts.isEmpty()) return ""

    val winner = counts.keys.minWith(
        compareBy<String> { if (isLiveSessionAlbum(it)) 1 else 0 }
            .thenByDescending { counts[it] ?: 0 }
            .thenBy { if (normalizeAlbumName(it) == stripAlbumEditionDecor(it)) 0 else 1 }
            .thenBy { it.length }
            .thenBy { it }
    )
    val stripped = stripAlbumEditionDecor(normalizeAlbumName(winner))
    if (stripped.isEmpty()) return winner
    for (key in counts.keys) {
        if (normalizeAlbumName(key) == stripped) return key
    }
    return stripped
}

fun studioAlbumKeysByArtist(
    songs: Iterable<Song>,
    isGeneric: (String) -> Boolean
): Map<String, List<String>> {
    val map = LinkedHashMap<String, LinkedHashSet<String>>()
    for (song in songs) {
        if (isGeneric(song.album) || isLiveSessionAlbum(song.album)) continue
        val artist = TrackMatchKeys.normalize(song.artist)
        val key = albumIdentityKey(song.album)
        if (artist.isEmpty() || key.isEmpty()) continue
        map.getOrPut(artist) { LinkedHashSet() }.add(key)
    }
    return map.mapValues { it.value.toList() }
}

fun albumGroupingKey(
    album: String,
    artist: String,
    studioKeysByArtist: Map<String, List<String>>,
    isGeneric: (String) -> Boolean
): String {
    val identity = albumIdentityKey(album)
    if (isGeneric(album) || !isLiveSessionAlbum(album)) return identity
    return studioKeysByArtist[TrackMatchKeys.normalize(artist)]?.singleOrNull() ?: identity
}

fun songsByAlbumBucket(
    songs: List<Song>,
    isGeneric: (String) -> Boolean
): Map<String, List<Song>> {
    val studio = studioAlbumKeysByArtist(songs, isGeneric)
    return songs.groupBy { song ->
        albumGroupingKey(song.album, song.artist, studio, isGeneric)
    }
}

fun songsMatchingAlbumBucket(
    songs: List<Song>,
    albumKey: String,
    isGeneric: (String) -> Boolean
): List<Song> {
    val grouped = songsByAlbumBucket(songs, isGeneric)
    val target = albumIdentityKey(albumKey)
    if (target.isEmpty()) {
        return grouped[albumKey]
            ?: songs.filter { it.album.equals(albumKey, ignoreCase = true) }
    }
    return grouped[target].orEmpty()
}

fun libraryAlbumKeysInBucket(
    songs: List<Song>,
    targetAlbum: String,
    isGeneric: (String) -> Boolean
): List<String> {
    return songsMatchingAlbumBucket(songs, targetAlbum, isGeneric)
        .map { it.album }
        .distinct()
}

fun pickPersistedAlbumName(
    library: List<Song>,
    proposedAlbum: String,
    proposedArtist: String,
    sourceAlbum: String = "",
    isGeneric: (String) -> Boolean
): String = pickPersistedAlbumName(
    library = library,
    proposedAlbum = proposedAlbum,
    proposedArtist = proposedArtist,
    sourceAlbum = sourceAlbum,
    isGeneric = isGeneric,
    studioKeysByArtist = studioAlbumKeysByArtist(library, isGeneric)
)

fun pickPersistedAlbumName(
    library: List<Song>,
    proposedAlbum: String,
    proposedArtist: String,
    sourceAlbum: String = "",
    isGeneric: (String) -> Boolean,
    studioKeysByArtist: Map<String, List<String>>
): String {
    val proposed = proposedAlbum.trim().ifBlank { sourceAlbum }
    if (proposed.isEmpty()) return proposedAlbum
    val source = sourceAlbum.trim()
    if (source.isNotEmpty() && !isGeneric(source) &&
        isLiveSessionAlbum(proposed) && !isLiveSessionAlbum(source)
    ) {
        return source
    }
    val key = albumGroupingKey(proposed, proposedArtist, studioKeysByArtist, isGeneric)
    val existing = library.map { it.album }.filter { album ->
        albumIdentityKey(album) == key ||
            albumGroupingKey(album, proposedArtist, studioKeysByArtist, isGeneric) == key
    }
    val cleaned = stripAlbumEditionDecor(normalizeAlbumName(proposed)).ifBlank { proposed }
    return preferredAlbumDisplayName(existing + cleaned).ifBlank { proposed }
}

fun pickPersistedArtistName(
    existingArtists: Collection<String>,
    proposed: String
): String {
    val compatible = existingArtists.filter { artistsCompatible(it, proposed) }
    if (compatible.isEmpty()) return proposed
    return compatible.minWith(compareBy<String> { it.length }.thenBy { it })
}

fun artistsCompatible(a: String, b: String): Boolean {
    val na = TrackMatchKeys.normalize(a)
    val nb = TrackMatchKeys.normalize(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    if (na == nb) return true
    return na.startsWith("$nb ") || nb.startsWith("$na ")
}

fun dominantNonBlank(values: Iterable<String>, default: String): String {
    val counts = HashMap<String, Int>(8)
    for (v in values) {
        if (v.isNotBlank()) {
            counts[v] = (counts[v] ?: 0) + 1
        }
    }
    if (counts.isEmpty()) return default
    return counts.entries.maxWith(
        compareBy<Map.Entry<String, Int>> { it.value }
            .thenBy { -it.key.length }
            .thenBy { it.key }
    ).key
}

private val identityKeyCache = ConcurrentHashMap<String, String>()
private val WHITESPACE = Regex("\\s+")

private fun isOtherScriptSubtitle(raw: String): Boolean {
    val letters = raw.filter { it.isLetter() }
    if (letters.isEmpty()) return false
    var hasLatin = false
    var hasOther = false
    for (ch in letters) {
        when (Character.UnicodeScript.of(ch.code)) {
            Character.UnicodeScript.LATIN -> hasLatin = true
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> Unit
            else -> hasOther = true
        }
    }
    return hasOther && !hasLatin
}

private val EDITION_TOKEN =
    """(?:deluxe(?:\s+edition)?|(?:special|limited|expanded|bonus|anniversary|explicit)(?:\s+edition)?|""" +
        """remaster(?:ed)?(?:\s+\d{4})?|edition|bonus(?:\s+tracks?)?|ep|single|""" +
        """audiotree\s+live|mahogany\s+sessions?|tiny\s+desk|from\s+the\s+basement|""" +
        """like\s+a\s+version|colors\s+show)"""

private val EDITION_PAREN = Regex(
    """\s*\(\s*$EDITION_TOKEN\s*\)""",
    RegexOption.IGNORE_CASE
)
private val EDITION_BRACKET = Regex(
    """\s*\[\s*$EDITION_TOKEN\s*]""",
    RegexOption.IGNORE_CASE
)
private val EDITION_SUFFIX = Regex(
    """\s*[-–—]\s*$EDITION_TOKEN\s*$""",
    RegexOption.IGNORE_CASE
)
private val OTHER_SCRIPT_SUFFIX = Regex("""\s*[-–—]\s*(.+)$""")

private val SESSION_PHRASES = listOf(
    "audiotree live",
    "mahogany session",
    "mahogany sessions",
    "tiny desk",
    "from the basement",
    "like a version",
    "colors show"
)
