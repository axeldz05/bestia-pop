package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.PlaylistPendingTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.radio.RadioSuggestResult
import com.bestiapop.android.domain.repository.IMusicRepository

data class SimilarPlaylistPreview(
    val items: List<PlayableItem>,
    val mode: RadioMode,
    val usedOnline: Boolean,
    val failedOnline: Boolean
)

/**
 * Multi-seed similares preview via [RadioEngine.suggestFromSeeds], with co-playlist
 * boost union across local seeds. Playlist create mirrors LB import (locals + pending remotes).
 */
class BuildSimilarPlaylistPreviewUseCase(
    private val radioEngine: RadioEngine,
    private val repository: IMusicRepository
) {

    suspend fun execute(
        seeds: List<PlayableItem>,
        library: List<Song>,
        mode: RadioMode,
        lbToken: String? = null,
        lbAvailable: Boolean = false,
        lbUsername: String? = null,
        networkAvailable: Boolean = false,
        limit: Int = RadioEngine.PREVIEW_DEFAULT_LIMIT
    ): SimilarPlaylistPreview {
        val coPlaylistSongIds = resolveCoPlaylistUnion(seeds)
        val result: RadioSuggestResult = radioEngine.suggestFromSeeds(
            seeds = seeds,
            library = library,
            mode = mode,
            excludeKeys = emptySet(),
            limit = limit,
            lbToken = lbToken,
            lbAvailable = lbAvailable,
            lbUsername = lbUsername,
            networkAvailable = networkAvailable,
            coPlaylistSongIds = coPlaylistSongIds
        )
        return SimilarPlaylistPreview(
            items = result.items,
            mode = mode,
            usedOnline = result.usedOnlineDiscovery,
            failedOnline = result.onlineDiscoveryFailed
        )
    }

    /**
     * Creates a Room playlist: [PlayableItem.Local] as song refs;
     * [PlayableItem.Remote] as metadata-only [PlaylistPendingTrack] (no CDN).
     *
     * @return playlist id, or null when [items] is empty and [allowEmpty] is false
     */
    suspend fun createPlaylistFromPlayables(
        name: String,
        items: List<PlayableItem>,
        description: String? = null,
        allowEmpty: Boolean = false
    ): Long? {
        if (items.isEmpty() && !allowEmpty) return null
        val playlistId = repository.createPlaylist(
            name = name.ifBlank { defaultPlaylistName(seedCount = 0) },
            description = description,
            coverUri = null
        )
        val pending = ArrayList<PlaylistPendingTrack>()
        items.forEachIndexed { index, item ->
            when (item) {
                is PlayableItem.Local -> repository.addSongToPlaylist(playlistId, item.song.id)
                is PlayableItem.Remote -> pending.add(
                    PlaylistPendingTrack(
                        identity = item.identity,
                        playlistId = playlistId,
                        recordingMbid = item.recordingMbid,
                        position = index
                    )
                )
            }
        }
        if (pending.isNotEmpty()) {
            repository.addPlaylistPendingTracks(pending)
        }
        return playlistId
    }

    companion object {
        fun defaultPlaylistName(seeds: List<PlayableItem>): String {
            val firstArtist = seeds.firstOrNull { it.artist.isNotBlank() }?.artist?.trim()
            return if (!firstArtist.isNullOrEmpty()) {
                "Similares · $firstArtist"
            } else {
                defaultPlaylistName(seedCount = seeds.size)
            }
        }

        fun defaultPlaylistName(seedCount: Int): String =
            if (seedCount > 0) "Similares ($seedCount seeds)" else "Similares"
    }

    private suspend fun resolveCoPlaylistUnion(seeds: List<PlayableItem>): Set<Long> {
        val out = LinkedHashSet<Long>()
        for (seed in seeds) {
            val local = seed as? PlayableItem.Local ?: continue
            val ids = runCatching { repository.getCoPlaylistSongIds(local.song.id) }
                .getOrDefault(emptySet())
            out.addAll(ids)
        }
        return out
    }
}
