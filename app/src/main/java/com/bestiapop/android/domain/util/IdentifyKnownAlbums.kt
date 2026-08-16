package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.albumTrackDisplayNumber
import com.bestiapop.android.data.util.looksLikeStoragePath
import kotlin.math.abs
import kotlin.math.max

const val KNOWN_ALBUM_REASON = "álbum conocido"
private val TRACK_PLACEHOLDER = Regex(
    """^(?:track|pista|audio|untitled|unknown|tema)\s*\d*$""",
    RegexOption.IGNORE_CASE
)

data class KnownAlbumTrack(
    val title: String,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val artworkUri: String? = null,
    val sourceSongId: Long? = null
) {
    val assignmentKey: String
        get() = "${IdentifyRanking.stripTitleNoise(title)}|${albumTrackDisplayNumber(trackNumber)}"
}

data class KnownAlbumTracks(
    val key: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val tracks: List<KnownAlbumTrack>
)

data class KnownAlbumQuery(
    val songId: Long,
    val title: String,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val folderPath: String = ""
)

data class KnownAlbumMatch(
    val album: KnownAlbumTracks,
    val track: KnownAlbumTrack,
    val score: Float
)

fun Song.toKnownAlbumTrack(): KnownAlbumTrack = KnownAlbumTrack(
    title = title,
    durationMs = durationMs,
    trackNumber = trackNumber,
    year = year,
    artworkUri = artworkUri,
    sourceSongId = id
)

fun OnlineCatalogTrack.toKnownAlbumTrack(): KnownAlbumTrack = KnownAlbumTrack(
    title = title,
    durationMs = durationMs,
    trackNumber = trackNumber,
    year = year,
    artworkUri = artworkUri
)

fun isUnusableKnownAlbumTitle(title: String): Boolean {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return true
    if (looksLikeStoragePath(trimmed)) return true
    if (isTrackNumberLabel(trimmed)) return true
    return TRACK_PLACEHOLDER.matches(trimmed)
}

fun knownAlbumQueryOf(
    song: Song,
    queryTitle: String = song.title,
    queryTrack: Int = song.trackNumber
): KnownAlbumQuery {
    val hints = mergeIdentityHints(
        parseFilenameMetadataHints(queryTitle),
        resolveWeakIdentityHints(song.artist, song.title)
    )
    val title = hints.title?.takeIf { !isUnusableKnownAlbumTitle(it) }
        ?: queryTitle.takeIf { !isUnusableKnownAlbumTitle(it) }
        ?: song.title
    val track = hints.trackNumber
        ?: albumTrackDisplayNumber(queryTrack).takeIf { it > 0 }
        ?: albumTrackDisplayNumber(song.trackNumber)
    return KnownAlbumQuery(
        songId = song.id,
        title = title,
        durationMs = song.durationMs,
        trackNumber = track,
        folderPath = song.folderPath
    )
}

fun knownAlbumsFromLibrary(
    songs: Iterable<Song>,
    excludeSongIds: Set<Long> = emptySet()
): List<KnownAlbumTracks> {
    val eligible = songs.filter { song ->
        song.id !in excludeSongIds && !IdentifyRanking.isGenericAlbum(song.album)
    }
    if (eligible.isEmpty()) return emptyList()
    return songsByAlbumBucket(eligible, IdentifyRanking::isGenericAlbum).mapNotNull { (_, group) ->
        if (group.isEmpty()) return@mapNotNull null
        val albumName = preferredAlbumDisplayName(group.map { it.album })
        if (albumName.isBlank() || IdentifyRanking.isGenericAlbum(albumName)) return@mapNotNull null
        val artist = pickPersistedArtistName(group.map { it.artist }, group.first().artist)
        KnownAlbumTracks(
            key = albumGroupKey(artist, albumName),
            artist = artist,
            album = albumName,
            artworkUri = group.firstNotNullOfOrNull { it.artworkUri?.takeIf { uri -> uri.isNotBlank() } },
            tracks = group.map { it.toKnownAlbumTrack() }
        )
    }
}

fun mergeKnownAlbumTracks(
    artist: String,
    album: String,
    library: KnownAlbumTracks?,
    catalog: List<KnownAlbumTrack>
): KnownAlbumTracks? {
    val displayArtist = library?.artist?.takeIf { it.isNotBlank() } ?: artist
    val displayAlbum = library?.album?.takeIf { it.isNotBlank() } ?: album
    if (displayAlbum.isBlank() || IdentifyRanking.isGenericAlbum(displayAlbum)) return null
    if (IdentifyRanking.isPlaceholderArtist(displayArtist) && library == null && catalog.isEmpty()) {
        return null
    }
    val merged = LinkedHashMap<String, KnownAlbumTrack>()
    library?.tracks.orEmpty().forEach { track ->
        merged[track.assignmentKey] = track
    }
    catalog.forEach { track ->
        merged.putIfAbsent(track.assignmentKey, track)
    }
    if (merged.isEmpty()) return null
    return KnownAlbumTracks(
        key = albumGroupKey(displayArtist, displayAlbum),
        artist = displayArtist,
        album = displayAlbum,
        artworkUri = library?.artworkUri
            ?: catalog.firstNotNullOfOrNull { it.artworkUri?.takeIf { uri -> uri.isNotBlank() } },
        tracks = merged.values.toList()
    )
}

fun durationCloseForKnownAlbum(fileMs: Long, trackMs: Long): Boolean {
    if (fileMs <= 0L || trackMs <= 0L) return true
    val diff = abs(fileMs - trackMs)
    val slack = max(3_000L, (max(fileMs, trackMs) * 0.08f).toLong())
    return diff <= slack
}

fun matchSongToKnownAlbum(
    query: KnownAlbumQuery,
    album: KnownAlbumTracks,
    seedFolderPath: String = ""
): KnownAlbumMatch? {
    if (isUnusableKnownAlbumTitle(query.title)) return null
    val qTitle = IdentifyRanking.stripTitleNoise(query.title)
    if (qTitle.isEmpty()) return null
    val scored = ArrayList<KnownAlbumMatch>(album.tracks.size)
    for (track in album.tracks) {
        if (track.sourceSongId != null && track.sourceSongId == query.songId) continue
        val tTitle = IdentifyRanking.stripTitleNoise(track.title)
        if (tTitle.isEmpty()) continue
        val titleSim = IdentifyRanking.fieldSimilarity(qTitle, tTitle)
        if (titleSim < IdentifyRanking.MEDIUM_SCORE) continue
        if (!durationCloseForKnownAlbum(query.durationMs, track.durationMs)) continue
        var score = titleSim
        val qTrack = albumTrackDisplayNumber(query.trackNumber)
        val tTrack = albumTrackDisplayNumber(track.trackNumber)
        if (qTrack > 0 && tTrack > 0 && qTrack == tTrack) score += 0.10f
        if (query.durationMs > 0L && track.durationMs > 0L) {
            val diff = abs(query.durationMs - track.durationMs)
            val slack = max(3_000L, (max(query.durationMs, track.durationMs) * 0.08f).toLong())
            score += 0.08f * (1f - diff.toFloat() / slack.toFloat()).coerceIn(0f, 1f)
        }
        if (seedFolderPath.isNotBlank() &&
            query.folderPath.isNotBlank() &&
            query.folderPath == seedFolderPath
        ) {
            score += 0.05f
        }
        scored += KnownAlbumMatch(album, track, score)
    }
    if (scored.isEmpty()) return null
    scored.sortByDescending { it.score }
    val best = scored.first()
    val second = scored.getOrNull(1)
    if (second != null && best.score - second.score < IdentifyRanking.HIGH_GAP) return null
    return best
}

/**
 * [scoped] = unique within the given albums only (typically one album after Usar/HIGH).
 * Global = unique across every known album; a title that hits two albums is skipped.
 */
fun assignUniqueKnownAlbumMatches(
    queries: List<KnownAlbumQuery>,
    albums: List<KnownAlbumTracks>,
    scoped: Boolean,
    seedFolderPath: String = ""
): Map<Long, KnownAlbumMatch> {
    if (queries.isEmpty() || albums.isEmpty()) return emptyMap()
    val perQuery = HashMap<Long, MutableList<KnownAlbumMatch>>()
    for (query in queries) {
        for (album in albums) {
            val match = matchSongToKnownAlbum(query, album, seedFolderPath) ?: continue
            perQuery.getOrPut(query.songId) { ArrayList() }.add(match)
        }
    }
    val unique = ArrayList<Pair<Long, KnownAlbumMatch>>()
    for ((songId, matches) in perQuery) {
        if (!scoped) {
            val albumKeys = matches.map { it.album.key }.distinct()
            if (albumKeys.size > 1) continue
        }
        val best = matches.maxByOrNull { it.score } ?: continue
        unique += songId to best
    }
    unique.sortByDescending { it.second.score }
    val assigned = LinkedHashMap<Long, KnownAlbumMatch>()
    val takenTracks = HashSet<String>()
    for ((songId, match) in unique) {
        val trackKey = "${match.album.key}|${match.track.assignmentKey}"
        if (trackKey in takenTracks) continue
        takenTracks += trackKey
        assigned[songId] = match
    }
    return assigned
}

fun KnownAlbumMatch.toIdentifyCandidate(): IdentifyCandidate {
    val identityTitle = IdentifyRanking.cleanIdentityTitle(track.title).ifBlank { track.title }
    return IdentifyCandidate(
        track = OnlineCatalogTrack(
            id = "known:${album.key}|${track.assignmentKey}",
            title = identityTitle,
            artist = album.artist,
            album = album.album,
            artworkUri = track.artworkUri ?: album.artworkUri,
            durationMs = track.durationMs,
            audioUrl = "",
            provider = "Catalog",
            trackNumber = track.trackNumber,
            year = track.year
        ),
        score = score.coerceIn(0f, 1f),
        reasons = listOf(KNOWN_ALBUM_REASON)
    )
}

fun IdentifyProposal.withKnownAlbumMatch(match: KnownAlbumMatch): IdentifyProposal {
    val candidate = match.toIdentifyCandidate()
    return copy(
        candidates = listOf(candidate) + candidates.filterNot { it.track.id == candidate.track.id },
        suggested = candidate,
        confidence = IdentifyConfidence.MEDIUM
    )
}

fun promoteKnownAlbumMatches(
    proposals: List<IdentifyProposal>,
    queries: List<KnownAlbumQuery>,
    albums: List<KnownAlbumTracks>
): List<IdentifyProposal> {
    if (proposals.isEmpty() || albums.isEmpty()) return proposals
    val byId = queries.associateBy { it.songId }
    val eligibleQueries = proposals.mapNotNull { proposal ->
        if (proposal.confidence == IdentifyConfidence.HIGH) return@mapNotNull null
        if (proposal.confidence == IdentifyConfidence.MEDIUM && proposal.suggested != null) {
            return@mapNotNull null
        }
        byId[proposal.songId]
    }
    val matches = assignUniqueKnownAlbumMatches(eligibleQueries, albums, scoped = false)
    if (matches.isEmpty()) return proposals
    return proposals.map { proposal ->
        val match = matches[proposal.songId] ?: return@map proposal
        proposal.withKnownAlbumMatch(match)
    }
}
