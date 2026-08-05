package com.bestiapop.android.data.migration

import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase

data class DedupGroup(
    val matchKey: String,
    val keeper: SongEntity,
    val losers: List<SongEntity>
)

data class DedupResult(
    val groupsProcessed: Int,
    val songsRemoved: Int,
    val filesDeleted: Int,
    val keepersNormalized: Int
)

/**
 * Pure selection / grouping helpers for library title+artist deduplication.
 */
object LibraryDedupLogic {

    fun matchKey(song: SongEntity): String =
        MatchListenBrainzTracksUseCase.matchKey(song.artist, song.title)

    fun groupDuplicates(songs: List<SongEntity>): List<DedupGroup> {
        return songs
            .groupBy { matchKey(it) }
            .filter { (key, list) -> key.isNotEmpty() && list.size > 1 }
            .map { (key, list) ->
                val keeper = selectKeeper(list)
                DedupGroup(
                    matchKey = key,
                    keeper = keeper,
                    losers = list.filter { it.id != keeper.id }
                )
            }
    }

    fun selectKeeper(songs: List<SongEntity>): SongEntity {
        require(songs.isNotEmpty())
        return songs.sortedWith(
            compareByDescending<SongEntity> {
                SongPathNormalizer.isAppOwnedUri(it.uriString, it.folderPath)
            }
                .thenByDescending { it.durationMs }
                .thenBy { it.id }
        ).first()
    }

    /** Absolute path for the keeper when the URI is file-based. */
    fun normalizedKeeperUri(keeper: SongEntity): String {
        val fromUri = SongPathNormalizer.toAbsolutePath(keeper.uriString)
        if (fromUri != null) return fromUri
        if (SongPathNormalizer.isAppOwnedUri(keeper.uriString, keeper.folderPath)) {
            SongPathNormalizer.resolveFilePath(keeper.uriString, keeper.folderPath)?.let { return it }
        }
        return keeper.uriString
    }

    fun loserFilePath(loser: SongEntity): String? =
        SongPathNormalizer.resolveFilePath(loser.uriString, loser.folderPath)

    fun keeperFilePath(keeper: SongEntity): String? =
        SongPathNormalizer.resolveFilePath(
            normalizedKeeperUri(keeper),
            keeper.folderPath
        )
}
