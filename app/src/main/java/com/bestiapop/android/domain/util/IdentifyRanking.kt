package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.withIdentity
import kotlin.math.abs
import kotlin.math.max

/**
 * Multi-signal scoring for identify candidates (title/artist/duration/album/provider/filename).
 * Pure: no I/O. Used by [com.bestiapop.android.data.repository.MusicRepository.proposeSongIdentity].
 */
object IdentifyRanking {

    const val HIGH_SCORE = 0.85f
    const val HIGH_GAP = 0.12f
    const val MEDIUM_SCORE = 0.55f
    const val TOP_N = 5
    /** Page size for “mostrar más” in identify review. */
    const val PAGE_SIZE = 5
    /** Catalog fetch page (Deezer/iTunes limit). */
    const val CATALOG_PAGE = 25
    /** Drop trailing candidates whose score is this far below the top. */
    const val TAIL_RELATIVE_CUTOFF = 0.4f
    const val CONTAINMENT_BOOST = 0.60f
    private const val VERSION_MISMATCH_PENALTY = 0.20f
    private const val SOURCE_AGREE_SIM = 0.85f
    private const val SOURCE_CONFLICT_SIM = 0.55f
    private const val SOURCE_ALBUM_AGREE_BOOST = 0.08f
    private const val YEAR_EXACT_BOOST = 0.06f
    private const val YEAR_NEAR_BOOST = 0.03f

    data class Query(
        val artist: String,
        val title: String,
        val durationMs: Long = 0L,
        val filenameArtist: String? = null,
        val filenameTitle: String? = null,
        val artistIsPlaceholder: Boolean = false,
        /** Predominant tags (WiFi ID3 / library). Used to boost agreement and block HIGH on severe conflict. */
        val sourceArtist: String? = null,
        val sourceTitle: String? = null,
        val sourceAlbum: String? = null,
        /** Optional refine year from identify filters / song tag. */
        val preferYear: Int = 0
    )

    fun stripTitleNoise(raw: String): String {
        var t = raw
        t = FEAT_PAREN.replace(t, " ")
        t = SCORE_NOISE_PAREN.replace(t, " ")
        t = SCORE_NOISE_SUFFIX.replace(t, " ")
        t = PLUS_LYRICS.replace(t, " ")
        t = TRAILING_LYRICS.replace(t, " ")
        return TrackMatchKeys.normalize(t)
    }

    /** Display/apply title: drop cosmetic mix/lyrics noise, keep live/remix/acoustic. */
    fun cleanIdentityTitle(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        t = COSMETIC_PAREN.replace(t, " ")
        t = COSMETIC_SUFFIX.replace(t, " ")
        t = PLUS_LYRICS.replace(t, " ")
        t = TRAILING_LYRICS.replace(t, " ")
        return t.replace(WHITESPACE, " ").trim()
    }

    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size.toFloat()
        val union = ta.union(tb).size.toFloat()
        val j = inter / union
        val extra = (ta - tb) + (tb - ta)
        if ((a.contains(b) || b.contains(a)) && extra.size <= 1) {
            return max(j, CONTAINMENT_BOOST).coerceIn(0f, 1f)
        }
        return j.coerceIn(0f, 1f)
    }

    /** Generic/blank album → `"$artist - Single"`; otherwise keep [current]. */
    fun fallbackAlbum(artist: String, current: String = ""): String {
        val trimmed = current.trim()
        if (trimmed.isNotEmpty() && !isGenericAlbum(trimmed)) return trimmed
        return "${artist.ifBlank { "Unknown" }} - Single"
    }

    fun isGenericAlbum(album: String): Boolean {
        val a = album.trim()
        if (a.isEmpty()) return true
        return a.equals("Unknown Album", ignoreCase = true) ||
            a.equals("YouTube Music", ignoreCase = true) ||
            a.equals("YouTube", ignoreCase = true) ||
            a.equals("Single", ignoreCase = true) ||
            a.equals("Álbum", ignoreCase = true) ||
            a.equals("Album", ignoreCase = true)
    }

    fun isPreferredProvider(provider: String): Boolean {
        val p = provider.lowercase()
        return p.contains("deezer") ||
            p.contains("itunes") ||
            p.contains("apple") ||
            p.contains("listenbrainz") ||
            p == "catalog"
    }

    fun isYouTubeProvider(provider: String): Boolean =
        provider.contains("youtube", ignoreCase = true)

    fun strongVersionMarkers(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return STRONG_MARKER_REGEX.findAll(raw.lowercase())
            .mapNotNull { canonicalizeStrongMarker(it.groupValues[1]) }
            .toSet()
    }

    /**
     * Score one catalog hit against the library query. Returns score in 0..1 and UI reasons.
     */
    fun score(query: Query, track: OnlineCatalogTrack): Pair<Float, List<String>> {
        val reasons = ArrayList<String>(4)
        var total = 0f

        val qTitle = stripTitleNoise(query.title)
        val cTitle = stripTitleNoise(track.title)
        val titleSim = similarity(qTitle, cTitle)
        total += 0.40f * titleSim
        when {
            titleSim >= 0.98f -> reasons.add("título exacto")
            titleSim >= 0.70f -> reasons.add("título similar")
        }

        val cArtist = TrackMatchKeys.normalize(track.artist)
        if (!query.artistIsPlaceholder) {
            val qArtist = TrackMatchKeys.normalize(query.artist)
            val artistSim = similarity(qArtist, cArtist)
            total += 0.30f * artistSim
            when {
                artistSim >= 0.98f -> reasons.add("artista exacto")
                artistSim >= 0.70f -> reasons.add("artista similar")
            }
        } else {
            val hintArtist = query.filenameArtist?.let { TrackMatchKeys.normalize(it) }.orEmpty()
            if (hintArtist.isNotEmpty()) {
                val hintSim = similarity(hintArtist, cArtist)
                total += 0.30f * hintSim
                if (hintSim >= 0.85f) reasons.add("archivo")
            } else {
                // Unknown artist: do not punish the top hit hard.
                total += 0.15f
            }
        }

        val fileDur = query.durationMs
        val candDur = track.durationMs
        if (fileDur > 0L && candDur > 0L) {
            val diffSec = abs(fileDur - candDur) / 1000f
            when {
                diffSec <= 2f -> {
                    total += 0.20f
                    val rounded = diffSec.toInt()
                    reasons.add(if (rounded <= 0) "duración exacta" else "duración ±${rounded}s")
                }
                diffSec <= 5f -> total += 0.10f
                else -> total += 0.02f
            }
        } else {
            total += 0.08f
        }

        val album = track.album
        when {
            album.isNotBlank() && !isGenericAlbum(album) -> total += 0.05f
            isGenericAlbum(album) -> total -= 0.03f
        }

        val srcAlbum = query.sourceAlbum?.trim().orEmpty()
        if (srcAlbum.isNotEmpty() && !isGenericAlbum(srcAlbum) &&
            album.isNotBlank() && !isGenericAlbum(album)
        ) {
            val albumSim = sourceAlbumSimilarity(srcAlbum, album)
            when {
                albumSim >= SOURCE_AGREE_SIM -> {
                    total += SOURCE_ALBUM_AGREE_BOOST
                    reasons.add("álbum coincidente")
                }
                albumSim < SOURCE_CONFLICT_SIM -> reasons.add("álbum distinto")
            }
        }

        val srcArtist = query.sourceArtist?.trim().orEmpty()
        if (srcArtist.isNotEmpty() && !isPlaceholderArtist(srcArtist)) {
            val srcArtistSim = similarity(TrackMatchKeys.normalize(srcArtist), cArtist)
            if (srcArtistSim < SOURCE_CONFLICT_SIM) {
                reasons.add("artista distinto")
            }
        }

        val srcTitle = query.sourceTitle?.let { stripTitleNoise(it) }.orEmpty()
        if (srcTitle.isNotEmpty() && titleSim < SOURCE_CONFLICT_SIM) {
            reasons.add("título distinto")
        }

        when {
            isPreferredProvider(track.provider) -> total += 0.03f
            isYouTubeProvider(track.provider) -> total -= 0.02f
        }

        val hintTitle = query.filenameTitle?.let { stripTitleNoise(it) }.orEmpty()
        if (hintTitle.isNotEmpty() && similarity(hintTitle, cTitle) >= 0.85f) {
            total += 0.05f
            if ("archivo" !in reasons) reasons.add("archivo")
        }

        val preferYear = query.preferYear
        if (preferYear in 1000..9999 && track.year in 1000..9999) {
            val delta = abs(track.year - preferYear)
            when {
                delta == 0 -> {
                    total += YEAR_EXACT_BOOST
                    reasons.add("año coincidente")
                }
                delta <= 1 -> total += YEAR_NEAR_BOOST
            }
        }

        val queryMarkers = strongVersionMarkers(query.title) +
            strongVersionMarkers(query.filenameTitle.orEmpty())
        val extraMarkers = strongVersionMarkers(track.title) - queryMarkers
        if (extraMarkers.isNotEmpty()) {
            total -= VERSION_MISMATCH_PENALTY
            val label = extraMarkers.first()
            reasons.add("versión distinta ($label)")
        }

        return total.coerceIn(0f, 1f) to reasons
    }

    fun toCandidate(track: OnlineCatalogTrack, score: Float, reasons: List<String>): IdentifyCandidate {
        val cleaned = cleanIdentityTitle(track.title).ifBlank { track.title }
        return IdentifyCandidate(
            track = if (cleaned == track.title) {
                track
            } else {
                track.withIdentity { copy(title = cleaned) }
            },
            score = score,
            reasons = reasons
        )
    }

    /**
     * Dedupe by normalized (artist, title, album), keep best score, sort desc, trim to [limit]
     * and drop weak tail relative to #1. Prefer catalog providers over YouTube when both exist.
     * Pass [limit] = [Int.MAX_VALUE] (or a large page) when expanding “mostrar más”.
     */
    fun rank(
        query: Query,
        tracks: List<OnlineCatalogTrack>,
        limit: Int = TOP_N
    ): List<IdentifyCandidate> {
        if (tracks.isEmpty() || limit <= 0) return emptyList()

        val preferred = tracks.filter { !isYouTubeProvider(it.provider) }
        val pool = preferred.ifEmpty { tracks }

        val bestByKey = LinkedHashMap<String, IdentifyCandidate>()
        for (track in pool) {
            if (track.title.isBlank()) continue
            if (TrackMatchKeys.normalize(track.artist).isEmpty()) continue
            val (s, reasons) = score(query, track)
            val key = dedupeKey(track.artist, track.title, track.album)
            val candidate = toCandidate(track, s, reasons)
            val existing = bestByKey[key]
            if (existing == null || candidate.score > existing.score) {
                bestByKey[key] = candidate
            }
        }

        val ranked = bestByKey.values.sortedWith(
            compareByDescending<IdentifyCandidate> { it.score }
                .thenBy { strongVersionMarkers(it.title).size }
        )
        if (ranked.isEmpty()) return emptyList()

        val topScore = ranked.first().score
        val capped = if (limit == Int.MAX_VALUE) ranked else ranked.take(limit)
        val trimmed = capped.filterIndexed { index, c ->
            index < 3 || c.score >= topScore - TAIL_RELATIVE_CUTOFF
        }
        return trimmed
    }

    /**
     * Keep [existing] order (already shown), append new ranked hits not already present.
     * Avoids reshuffling the visible list when loading more pages.
     */
    fun appendCandidates(
        existing: List<IdentifyCandidate>,
        newcomers: List<IdentifyCandidate>
    ): List<IdentifyCandidate> {
        if (newcomers.isEmpty()) return existing
        if (existing.isEmpty()) return newcomers
        val seen = existing.map { dedupeKey(it.artist, it.title, it.album) }.toHashSet()
        val out = existing.toMutableList()
        for (c in newcomers.sortedByDescending { it.score }) {
            val key = dedupeKey(c.artist, c.title, c.album)
            if (key in seen) continue
            seen.add(key)
            out.add(c)
        }
        return out
    }

    fun confidence(ranked: List<IdentifyCandidate>): IdentifyConfidence {
        if (ranked.isEmpty()) return IdentifyConfidence.NONE
        val top = ranked.first()
        val gap = if (ranked.size >= 2) top.score - ranked[1].score else 1f
        val base = when {
            top.score >= HIGH_SCORE && gap >= HIGH_GAP -> IdentifyConfidence.HIGH
            top.score >= MEDIUM_SCORE -> IdentifyConfidence.MEDIUM
            else -> IdentifyConfidence.LOW
        }
        if (base != IdentifyConfidence.HIGH) return base
        if (isYouTubeProvider(top.provider)) return IdentifyConfidence.MEDIUM
        if (hasSevereConflict(top.reasons)) return IdentifyConfidence.MEDIUM
        return IdentifyConfidence.HIGH
    }

    fun hasSevereConflict(reasons: List<String>): Boolean =
        reasons.any { reason ->
            reason.startsWith("versión distinta") ||
                reason.startsWith("álbum distinto") ||
                reason.startsWith("artista distinto") ||
                reason.startsWith("título distinto")
        }

    private fun sourceAlbumSimilarity(source: String, candidate: String): Float {
        if (albumNamesMatch(source, candidate)) return 1f
        return similarity(TrackMatchKeys.normalize(source), TrackMatchKeys.normalize(candidate))
    }

    fun isPlaceholderArtist(artist: String): Boolean {
        val a = artist.trim()
        return a.isEmpty() ||
            a.equals("Unknown Artist", ignoreCase = true) ||
            a == "YouTube Artist" ||
            a == "Enlace Web" ||
            isTrackNumberLabel(a)
    }

    fun dedupeKey(artist: String, title: String, album: String): String =
        "${TrackMatchKeys.normalize(artist)}|${stripTitleNoise(title)}|${TrackMatchKeys.normalize(album)}"

    private fun canonicalizeStrongMarker(token: String): String? {
        val t = token.lowercase()
        return when (t) {
            "live", "concert", "performance", "session" -> "live"
            "letra", "letras", "lyric", "lyrics" -> "lyrics"
            "remix", "bootleg", "cover", "karaoke", "acoustic" -> t
            else -> null
        }
    }

    private val FEAT_PAREN = Regex(
        """\s*[\(\[][^)\]]*?\bfeat\.?\b[^)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val SCORE_NOISE_BODY =
        """official\s+(audio|video|music\s+video)|lyric\s+video|lyrics?|letras?|""" +
            """remaster(?:ed)?(?:\s+\d{4})?|live|concert|performance|session|""" +
            """original\s+mix|radio\s+edit|explicit|visuali[sz]er|remix|bootleg|cover|karaoke|acoustic"""
    private val SCORE_NOISE_PAREN = Regex(
        """\s*[\(\[]\s*($SCORE_NOISE_BODY)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val SCORE_NOISE_SUFFIX = Regex(
        """\s*[-–—]\s*($SCORE_NOISE_BODY)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val COSMETIC_BODY =
        """official\s+(audio|video|music\s+video)|lyric\s+video|lyrics?|letras?|""" +
            """remaster(?:ed)?(?:\s+\d{4})?|original\s+mix|radio\s+edit|explicit|visuali[sz]er"""
    private val COSMETIC_PAREN = Regex(
        """\s*[\(\[]\s*($COSMETIC_BODY)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val COSMETIC_SUFFIX = Regex(
        """\s*[-–—]\s*($COSMETIC_BODY)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PLUS_LYRICS = Regex(
        """\s*[+|]\s*(?:letras?|lyrics?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_LYRICS = Regex(
        """\s+(?:letras?|lyrics?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val WHITESPACE = Regex("""\s+""")
    private val STRONG_MARKER_REGEX = Regex(
        """\b(live|concert|performance|session|letra|letras|lyrics?|remix|bootleg|cover|karaoke|acoustic)\b""",
        RegexOption.IGNORE_CASE
    )
}
