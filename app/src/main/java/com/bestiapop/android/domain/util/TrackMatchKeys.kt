package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.data.util.albumTrackDisplayNumber
import java.text.Normalizer

/**
 * Shared artist+title matching keys used by radio, downloads, imports, and library lookups.
 * [normalize] folds case, punctuation, and diacritics (so "canción" ≡ "cancion").
 */
object TrackMatchKeys {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val PUNCT = Regex("[\\p{Punct}\\p{IsPunctuation}]")
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_NORMALIZE_CACHE_ENTRIES = 1000

    private val normalizeCache =
        object : java.util.LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_NORMALIZE_CACHE_ENTRIES
        }
    private val cacheLock = Any()

    private fun hasNonAscii(s: String): Boolean {
        for (i in 0 until s.length) {
            if (s[i].code > 127) return true
        }
        return false
    }

    fun normalize(value: String): String {
        if (value.isEmpty()) return ""
        val cacheCandidate = value.length <= 80
        if (cacheCandidate) {
            synchronized(cacheLock) {
                normalizeCache[value]?.let { return it }
            }
        }

        val base =
            if (hasNonAscii(value)) {
                Normalizer.normalize(value, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            } else {
                value
            }

        val result =
            base
                .lowercase()
                .replace(PUNCT, " ")
                .replace(WHITESPACE, " ")
                .trim()

        if (cacheCandidate) {
            synchronized(cacheLock) {
                normalizeCache[value] = result
            }
        }
        return result
    }

    /** Substring match after [normalize] (blank [needle] matches everything). */
    fun containsNormalized(
        haystack: String,
        needle: String,
    ): Boolean {
        if (needle.isBlank()) return true
        val n = normalize(needle)
        if (n.isEmpty()) return false
        return normalize(haystack).contains(n)
    }

    fun matchKey(
        artist: String,
        title: String,
    ): String {
        val a = normalize(artist)
        val t = normalize(title)
        return composeKey(a, t)
    }

    /** Level 1: Combines two pre-normalized strings into a composite key ($part1|$part2). */
    fun composeKey(
        part1: String,
        part2: String,
    ): String {
        if (part1.isEmpty() || part2.isEmpty()) return ""
        return "$part1|$part2"
    }

    /** Level 1: Combines pre-normalized components without re-executing Normalizer or Regex. */
    fun matchKeyPreNormalized(
        normalizedArtist: String,
        normalizedTitle: String,
    ): String = composeKey(normalizedArtist, normalizedTitle)

    private val PARENTHESES_REGEX = Regex("""[\(\[\{]([^\)\]\}]+)[\)\]\}]""")

    private fun isJapaneseText(text: String): Boolean {
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                return true
            }
        }
        return false
    }

    private fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u30A1'..'\u30F6') {
                sb.append((ch.code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun phoneticTextVariants(text: String): Set<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptySet()
        val variants = LinkedHashSet<String>()
        variants.add(trimmed)

        // 1. Compact & underscore normalizations (e.g. "Nichijou_seikatsu" <-> "Nichijou seikatsu" <-> "nichijouseikatsu")
        val compact = trimmed.replace(" ", "").replace("_", "")
        if (compact.isNotEmpty() && compact != trimmed) {
            variants.add(compact)
        }
        if (trimmed.contains('_')) {
            variants.add(trimmed.replace('_', ' '))
        }

        // 2. Word-final / loanword ending: -i <-> -y, -ii <-> -y (e.g. neoteni <-> neoteny, party <-> parti)
        if (trimmed.endsWith("i", ignoreCase = true) && !trimmed.endsWith("ii", ignoreCase = true)) {
            variants.add(trimmed.dropLast(1) + (if (trimmed.last().isUpperCase()) "Y" else "y"))
        } else if (trimmed.endsWith("ii", ignoreCase = true)) {
            variants.add(trimmed.dropLast(2) + (if (trimmed.last().isUpperCase()) "Y" else "y"))
        } else if (trimmed.endsWith("y", ignoreCase = true)) {
            variants.add(trimmed.dropLast(1) + (if (trimmed.last().isUpperCase()) "I" else "i"))
            variants.add(trimmed.dropLast(1) + (if (trimmed.last().isUpperCase()) "II" else "ii"))
        }

        // 3. Romaji long vowels: ou <-> o, oo <-> o, oh <-> o
        if (trimmed.contains("ou", ignoreCase = true)) {
            variants.add(trimmed.replace("ou", "o").replace("OU", "O").replace("Ou", "O"))
        }
        if (trimmed.contains("oo", ignoreCase = true)) {
            variants.add(trimmed.replace("oo", "o").replace("OO", "O").replace("Oo", "O"))
        }
        if (trimmed.contains("oh", ignoreCase = true)) {
            variants.add(trimmed.replace("oh", "o").replace("OH", "O").replace("Oh", "O"))
        }

        // 4. Consonant variations: jyo <-> jo / zyo, jya <-> ja, jyu <-> ju, shi <-> si, chi <-> ti, tsu <-> tu, fu <-> hu
        if (trimmed.contains("jyo", ignoreCase = true)) {
            variants.add(trimmed.replace("jyo", "jo").replace("Jyo", "Jo").replace("JYO", "JO"))
            variants.add(trimmed.replace("jyo", "zyo").replace("Jyo", "Zyo").replace("JYO", "ZYO"))
        } else if (trimmed.contains("jo", ignoreCase = true)) {
            variants.add(trimmed.replace("jo", "jyo").replace("Jo", "Jyo").replace("JO", "JYO"))
        }
        if (trimmed.contains("jya", ignoreCase = true)) {
            variants.add(trimmed.replace("jya", "ja").replace("Jya", "Ja").replace("JYA", "JA"))
        } else if (trimmed.contains("ja", ignoreCase = true)) {
            variants.add(trimmed.replace("ja", "jya").replace("Ja", "Jya").replace("JA", "JYA"))
        }
        if (trimmed.contains("jyu", ignoreCase = true)) {
            variants.add(trimmed.replace("jyu", "ju").replace("Jyu", "Ju").replace("JYU", "JU"))
        } else if (trimmed.contains("ju", ignoreCase = true)) {
            variants.add(trimmed.replace("ju", "jyu").replace("Ju", "Jyu").replace("JU", "JYU"))
        }
        if (trimmed.contains("shi", ignoreCase = true)) {
            variants.add(trimmed.replace("shi", "si").replace("Shi", "Si").replace("SHI", "SI"))
        } else if (trimmed.contains("si", ignoreCase = true)) {
            variants.add(trimmed.replace("si", "shi").replace("Si", "Shi").replace("SI", "SHI"))
        }
        if (trimmed.contains("chi", ignoreCase = true)) {
            variants.add(trimmed.replace("chi", "ti").replace("Chi", "Ti").replace("CHI", "TI"))
        } else if (trimmed.contains("ti", ignoreCase = true)) {
            variants.add(trimmed.replace("ti", "chi").replace("Ti", "Chi").replace("TI", "CHI"))
        }
        if (trimmed.contains("tsu", ignoreCase = true)) {
            variants.add(trimmed.replace("tsu", "tu").replace("Tsu", "Tu").replace("TSU", "TU"))
        } else if (trimmed.contains("tu", ignoreCase = true)) {
            variants.add(trimmed.replace("tu", "tsu").replace("Tu", "Tsu").replace("TU", "TSU"))
        }
        if (trimmed.contains("fu", ignoreCase = true)) {
            variants.add(trimmed.replace("fu", "hu").replace("Fu", "Hu").replace("FU", "HU"))
        } else if (trimmed.contains("hu", ignoreCase = true)) {
            variants.add(trimmed.replace("hu", "fu").replace("Hu", "Fu").replace("HU", "FU"))
        }

        return variants
    }

    /**
     * Level 1: Generates candidate match keys for an artist + title pair.
     * Includes canonical matchKey, transliterated non-Latin characters,
     * Japanese Romaji loanwords/phonetics, r/l variations, bilingual/parenthesized sub-parts,
     * and cosmetic-noise-stripped titles.
     */
    fun candidateMatchKeys(
        artist: String,
        title: String,
    ): List<String> {
        val canonical = matchKey(artist, title)
        if (canonical.isEmpty()) return emptyList()

        val cleanArt = artist.trimEnd('.', ' ', '-', ':')
        val transArtist = NaturalTextOrder.transliterateToLatin(artist)
        val cleanTransArtist = NaturalTextOrder.transliterateToLatin(cleanArt)
        val artistVariants = LinkedHashSet<String>()
        artistVariants.add(artist)
        if (cleanArt.isNotBlank()) artistVariants.add(cleanArt)
        if (transArtist.isNotBlank()) artistVariants.add(transArtist)
        if (cleanTransArtist.isNotBlank()) artistVariants.add(cleanTransArtist)
        if (artist.contains('-')) artistVariants.add(artist.replace("-", " "))
        if (transArtist.contains('-')) artistVariants.add(transArtist.replace("-", " "))
        if (isJapaneseText(artist)) {
            val hira = katakanaToHiragana(artist)
            if (hira.isNotBlank() && hira != artist) {
                artistVariants.add(hira)
                val transHira = NaturalTextOrder.transliterateToLatin(hira)
                if (transHira.isNotBlank()) artistVariants.add(transHira)
            }
        }
        val baseArtists = ArrayList(artistVariants)
        for (a in baseArtists) {
            artistVariants.addAll(phoneticTextVariants(a))
        }

        val out = LinkedHashSet<String>()

        fun emitRlVariants(
            art: String,
            t: String,
        ) {
            if (t.contains('r', ignoreCase = true) || t.contains('l', ignoreCase = true)) {
                val rToL = t.replace('r', 'l').replace('R', 'L')
                val lToR = t.replace('l', 'r').replace('L', 'R')
                if (rToL != t) {
                    val k = matchKey(art, rToL)
                    if (k.isNotEmpty()) out.add(k)
                }
                if (lToR != t) {
                    val k = matchKey(art, lToR)
                    if (k.isNotEmpty()) out.add(k)
                }
            }
        }

        fun emitTitle(t: String) {
            val trimmed = t.trim()
            if (trimmed.isEmpty()) return
            val trans = NaturalTextOrder.transliterateToLatin(trimmed)
            val titlesToProcess = LinkedHashSet<String>()
            titlesToProcess.add(trimmed)
            if (trans.isNotBlank() && trans != trimmed) {
                titlesToProcess.add(trans)
            }
            if (isJapaneseText(trimmed)) {
                val hira = katakanaToHiragana(trimmed)
                if (hira.isNotBlank() && hira != trimmed) {
                    titlesToProcess.add(hira)
                    val transHira = NaturalTextOrder.transliterateToLatin(hira)
                    if (transHira.isNotBlank()) titlesToProcess.add(transHira)
                }
            }

            for (rawTitle in titlesToProcess) {
                val variants = phoneticTextVariants(rawTitle)
                for (v in variants) {
                    for (art in artistVariants) {
                        val key = matchKey(art, v)
                        if (key.isNotEmpty()) out.add(key)
                        emitRlVariants(art, v)
                    }
                }
            }
        }

        // 1. Primary title (canonical, transliterated, phonetic, r/l)
        emitTitle(title)

        // 2. Parenthesized / bilingual portions
        for (match in PARENTHESES_REGEX.findAll(title)) {
            emitTitle(match.groupValues[1])
        }
        val outside = PARENTHESES_REGEX.replace(title, " ").trim()
        if (outside != title) {
            emitTitle(outside)
        }

        // 3. Delimiter-separated titles
        if (title.contains('/')) {
            for (part in title.split('/')) {
                emitTitle(part)
            }
        }

        // 4. Cosmetic noise stripping
        val clean = IdentifyRanking.cleanIdentityTitle(title, artist)
        if (clean != title) {
            emitTitle(clean)
        }

        return out.toList()
    }

    /** Level 2: Candidate match keys directly from [TrackMeta] without unpacking. */
    fun candidateMatchKeys(meta: TrackMeta): List<String> = candidateMatchKeys(meta.artist, meta.title)

    /** L2: stable [ActiveDownload] / queue id from artist+title (empty if either blank). */
    fun downloadIdFor(
        artist: String,
        title: String,
    ): String = matchKey(artist, title)

    /** Level 2: stable download id directly from [TrackMeta] without unpacking. */
    fun downloadIdFor(meta: TrackMeta): String = meta.matchKey()

    /** Batch catalog job id; pairs with [downloadIdFor] for [findByTrack] lookup. */
    fun batchDownloadIdFor(
        artist: String,
        title: String,
    ): String {
        val key = downloadIdFor(artist, title)
        return if (key.isEmpty()) "" else "batch:$key"
    }

    /**
     * Every id a download of this track can carry (plain + `batch:`). They are distinct ids but they
     * resolve to the same destination filename, so any check for "is this track already downloading"
     * has to span the whole set — single source of truth for `findByTrack` and the enqueue gate.
     */
    fun downloadIdVariantsFor(
        artist: String,
        title: String,
    ): List<String> {
        val keys = candidateMatchKeys(artist, title)
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<String>(keys.size * 2)
        for (key in keys) {
            out.add(key)
            out.add("batch:$key")
        }
        return out
    }

    /** Level 2: Download ID variants directly from [TrackMeta] without unpacking. */
    fun downloadIdVariantsFor(meta: TrackMeta): List<String> = downloadIdVariantsFor(meta.artist, meta.title)

    fun buildLibraryIndex(library: List<Song>): Map<String, Song> {
        val map = HashMap<String, Song>(library.size * 2)
        // Pass 1: Local songs canonical keys take absolute top priority
        for (song in library) {
            if (!song.isRemote) {
                val canonical = matchKey(song.artist, song.title)
                if (canonical.isNotEmpty()) {
                    map[canonical] = song
                }
            }
        }
        // Pass 2: Local songs candidate/alias keys
        for (song in library) {
            if (!song.isRemote) {
                for (key in candidateMatchKeys(song)) {
                    map.putIfAbsent(key, song)
                }
            }
        }
        // Pass 3: Remote songs (only fill unoccupied slots)
        for (song in library) {
            if (song.isRemote) {
                for (key in candidateMatchKeys(song)) {
                    map.putIfAbsent(key, song)
                }
            }
        }
        return map
    }

    fun <T> buildIndex(
        items: List<T>,
        artistOf: (T) -> String,
        titleOf: (T) -> String,
    ): Map<String, T> {
        val map = HashMap<String, T>(items.size * 2)
        for (item in items) {
            val key = matchKey(artistOf(item), titleOf(item))
            if (key.isNotEmpty()) {
                map.putIfAbsent(key, item)
            }
        }
        for (item in items) {
            for (key in candidateMatchKeys(artistOf(item), titleOf(item))) {
                map.putIfAbsent(key, item)
            }
        }
        return map
    }

    /** Resolve a library song from a pre-built [buildLibraryIndex] map, prioritizing local tracks. */
    fun lookupLocalSong(
        index: Map<String, Song>,
        meta: TrackMeta,
    ): Song? {
        var remoteFallback: Song? = null
        for (candidate in candidateMatchKeys(meta)) {
            val found = index[candidate] ?: continue
            if (!found.isRemote) return found
            if (remoteFallback == null) remoteFallback = found
        }
        return remoteFallback
    }

    /**
     * L2: build a library index once and map each item (via [metaOf]) to a result.
     * When [skipBlank] is true, rows with blank title or artist are omitted.
     * Level 1 ([buildLibraryIndex], [lookupLocalSong]) stays public for radio/dedupe paths.
     */
    fun <T, R> matchAgainstLibrary(
        items: List<T>,
        library: List<Song>,
        metaOf: (T) -> TrackMeta,
        skipBlank: Boolean = false,
        transform: (T, Song?) -> R?,
    ): List<R> {
        val index = buildLibraryIndex(library)
        val out = ArrayList<R>(items.size)
        for (item in items) {
            val meta = metaOf(item)
            if (skipBlank && (meta.title.isBlank() || meta.artist.isBlank())) continue
            val result = transform(item, lookupLocalSong(index, meta)) ?: continue
            out.add(result)
        }
        return out
    }

    /** L2 convenience when items themselves are [TrackMeta]. */
    fun <T : TrackMeta, R> matchMetasAgainstLibrary(
        items: List<T>,
        library: List<Song>,
        skipBlank: Boolean = false,
        transform: (T, Song?) -> R?,
    ): List<R> =
        matchAgainstLibrary(
            items = items,
            library = library,
            metaOf = { it },
            skipBlank = skipBlank,
            transform = transform,
        )

    /** Level 2: Determines which catalog candidates are truly missing from an album's local songs. */
    fun <T : TrackMeta> filterMissingAlbumCandidates(
        candidates: List<T>,
        localSongs: List<Song>,
    ): List<T> =
        com.bestiapop.android.domain.util
            .filterMissingAlbumCandidates(candidates, localSongs)
}

fun TrackMeta.matchKey(): String = TrackMatchKeys.matchKey(artist, title)

/** Level 1: Deduplicate any collection using an explicit track/album matching key. */
inline fun <T> List<T>.distinctByTrackKey(
    limit: Int = size,
    crossinline keyOf: (T) -> String,
): List<T> = distinctBy { keyOf(it).ifEmpty { it.hashCode().toString() } }.take(limit)

/** Level 2: Deduplicate tracks that implement [TrackMeta] by artist + title match key. */
fun <T : TrackMeta> List<T>.distinctCatalogTracks(limit: Int = size): List<T> = distinctByTrackKey(limit) { it.matchKey() }

/** Level 2: Deduplicate albums by artist + title match key. */
inline fun <T> List<T>.distinctCatalogAlbums(
    limit: Int = size,
    crossinline artistOf: (T) -> String,
    crossinline titleOf: (T) -> String,
): List<T> = distinctByTrackKey(limit) { TrackMatchKeys.matchKey(artistOf(it), titleOf(it)) }

/** Level 2: Match key for [CatalogAlbum] using normalized artist + title. */
fun CatalogAlbum.matchKey(): String = TrackMatchKeys.matchKey(artist, title)

/** Level 2: Deduplicate catalog albums using their artist + title match key. */
@JvmName("distinctCatalogAlbumsModel")
fun List<CatalogAlbum>.distinctCatalogAlbums(limit: Int = size): List<CatalogAlbum> =
    distinctCatalogAlbums(limit, artistOf = { it.artist }, titleOf = { it.title })

/** Level 2: Filter out catalog tracks whose exact match exists in [localSongs]. */
fun List<OnlineCatalogTrack>.filterNotMatchingSongs(localSongs: Collection<Song>): List<OnlineCatalogTrack> {
    if (isEmpty() || localSongs.isEmpty()) return this
    val localIndex = TrackMatchKeys.buildLibraryIndex(localSongs.toList())
    return filter { track ->
        TrackMatchKeys.lookupLocalSong(localIndex, track) == null
    }
}

/** Level 2: Filter out catalog albums whose exact match exists in [localAlbums]. */
fun List<CatalogAlbum>.filterNotMatchingAlbums(localAlbums: Collection<Album>): List<CatalogAlbum> {
    if (isEmpty() || localAlbums.isEmpty()) return this
    val keys = HashSet<String>(localAlbums.size * 4)
    for (alb in localAlbums) {
        keys.addAll(TrackMatchKeys.candidateMatchKeys(alb.artist, alb.name))
        if (alb.displayName.isNotBlank() && alb.displayName != alb.name) {
            keys.addAll(TrackMatchKeys.candidateMatchKeys(alb.artist, alb.displayName))
        }
    }
    return filter { catAlb ->
        val candidates = TrackMatchKeys.candidateMatchKeys(catAlb.artist, catAlb.title)
        candidates.none { it in keys }
    }
}

/** Level 2: Determines which catalog candidates are truly missing from an album's local songs. */
fun <T : TrackMeta> filterMissingAlbumCandidates(
    candidates: List<T>,
    localSongs: List<Song>,
): List<T> {
    if (candidates.isEmpty()) return emptyList()
    if (localSongs.isEmpty()) return candidates

    val localKeys = HashSet<String>(localSongs.size * 8)
    val localNormTitles = HashSet<String>(localSongs.size * 2)
    val localCompactTitles = HashSet<String>(localSongs.size * 2)
    val localTransTitles = HashSet<String>(localSongs.size * 2)
    val localByTrackNum = HashMap<Int, MutableList<Song>>()

    for (local in localSongs) {
        val trackNum = albumTrackDisplayNumber(local.trackNumber)
        if (trackNum > 0) {
            localByTrackNum.getOrPut(trackNum) { ArrayList() }.add(local)
        }
        val norm = TrackMatchKeys.normalize(local.title)
        if (norm.isNotEmpty()) {
            localNormTitles.add(norm)
            localCompactTitles.add(norm.replace(" ", "").replace("_", ""))
        }
        val trans = TrackMatchKeys.normalize(NaturalTextOrder.transliterateToLatin(local.title))
        if (trans.isNotEmpty()) {
            localTransTitles.add(trans)
            localCompactTitles.add(trans.replace(" ", "").replace("_", ""))
        }
        localKeys.addAll(TrackMatchKeys.candidateMatchKeys(local))
    }

    return candidates.filter { candidate ->
        val candTrackNum = albumTrackDisplayNumber(candidate.trackNumber)
        // 1. Same track position on the album with compatible duration
        // This is the primary anchor for multilingual releases (e.g. Japanese original
        // titles vs English translated catalog titles on albums like LSC or Jyocho).
        if (candTrackNum > 0) {
            val matchingLocalTracks = localByTrackNum[candTrackNum]
            if (matchingLocalTracks != null &&
                matchingLocalTracks.any {
                    durationCloseForKnownAlbum(it.durationMs, candidate.durationMs)
                }
            ) {
                return@filter false
            }
        }

        // 2. Direct normalized title
        val candNorm = TrackMatchKeys.normalize(candidate.title)
        if (candNorm.isNotEmpty() && candNorm in localNormTitles) {
            return@filter false
        }

        // 3. Compact title (without spaces, underscores, hyphens)
        val candCompact = candNorm.replace(" ", "").replace("_", "")
        if (candCompact.isNotEmpty() && candCompact in localCompactTitles) {
            return@filter false
        }

        // 4. Candidate match keys intersection (phonetics, loanwords -i/-y, bilingual, etc.)
        val cKeys = TrackMatchKeys.candidateMatchKeys(candidate)
        if (cKeys.any { it in localKeys }) {
            return@filter false
        }

        // 5. Transliterated Latin title match
        val candTrans = TrackMatchKeys.normalize(NaturalTextOrder.transliterateToLatin(candidate.title))
        if (candTrans.isNotEmpty()) {
            if (candTrans in localTransTitles) return@filter false
            val candTransCompact = candTrans.replace(" ", "").replace("_", "")
            if (candTransCompact in localCompactTitles) return@filter false
        }

        // 6. High similarity title when duration is compatible
        val matchesFuzzy =
            localSongs.any { local ->
                durationCloseForKnownAlbum(local.durationMs, candidate.durationMs) &&
                    IdentifyRanking.titleFieldSimilarity(candidate.title, local.title) >= 0.70f
            }
        !matchesFuzzy
    }
}
