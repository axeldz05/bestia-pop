package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal

/** MEDIUM proposals that share a non-generic suggested album (size ≥ 2). */
data class IdentifyAlbumGroup(
    val key: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val songIds: List<Long>
)

fun clusterIdentifyAlbumGroups(proposals: List<IdentifyProposal>): List<IdentifyAlbumGroup> {
    if (proposals.size < 2) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<IdentifyProposal>>()
    for (proposal in proposals) {
        if (proposal.confidence != IdentifyConfidence.MEDIUM) continue
        val suggested = proposal.suggested ?: continue
        val album = suggested.album.trim()
        if (album.isEmpty() || IdentifyRanking.isGenericAlbum(album)) continue
        val key = albumGroupKey(suggested.artist, album)
        buckets.getOrPut(key) { ArrayList() }.add(proposal)
    }
    return buckets.mapNotNull { (key, group) ->
        if (group.size < 2) return@mapNotNull null
        val suggested = group.first().suggested ?: return@mapNotNull null
        IdentifyAlbumGroup(
            key = key,
            artist = suggested.artist,
            album = suggested.album,
            artworkUri = group.firstNotNullOfOrNull { it.suggested?.artworkUri },
            songIds = group.map { it.songId }
        )
    }
}

fun albumGroupKey(artist: String, album: String): String =
    "${TrackMatchKeys.normalize(artist)}|${normalizeAlbumName(album).lowercase()}"
