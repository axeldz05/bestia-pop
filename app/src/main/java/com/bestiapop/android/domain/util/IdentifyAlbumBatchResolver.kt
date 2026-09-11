package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.Song

// --- Data Structures ---

/**
 * Partición estructurada de canciones entre aquellas que solo necesitan portada
 * y aquellas que presentan otros gaps de metadatos pendientes.
 */
data class AlbumBatchSongPartition(
    val artworkOnly: List<Song>,
    val otherGaps: List<Song>
) {
    val artworkOnlyIds: List<Long> get() = artworkOnly.map { it.id }
    val otherGapsIds: List<Long> get() = otherGaps.map { it.id }
}

/**
 * Resultado estructurado del macheo y aplicación de tracks de un álbum conocido a canciones pendientes.
 */
data class AlbumTrackMatchResult(
    val appliedSongIds: Set<Long> = emptySet(),
    val reviewProposals: List<IdentifyProposal> = emptyList()
) {
    val completedSongIds: Set<Long> get() = appliedSongIds + reviewProposals.map { it.songId }
}

/**
 * Encapsula un grupo de canciones del mismo álbum dentro de un lote de identificación,
 * separadas entre aquellas que únicamente necesitan portada y aquellas con otros gaps pendientes.
 */
data class IdentifyAlbumBatchCandidate(
    val groupKey: String,
    val artist: String,
    val album: String,
    val songs: List<Song>,
    val partition: AlbumBatchSongPartition
) {
    constructor(
        groupKey: String,
        artist: String,
        album: String,
        songs: List<Song>,
        artworkOnlySongs: List<Song>,
        otherGapsSongs: List<Song>
    ) : this(
        groupKey = groupKey,
        artist = artist,
        album = album,
        songs = songs,
        partition = AlbumBatchSongPartition(artworkOnlySongs, otherGapsSongs)
    )

    val songIds: List<Long> get() = songs.map { it.id }
    val artworkOnlySongs: List<Song> get() = partition.artworkOnly
    val otherGapsSongs: List<Song> get() = partition.otherGaps
    val artworkOnlyIds: List<Long> get() = partition.artworkOnlyIds
    val otherGapsIds: List<Long> get() = partition.otherGapsIds
}

// --- L1 Primitives ---

/**
 * Valida si un par artista/álbum califica para identificación a nivel de álbum
 * (descarta nombres genéricos o placeholders).
 */
fun isEligibleForAlbumBatchIdentify(artist: String, album: String): Boolean {
    val trimmedArtist = artist.trim()
    val trimmedAlbum = album.trim()
    if (trimmedArtist.isEmpty() || IdentifyRanking.isPlaceholderArtist(trimmedArtist)) return false
    if (trimmedAlbum.isEmpty() || IdentifyRanking.isGenericAlbum(trimmedAlbum)) return false
    return true
}

/**
 * Particiona una lista de canciones de un álbum en:
 * 1. Canciones que solo necesitan portada (`artworkOnly`).
 * 2. Canciones que tienen otros gaps de metadatos (`otherGaps`).
 */
fun partitionAlbumBatchSongs(
    songs: List<Song>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet()
): AlbumBatchSongPartition {
    val artworkOnly = ArrayList<Song>()
    val otherGaps = ArrayList<Song>()
    for (song in songs) {
        val effectiveFields = if (song.id in fillGapsOnlyIds) {
            gapApplyFields(song)
        } else {
            batchFields
        }
        if (songHasOtherGapsThanArtwork(song, effectiveFields)) {
            otherGaps.add(song)
        } else {
            artworkOnly.add(song)
        }
    }
    return AlbumBatchSongPartition(artworkOnly = artworkOnly, otherGaps = otherGaps)
}

/**
 * Construye una propuesta de identificación de alta confianza para una canción
 * a partir del match con un track de álbum conocido.
 */
fun buildKnownAlbumProposal(
    song: Song,
    match: KnownAlbumMatch
): IdentifyProposal {
    val candidate = match.toIdentifyCandidate()
    return IdentifyProposal(
        songId = song.id,
        queryArtist = song.artist,
        queryTitle = song.title,
        candidates = listOf(candidate),
        confidence = IdentifyConfidence.HIGH,
        suggested = candidate
    )
}

// --- L2 Composite Operations ---

/**
 * Aplica los matches conocidos a las canciones correspondientes usando la función [apply] provista,
 * agrupando resultados aplicados y propuestas enviadas a revisión.
 */
suspend fun applyKnownAlbumMatches(
    songs: List<Song>,
    matches: Map<Long, KnownAlbumMatch>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    collectReviewProposals: Boolean = true,
    apply: suspend (Long, IdentifyProposal, IdentifyApplyFields) -> IdentifyResult
): AlbumTrackMatchResult {
    if (songs.isEmpty() || matches.isEmpty()) return AlbumTrackMatchResult()
    val applied = LinkedHashSet<Long>()
    val reviews = ArrayList<IdentifyProposal>()

    for (song in songs) {
        val match = matches[song.id] ?: continue
        val proposal = buildKnownAlbumProposal(song, match)
        val fields = if (song.id in fillGapsOnlyIds) gapApplyFields(song) else batchFields
        when (apply(song.id, proposal, fields)) {
            is IdentifyResult.Updated -> applied.add(song.id)
            else -> if (collectReviewProposals) reviews.add(proposal)
        }
    }
    return AlbumTrackMatchResult(appliedSongIds = applied, reviewProposals = reviews)
}

/**
 * Machea y aplica en bloque los tracks de un álbum conocido a una lista de canciones.
 */
suspend fun matchAndApplyKnownAlbumTracks(
    songs: List<Song>,
    knownAlbum: KnownAlbumTracks,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    seedFolderPath: String = "",
    collectReviewProposals: Boolean = true,
    apply: suspend (Long, IdentifyProposal, IdentifyApplyFields) -> IdentifyResult
): AlbumTrackMatchResult {
    if (songs.isEmpty() || knownAlbum.tracks.size < 2) return AlbumTrackMatchResult()
    val queries = songs.map { knownAlbumQueryOf(it) }
    val matches = assignUniqueKnownAlbumMatches(
        queries = queries,
        albums = listOf(knownAlbum),
        scoped = true,
        seedFolderPath = seedFolderPath
    )
    if (matches.isEmpty()) return AlbumTrackMatchResult()
    return applyKnownAlbumMatches(
        songs = songs,
        matches = matches,
        batchFields = batchFields,
        fillGapsOnlyIds = fillGapsOnlyIds,
        collectReviewProposals = collectReviewProposals,
        apply = apply
    )
}

/**
 * Encuentra todos los grupos de álbum elegibles para consulta a nivel de álbum en un lote de canciones disponibles.
 */
fun findAlbumBatchCandidates(
    availableSongs: List<Song>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    attemptedGroupKeys: Set<String> = emptySet(),
    minGroupSize: Int = 2
): List<IdentifyAlbumBatchCandidate> {
    if (!batchFields.artwork && fillGapsOnlyIds.isEmpty()) return emptyList()
    if (availableSongs.size < minGroupSize) return emptyList()

    val grouped = LinkedHashMap<String, MutableList<Song>>()
    for (song in availableSongs) {
        if (!isEligibleForAlbumBatchIdentify(song.artist, song.album)) continue
        val key = albumGroupKey(song.artist, song.album)
        if (key in attemptedGroupKeys) continue
        grouped.getOrPut(key) { ArrayList() }.add(song)
    }

    return grouped.mapNotNull { (key, groupSongs) ->
        if (groupSongs.size < minGroupSize) return@mapNotNull null
        val firstSong = groupSongs.first()
        val partition = partitionAlbumBatchSongs(
            songs = groupSongs,
            batchFields = batchFields,
            fillGapsOnlyIds = fillGapsOnlyIds
        )
        IdentifyAlbumBatchCandidate(
            groupKey = key,
            artist = firstSong.artist,
            album = firstSong.album,
            songs = groupSongs,
            partition = partition
        )
    }
}

/**
 * Variante de [findAlbumBatchCandidates] que filtra canciones por `remainingIds` e `inFlightIds`.
 */
fun findAlbumBatchCandidates(
    songs: List<Song>,
    remainingIds: Set<Long>,
    inFlightIds: Set<Long>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    attemptedGroupKeys: Set<String> = emptySet(),
    minGroupSize: Int = 2
): List<IdentifyAlbumBatchCandidate> {
    val available = songs.filter { it.id in remainingIds && it.id !in inFlightIds }
    return findAlbumBatchCandidates(
        availableSongs = available,
        batchFields = batchFields,
        fillGapsOnlyIds = fillGapsOnlyIds,
        attemptedGroupKeys = attemptedGroupKeys,
        minGroupSize = minGroupSize
    )
}

/**
 * Encuentra el siguiente candidato de álbum para procesamiento en lote.
 */
fun findNextAlbumBatchCandidate(
    availableSongs: List<Song>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    attemptedGroupKeys: Set<String> = emptySet(),
    minGroupSize: Int = 2
): IdentifyAlbumBatchCandidate? = findAlbumBatchCandidates(
    availableSongs = availableSongs,
    batchFields = batchFields,
    fillGapsOnlyIds = fillGapsOnlyIds,
    attemptedGroupKeys = attemptedGroupKeys,
    minGroupSize = minGroupSize
).firstOrNull()

// --- L3 High-Level Utility ---

/**
 * Determina si la canción objetivo pertenece a un grupo de álbum elegible para identificación en bloque
 * y retorna el candidato del grupo completo, o null si debe procesarse individualmente.
 */
fun findNextAlbumBatchCandidateForSong(
    targetSong: Song,
    songs: List<Song>,
    remainingIds: Set<Long>,
    inFlightIds: Set<Long>,
    batchFields: IdentifyApplyFields,
    fillGapsOnlyIds: Set<Long> = emptySet(),
    attemptedGroupKeys: Set<String> = emptySet(),
    minGroupSize: Int = 2
): IdentifyAlbumBatchCandidate? {
    if (!isEligibleForAlbumBatchIdentify(targetSong.artist, targetSong.album)) return null
    val targetKey = albumGroupKey(targetSong.artist, targetSong.album)
    if (targetKey in attemptedGroupKeys) return null

    val candidates = findAlbumBatchCandidates(
        songs = songs,
        remainingIds = remainingIds,
        inFlightIds = inFlightIds,
        batchFields = batchFields,
        fillGapsOnlyIds = fillGapsOnlyIds,
        attemptedGroupKeys = attemptedGroupKeys,
        minGroupSize = minGroupSize
    )
    return candidates.firstOrNull { it.groupKey == targetKey }
}
