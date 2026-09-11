package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.Song

/**
 * Album-level grouping of identified songs, exposing metadata that can be changed as an album
 * (artwork, release year, album title, artist) and candidates for selection.
 */
data class IdentifyAlbumGroup(
    val key: String,
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentArtworkUri: String? = null,
    val currentYear: Int = 0,
    val proposedArtist: String = "",
    val proposedAlbum: String = "",
    val proposedArtworkUri: String? = null,
    val proposedYear: Int = 0,
    val songIds: List<Long>,
    val artworkOnlySongIds: List<Long> = emptyList(),
    val otherGapsSongIds: List<Long> = emptyList(),
    val candidates: List<CatalogAlbum> = emptyList(),
    val selectedCandidateIndex: Int = 0
) {
    /** Backwards-compatible constructor for legacy usages. */
    constructor(
        key: String,
        artist: String,
        album: String,
        artworkUri: String?,
        songIds: List<Long>
    ) : this(
        key = key,
        currentArtist = artist,
        currentAlbum = album,
        currentArtworkUri = artworkUri,
        currentYear = 0,
        proposedArtist = artist,
        proposedAlbum = album,
        proposedArtworkUri = artworkUri,
        proposedYear = 0,
        songIds = songIds,
        artworkOnlySongIds = emptyList(),
        otherGapsSongIds = emptyList(),
        candidates = emptyList(),
        selectedCandidateIndex = 0
    )

    val artist: String
        get() = candidates.getOrNull(selectedCandidateIndex)?.artist
            ?: proposedArtist.takeIf { it.isNotBlank() }
            ?: currentArtist

    val album: String
        get() = candidates.getOrNull(selectedCandidateIndex)?.title
            ?: proposedAlbum.takeIf { it.isNotBlank() }
            ?: currentAlbum

    val artworkUri: String?
        get() = candidates.getOrNull(selectedCandidateIndex)?.coverUrl
            ?: proposedArtworkUri
            ?: currentArtworkUri

    val year: Int
        get() = candidates.getOrNull(selectedCandidateIndex)?.releaseYear?.toIntOrNull()
            ?: proposedYear.takeIf { it > 0 }
            ?: currentYear

    val selectedCandidate: CatalogAlbum?
        get() = candidates.getOrNull(selectedCandidateIndex)
}

data class IdentifyAlbumGroupSource(
    val song: Song,
    val proposal: IdentifyProposal
)

/** Convert candidates to catalog albums omitting invalid or generic titles. */
fun IdentifyCandidate.toCatalogAlbum(): CatalogAlbum? {
    val alb = album.trim()
    if (alb.isEmpty() || IdentifyRanking.isGenericAlbum(alb)) return null
    return CatalogAlbum(
        id = track.id.ifBlank { "${artist}_$alb" },
        title = alb,
        artist = artist,
        coverUrl = artworkUri?.takeIf { it.isNotBlank() },
        trackCount = 0,
        releaseYear = year.takeIf { it > 0 }?.toString().orEmpty()
    )
}

private val CatalogAlbum.dedupKey: String
    get() = "${artist.lowercase()}_${title.lowercase()}_${coverUrl.orEmpty()}"

/**
 * Clusters songs into album groups (Level 1 primitive), categorizing songs that only need artwork vs songs with other gaps,
 * and extracting deduplicated album candidates.
 */
fun clusterIdentifyAlbumGroupsFromSources(
    sources: List<IdentifyAlbumGroupSource>,
    applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL,
    searchedCandidates: Map<String, List<CatalogAlbum>> = emptyMap(),
    selectedCandidateIndices: Map<String, Int> = emptyMap()
): List<IdentifyAlbumGroup> {
    if (sources.size < 2) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<IdentifyAlbumGroupSource>>()

    for (source in sources) {
        val suggested = source.proposal.suggested
        val suggestedAlbum = suggested?.album?.trim().orEmpty()
        val suggestedArtist = suggested?.artist?.trim().orEmpty()
        val hasSuggested = source.proposal.confidence == IdentifyConfidence.MEDIUM &&
            suggestedAlbum.isNotEmpty() && !IdentifyRanking.isGenericAlbum(suggestedAlbum)

        val localAlbum = source.song.album.trim()
        val localArtist = source.song.artist.trim()
        val hasLocal = localAlbum.isNotEmpty() && !IdentifyRanking.isGenericAlbum(localAlbum)

        val key = when {
            hasSuggested -> albumGroupKey(suggestedArtist.ifEmpty { localArtist }, suggestedAlbum)
            hasLocal -> albumGroupKey(localArtist, localAlbum)
            else -> null
        } ?: continue

        buckets.getOrPut(key) { ArrayList() }.add(source)
    }

    return buckets.mapNotNull { (key, groupSources) ->
        if (groupSources.size < 2) return@mapNotNull null
        val firstProposal = groupSources.firstNotNullOfOrNull { it.proposal.suggested }
        val firstSong = groupSources.first().song

        val proposedAlbum = firstProposal?.album?.takeUnless { IdentifyRanking.isGenericAlbum(it) }.orEmpty()
        val proposedArtist = firstProposal?.artist?.takeUnless { IdentifyRanking.isPlaceholderArtist(it) }.orEmpty()
        val proposedArtwork = groupSources.firstNotNullOfOrNull { it.proposal.suggested?.artworkUri }
        val proposedYear = groupSources.firstNotNullOfOrNull {
            it.proposal.suggested?.year?.takeIf { y -> y > 0 }
        } ?: 0

        val currentArtist = firstSong.artist
        val currentAlbum = firstSong.album
        val currentArtwork = groupSources.firstNotNullOfOrNull { it.song.artworkUri?.takeIf { uri -> uri.isNotBlank() } }
        val currentYear = groupSources.firstNotNullOfOrNull { it.song.year.takeIf { y -> y > 0 } } ?: 0

        val songIds = groupSources.map { it.song.id }
        val partition = partitionAlbumBatchSongs(
            songs = groupSources.map { it.song },
            batchFields = applyFields
        )
        val artworkOnlyIds = partition.artworkOnlyIds
        val otherGapsIds = partition.otherGapsIds

        // Extracted candidates from proposals of all songs in this album
        val extractedCandidates = groupSources.flatMap { it.proposal.candidates }
            .mapNotNull { it.toCatalogAlbum() }
            .distinctBy { it.dedupKey }

        val searched = searchedCandidates[key].orEmpty()
        val mergedCandidates = (searched + extractedCandidates).distinctBy { it.dedupKey }

        val selectedIndex = selectedCandidateIndices[key]?.coerceIn(0, (mergedCandidates.size - 1).coerceAtLeast(0)) ?: 0

        IdentifyAlbumGroup(
            key = key,
            currentArtist = currentArtist,
            currentAlbum = currentAlbum,
            currentArtworkUri = currentArtwork,
            currentYear = currentYear,
            proposedArtist = proposedArtist,
            proposedAlbum = proposedAlbum,
            proposedArtworkUri = proposedArtwork,
            proposedYear = proposedYear,
            songIds = songIds,
            artworkOnlySongIds = artworkOnlyIds,
            otherGapsSongIds = otherGapsIds,
            candidates = mergedCandidates,
            selectedCandidateIndex = selectedIndex
        )
    }
}

/** Level 3 helper: converts proposals into group sources with dummy song identity for legacy callers. */
fun List<IdentifyProposal>.toAlbumGroupSources(): List<IdentifyAlbumGroupSource> = map { proposal ->
    IdentifyAlbumGroupSource(
        song = Song(
            id = proposal.songId,
            uriString = "",
            title = proposal.queryTitle,
            artist = proposal.queryArtist,
            album = ""
        ),
        proposal = proposal
    )
}

/**
 * Level 3 convenience clustering for proposals without full Song instances.
 * Reuses the single-source-of-truth clustering logic from [clusterIdentifyAlbumGroupsFromSources].
 */
fun clusterIdentifyAlbumGroups(proposals: List<IdentifyProposal>): List<IdentifyAlbumGroup> {
    if (proposals.size < 2) return emptyList()
    return clusterIdentifyAlbumGroupsFromSources(proposals.toAlbumGroupSources())
}

fun albumGroupKey(artist: String, album: String): String = albumArtistKey(artist, album)

