package com.bestiapop.android.service.library

import androidx.media3.common.MediaItem
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.BrowseLocalLibrarySnapshot
import com.bestiapop.android.domain.usecase.BrowseLocalLibraryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MediaLibraryPlaybackSelection(
    val songs: List<Song>,
    val startIndex: Int,
    val startPositionMs: Long
)

internal class MediaLibraryBrowseProvider(
    private val repository: IMusicRepository,
    scope: CoroutineScope,
    private val useCase: BrowseLocalLibraryUseCase = BrowseLocalLibraryUseCase()
) {
    private data class Sources(
        val revision: Long,
        val songs: List<Song>,
        val overrides: List<AlbumOverride>,
        val playlists: List<Playlist>
    )

    private data class CachedSnapshot(
        val revision: Long,
        val value: BrowseLocalLibrarySnapshot
    )

    private var nextRevision = 0L
    private val sources = combine(
        repository.allSongsFlow,
        repository.albumOverridesFlow,
        repository.playlistsFlow
    ) { songs, overrides, playlists ->
        Sources(++nextRevision, songs, overrides, playlists)
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val snapshotMutex = Mutex()
    @Volatile
    private var cachedSnapshot: CachedSnapshot? = null

    suspend fun root(): MediaItem = MediaLibraryBrowseMapper.root()

    suspend fun item(mediaId: String): MediaItem? {
        val target = MediaLibraryIds.parse(mediaId) ?: return null
        return when (target) {
            MediaLibraryTarget.Root -> root()
            MediaLibraryTarget.Songs,
            MediaLibraryTarget.Albums,
            MediaLibraryTarget.Artists,
            MediaLibraryTarget.Playlists ->
                MediaLibraryBrowseMapper.categories().firstOrNull { it.mediaId == mediaId }
            is MediaLibraryTarget.Song -> snapshot().songs
                .firstOrNull { it.id == target.id }
                ?.let(MediaLibraryBrowseMapper::song)
            is MediaLibraryTarget.Album -> snapshot().albums
                .firstOrNull { it.name.equals(target.key, ignoreCase = true) }
                ?.let(MediaLibraryBrowseMapper::album)
            is MediaLibraryTarget.Artist -> snapshot().artists
                .firstOrNull { it.name.equals(target.name, ignoreCase = true) }
                ?.let(MediaLibraryBrowseMapper::artist)
            is MediaLibraryTarget.Playlist -> snapshot().playlists
                .firstOrNull { it.id == target.id }
                ?.let(MediaLibraryBrowseMapper::playlist)
        }
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        val target = MediaLibraryIds.parse(parentId) ?: return null
        if (target == MediaLibraryTarget.Root) {
            return useCase.page(MediaLibraryBrowseMapper.categories(), page, pageSize)
        }
        val snapshot = snapshot()
        val children = when (target) {
            MediaLibraryTarget.Root -> MediaLibraryBrowseMapper.categories()
            MediaLibraryTarget.Songs -> snapshot.songs.map(MediaLibraryBrowseMapper::song)
            MediaLibraryTarget.Albums -> snapshot.albums.map(MediaLibraryBrowseMapper::album)
            MediaLibraryTarget.Artists -> snapshot.artists.map(MediaLibraryBrowseMapper::artist)
            MediaLibraryTarget.Playlists ->
                snapshot.playlists.map(MediaLibraryBrowseMapper::playlist)
            is MediaLibraryTarget.Album ->
                useCase.songsForAlbum(snapshot, target.key).map(MediaLibraryBrowseMapper::song)
            is MediaLibraryTarget.Artist ->
                useCase.songsForArtist(snapshot, target.name).map(MediaLibraryBrowseMapper::song)
            is MediaLibraryTarget.Playlist ->
                repository.getPlaylistSongsOrdered(target.id).map(MediaLibraryBrowseMapper::song)
            is MediaLibraryTarget.Song -> return null
        }
        return useCase.page(children, page, pageSize)
    }

    suspend fun search(query: String, page: Int, pageSize: Int): List<MediaItem> =
        useCase.page(useCase.search(snapshot(), query), page, pageSize)
            .map(MediaLibraryBrowseMapper::song)

    suspend fun searchCount(query: String): Int = useCase.search(snapshot(), query).size

    suspend fun resolveSearchPlayback(
        query: String,
        startPositionMs: Long
    ): MediaLibraryPlaybackSelection? {
        val songs = useCase.search(snapshot(), query)
            .take(BrowseLocalLibraryUseCase.MAX_PAGE_SIZE)
        if (songs.isEmpty()) return null
        return MediaLibraryPlaybackSelection(
            songs = songs,
            startIndex = 0,
            startPositionMs = startPositionMs.coerceAtLeast(0L)
        )
    }

    suspend fun resolvePlayback(
        requested: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaLibraryPlaybackSelection? {
        if (requested.isEmpty()) return null
        val snapshot = snapshot()
        val singleTarget = requested.singleOrNull()?.mediaId?.let(MediaLibraryIds::parse)
        val songs = when (singleTarget) {
            is MediaLibraryTarget.Song ->
                snapshot.songs.filter { it.id == singleTarget.id }
            is MediaLibraryTarget.Album ->
                useCase.songsForAlbum(snapshot, singleTarget.key)
            is MediaLibraryTarget.Artist ->
                useCase.songsForArtist(snapshot, singleTarget.name)
            is MediaLibraryTarget.Playlist ->
                repository.getPlaylistSongsOrdered(singleTarget.id)
            else -> requested.mapNotNull { requestedItem ->
                val target = MediaLibraryIds.parse(requestedItem.mediaId)
                    as? MediaLibraryTarget.Song
                target?.let { songTarget ->
                    snapshot.songs.firstOrNull { it.id == songTarget.id }
                }
            }
        }
        if (songs.isEmpty()) return null
        return MediaLibraryPlaybackSelection(
            songs = songs,
            startIndex = if (singleTarget is MediaLibraryTarget.Song || requested.size > 1) {
                startIndex.coerceIn(songs.indices)
            } else {
                0
            },
            startPositionMs = startPositionMs.coerceAtLeast(0L)
        )
    }

    private suspend fun snapshot(): BrowseLocalLibrarySnapshot {
        val current = sources.filterNotNull().first()
        cachedSnapshot?.takeIf { it.revision == current.revision }?.let { return it.value }
        return snapshotMutex.withLock {
            val latest = sources.filterNotNull().first()
            cachedSnapshot?.takeIf { it.revision == latest.revision }?.value
                ?: withContext(Dispatchers.Default) {
                    useCase.snapshot(latest.songs, latest.overrides, latest.playlists)
                }.also { built ->
                    cachedSnapshot = CachedSnapshot(latest.revision, built)
                }
        }
    }
}
