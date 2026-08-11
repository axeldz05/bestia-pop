package com.bestiapop.android.domain.repository

import android.net.Uri
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.TagSyncSummary
import kotlinx.coroutines.flow.Flow

/** Scan/import progress: done, total, current file label. */
typealias LibraryScanProgress = (done: Int, total: Int, fileName: String) -> Unit

interface IMusicRepository {
    val allSongsFlow: Flow<List<Song>>
    val playlistsFlow: Flow<List<Playlist>>
    val albumOverridesFlow: Flow<List<AlbumOverride>>

    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<Song>>
    fun getPlaylistDetailsFlow(playlistId: Long): Flow<Pair<Playlist, List<Song>>?>

    suspend fun scanMediaStore(onProgress: LibraryScanProgress? = null)
    /** Indexes audio under public Music/BestiaPop after reinstall (Room wipe). Returns inserted count. */
    suspend fun resyncAppManagedMusic(onProgress: LibraryScanProgress? = null): Int
    /** SAF folder import. Returns number of newly inserted songs. */
    suspend fun scanFolderUri(treeUri: Uri, onProgress: LibraryScanProgress? = null): Int
    suspend fun getAllSongsSync(): List<Song>
    suspend fun findSongByArtistTitle(artist: String, title: String): Song?
    suspend fun saveUploadedSong(song: Song): Long
    suspend fun deleteSongsFromApp(songs: List<Song>)
    suspend fun deleteSongsFromDevice(songs: List<Song>)
    suspend fun enhanceSongMetadataAndLyrics(song: Song)
    /**
     * Ranked online candidates for a library song.
     * May persist a soft cleanup of rip-style tags (`01` / `- Title`) before searching.
     * Songs that already have usable artist+album return [IdentifyProposal.alreadyIdentified]
     * unless [force] is true (WiFi import always forces to detect catalog conflicts).
     * Song tags are predominant ranking source; [customQuery] replaces the default search text.
     * [filters] refine artist/album/year (Deezer advanced query + ranking boosts).
     * [catalogIndex] + [existingCandidates] page/append for “mostrar más” without reshuffling shown rows.
     * When [listenBrainzToken] is set and catalog confidence is not HIGH, may enrich via ListenBrainz.
     */
    suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String? = null,
        force: Boolean = false,
        listenBrainzToken: String? = null,
        filters: IdentifySearchFilters = IdentifySearchFilters(),
        catalogIndex: Int = 0,
        existingCandidates: List<IdentifyCandidate> = emptyList()
    ): IdentifyProposal

    /** Persist one identify candidate onto [songId]. */
    suspend fun applySongIdentity(songId: Long, candidate: IdentifyCandidate): IdentifyResult

    /**
     * Look up artist/album online for a library song with missing/placeholder metadata.
     * Auto-applies only [com.bestiapop.android.data.model.IdentifyConfidence.HIGH] matches
     * (propose + apply). Medium/low are left unchanged ([IdentifyResult.NoMatch]).
     */
    suspend fun identifySongMetadata(song: Song): IdentifyResult
    suspend fun updateSongDuration(songId: Long, durationMs: Long)
    suspend fun touchSongLastPlayed(songId: Long, playedAt: Long = System.currentTimeMillis())
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int = 0,
        trackNumber: Int = 0
    )

    /** Persist album-level override only (does not rewrite songs). */
    suspend fun upsertAlbumOverride(override: AlbumOverride)

    /**
     * Persist album override and rewrite artist/album/genre/year/artwork on all songs
     * currently under [AlbumOverride.albumKey].
     */
    suspend fun updateAlbumMetadataPropagateToSongs(override: AlbumOverride)

    /**
     * Move all songs under [sourceAlbumKey] into [targetAlbumKey], rewriting their
     * album/artist/genre/year/artwork to match the effective metadata of the target album.
     * Deletes the source album override; leaves the target override untouched.
     */
    suspend fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String)

    suspend fun getAlbumOverride(albumKey: String): AlbumOverride?

    fun extractAndSaveEmbeddedArtwork(audioPathOrUri: String, identifier: String): String?
    fun savePlaylistCoverImage(sourceUriStr: String?): String?
    fun saveAlbumCoverImage(sourceUriStr: String?): String?

    suspend fun createPlaylist(name: String, description: String? = null, coverUri: String? = null): Long
    suspend fun updatePlaylist(id: Long, name: String, description: String? = null, coverUri: String? = null)
    suspend fun deletePlaylist(id: Long)
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long)
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    /** Playlist ids that currently contain [songId]. */
    suspend fun getPlaylistIdsForSong(songId: Long): List<Long>

    /** Song ids that share at least one playlist with [songId] (for local radio scoring). */
    suspend fun getCoPlaylistSongIds(songId: Long): Set<Long>

    fun getPlaylistPendingTracksFlow(playlistId: Long): Flow<List<PlaylistPendingTrack>>
    suspend fun addPlaylistPendingTracks(tracks: List<PlaylistPendingTrack>)
    suspend fun removePlaylistPendingTrack(playlistId: Long, artist: String, title: String)

    suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((com.bestiapop.android.data.model.DownloadPhase) -> Unit)? = null,
        conflictPolicy: com.bestiapop.android.data.model.DownloadConflictPolicy? = null
    ): Song

    /**
     * Write Room metadata into local writable audio files (BestiaPop path).
     * Skips content:// and unsupported formats. [onProgress] is (done, total, fileLabel).
     */
    suspend fun syncTagsToFiles(onProgress: LibraryScanProgress? = null): TagSyncSummary
}
