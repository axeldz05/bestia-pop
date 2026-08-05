package com.bestiapop.android.data.migration

import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.db.PlaylistSongCrossRef
import com.bestiapop.android.data.util.SongPathNormalizer
import java.io.File

/**
 * One-shot library cleanup: one row per normalized title+artist, remap playlists,
 * delete loser DB rows and safe-to-delete duplicate files on disk.
 */
class LibraryDedupMigrator(
    private val musicDao: MusicDao,
    private val deleteFile: (File) -> Boolean = { it.delete() }
) {

    suspend fun run(): DedupResult {
        val songs = musicDao.getAllSongs()
        val groups = LibraryDedupLogic.groupDuplicates(songs)
        var songsRemoved = 0
        var filesDeleted = 0
        var keepersNormalized = 0

        for (group in groups) {
            var keeper = group.keeper
            val normalizedUri = LibraryDedupLogic.normalizedKeeperUri(keeper)
            if (normalizedUri != keeper.uriString) {
                keeper = keeper.copy(uriString = normalizedUri)
                musicDao.updateSong(keeper)
                keepersNormalized++
            }

            val keeperPath = LibraryDedupLogic.keeperFilePath(keeper)

            for (loser in group.losers) {
                remapPlaylistRefs(loserId = loser.id, keeperId = keeper.id)

                val loserPath = LibraryDedupLogic.loserFilePath(loser)
                val sameFile = keeperPath != null && loserPath != null &&
                    SongPathNormalizer.pathsReferToSameFile(keeperPath, loserPath)

                if (!sameFile && loserPath != null && SongPathNormalizer.isSafeToDeleteAppManagedFile(loserPath)) {
                    val file = File(loserPath)
                    if (file.exists() && deleteFile(file)) {
                        filesDeleted++
                    }
                }

                musicDao.deleteSong(loser.id)
                songsRemoved++
            }
        }

        return DedupResult(
            groupsProcessed = groups.size,
            songsRemoved = songsRemoved,
            filesDeleted = filesDeleted,
            keepersNormalized = keepersNormalized
        )
    }

    private suspend fun remapPlaylistRefs(loserId: Long, keeperId: Long) {
        val refs = musicDao.getPlaylistRefsForSong(loserId)
        for (ref in refs) {
            musicDao.addSongToPlaylist(
                PlaylistSongCrossRef(
                    playlistId = ref.playlistId,
                    songId = keeperId,
                    position = ref.position
                )
            )
            musicDao.removeSongFromPlaylist(ref.playlistId, loserId)
        }
    }
}
