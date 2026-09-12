package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.DiscoverPlaybackOrigin
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.catalogPreviewKeyFor
import com.bestiapop.android.data.model.isRemote
import com.bestiapop.android.data.model.toPlayableItems
import com.bestiapop.android.data.model.toPlayableItemsWithFreshIds
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.service.PlaybackRuntime
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class GroupPlaybackAction { PLAY, PLAY_SHUFFLED, PLAY_NEXT, ENQUEUE }

/**
 * Coordinates playback execution, grouping collections ("everything is a playlist"),
 * queue modifications, online catalog previewing, and Now Playing triggers.
 */
class PlaybackExecutionCoordinator(
    private val scope: CoroutineScope,
    private val playbackRuntime: PlaybackRuntime,
    private val repository: IMusicRepository,
    private val libraryProjection: LibraryProjectionState,
    private val getLibrarySongsUseCase: GetLibrarySongsUseCase,
    private val getLocalSongsByMatchKey: () -> Map<String, Song>,
    private val getLibraryBrowseFilter: () -> LibraryBrowseFilter,
    private val getPlaylistDetail: () -> PlaylistDetailNav,
    private val getLibraryViewMode: () -> LibraryViewMode,
    private val getSortOption: () -> SortOption,
    private val getSortDirection: () -> SortDirection,
    private val playlistsFlow: Flow<List<Playlist>>,
    private val isOpenNowPlayingOnPlay: () -> Boolean,
    private val togglePlayPause: () -> Unit,
    private val isPlaying: StateFlow<Boolean>
) {
    /** Stable key of the catalog track being previewed inside Add Music (null = no catalog preview). */
    private val _catalogPreviewKey = MutableStateFlow<String?>(null)
    val catalogPreviewKey: StateFlow<String?> = _catalogPreviewKey.asStateFlow()

    private val _openNowPlayingEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openNowPlayingEvents: SharedFlow<Unit> = _openNowPlayingEvents.asSharedFlow()

    fun playCollection(songs: List<Song>, startIndex: Int = 0, startShuffled: Boolean = false) {
        if (songs.isEmpty()) return
        if (startShuffled) {
            shuffleCollection(songs)
        } else {
            val validIndex = startIndex.coerceIn(0, songs.size - 1)
            playSong(songs[validIndex], songs)
        }
    }

    fun playCollection(songs: List<Song>, startShuffled: Boolean) {
        playCollection(songs, startIndex = 0, startShuffled = startShuffled)
    }

    fun playCollection(songs: List<Song>, startSong: Song) {
        playSong(startSong, songs)
    }

    /**
     * Level 1: Core pipeline for executing playback actions on any collection of songs.
     */
    fun executeGroupPlayback(songs: List<Song>, action: GroupPlaybackAction) {
        if (songs.isEmpty()) return
        when (action) {
            GroupPlaybackAction.PLAY -> playCollection(songs, startShuffled = false)
            GroupPlaybackAction.PLAY_SHUFFLED -> shuffleCollection(songs)
            GroupPlaybackAction.PLAY_NEXT -> playNextBatch(songs)
            GroupPlaybackAction.ENQUEUE -> enqueueCollection(songs)
        }
    }

    fun playAlbum(albumName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForAlbum(libraryProjection.songs.value, albumName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playAlbum(album: Album, startShuffled: Boolean = false) = playAlbum(album.name, startShuffled)

    fun playAlbumNext(albumName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForAlbum(libraryProjection.songs.value, albumName),
            GroupPlaybackAction.PLAY_NEXT
        )
    }

    fun playAlbumNext(album: Album) = playAlbumNext(album.name)

    fun enqueueAlbum(albumName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForAlbum(libraryProjection.songs.value, albumName),
            GroupPlaybackAction.ENQUEUE
        )
    }

    fun enqueueAlbum(album: Album) = enqueueAlbum(album.name)

    fun playArtist(artistName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForArtist(libraryProjection.songs.value, artistName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playArtistNext(artistName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForArtist(libraryProjection.songs.value, artistName),
            GroupPlaybackAction.PLAY_NEXT
        )
    }

    fun enqueueArtist(artistName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsForArtist(libraryProjection.songs.value, artistName),
            GroupPlaybackAction.ENQUEUE
        )
    }

    fun playGenre(genreName: String, startShuffled: Boolean = false) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsMatchingGenre(libraryProjection.songs.value, genreName),
            if (startShuffled) GroupPlaybackAction.PLAY_SHUFFLED else GroupPlaybackAction.PLAY
        )
    }

    fun playGenreNext(genreName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsMatchingGenre(libraryProjection.songs.value, genreName),
            GroupPlaybackAction.PLAY_NEXT
        )
    }

    fun enqueueGenre(genreName: String) {
        executeGroupPlayback(
            getLibrarySongsUseCase.songsMatchingGenre(libraryProjection.songs.value, genreName),
            GroupPlaybackAction.ENQUEUE
        )
    }

    fun playCurrentLibraryBrowse(shuffle: Boolean) {
        val filter = getLibraryBrowseFilter()
        if (filter == LibraryBrowseFilter.PLAYLISTS) {
            scope.launch {
                val detailId = (getPlaylistDetail() as? PlaylistDetailNav.Local)?.id
                val songsToPlay = if (detailId != null) {
                    repository.getPlaylistSongsOrdered(detailId)
                } else {
                    val currentPlaylists = playlistsFlow.first()
                    currentPlaylists.flatMap { repository.getPlaylistSongsOrdered(it.id) }
                }
                if (songsToPlay.isEmpty()) return@launch
                if (shuffle) shuffleCollection(songsToPlay) else playCollection(songsToPlay)
            }
            return
        }
        val songs = libraryProjection.songs.value
        val queue = when (filter) {
            LibraryBrowseFilter.SONGS -> libraryProjection.songList.value.songsVisual
            LibraryBrowseFilter.RECENT -> libraryProjection.recentSongs.value
            else -> getLibrarySongsUseCase.songsForBrowseProjection(
                filter = filter,
                songs = songs,
                viewMode = getLibraryViewMode(),
                albums = libraryProjection.albums.value,
                artists = libraryProjection.artists.value,
                genres = libraryProjection.genres.value,
                sortOption = getSortOption(),
                sortDirection = getSortDirection()
            )
        }
        if (queue.isEmpty()) return
        if (shuffle) shuffleCollection(queue) else playCollection(queue)
    }

    fun playSong(
        song: Song,
        playlistOrQueue: List<Song> = emptyList(),
        applyManualModes: Boolean = true,
        openNowPlaying: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val baseList = when {
            playlistOrQueue.isNotEmpty() -> playlistOrQueue
            else -> libraryProjection.songList.value.songsVisual.ifEmpty {
                libraryProjection.songs.value
            }
        }
        val indexInBase = baseList.indexOfFirst { it.id == song.id || it.uriString == song.uriString }

        val targetQueue = if (indexInBase != -1) baseList else listOf(song)
        val index = if (indexInBase != -1) indexInBase else 0
        playPlayableCollection(
            targetQueue.toPlayableItemsWithFreshIds { libraryProjection.resolveAlbumArtwork(it) },
            index,
            applyManualModes = applyManualModes,
            openNowPlaying = openNowPlaying
        )
    }

    fun playPlayableCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        fromRadio: Boolean = false,
        rotate: Boolean = true,
        applyManualModes: Boolean = true,
        startShuffled: Boolean = false,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None,
        resumeAtMs: Long? = null,
        openNowPlaying: Boolean = true
    ) {
        playbackRuntime.playPlayableCollection(
            items = items,
            startIndex = startIndex,
            fromRadio = fromRadio,
            rotate = rotate,
            applyManualModes = applyManualModes,
            startShuffled = startShuffled,
            origin = origin,
            resumeAtMs = resumeAtMs
        )
        if (openNowPlaying && items.isNotEmpty() && !fromRadio && isOpenNowPlayingOnPlay()) {
            _openNowPlayingEvents.tryEmit(Unit)
        }
    }

    fun catalogPreviewKeyForTrack(track: OnlineCatalogTrack): String =
        catalogPreviewKeyFor(track)

    fun playOnlineCatalogTrackAsStream(
        track: OnlineCatalogTrack,
        openNowPlaying: Boolean = true
    ) {
        val key = catalogPreviewKeyForTrack(track)
        if (_catalogPreviewKey.value == key && playbackRuntime.currentItem.value != null) {
            togglePlayPause()
            return
        }
        _catalogPreviewKey.value = key
        val queryOrId = YouTubeExtractor.resolveYouTubeQueryOrId(track)
        val remote = PlayableItem.remoteFrom(
            identity = track.identity,
            youtubeQueryOrId = queryOrId
        )
        playPlayableCollection(listOf(remote), 0, openNowPlaying = openNowPlaying)
    }

    /** Returns matched local (non-remote) Song from library index in O(1) time. */
    fun findLocalSongFor(meta: TrackMeta): Song? {
        val song = TrackMatchKeys.lookupLocalSong(getLocalSongsByMatchKey(), meta)
        return if (song != null && !song.isRemote) song else null
    }

    /** Plays local version if available in library; otherwise falls back to online stream. */
    fun playCatalogOrLocalTrack(
        track: OnlineCatalogTrack,
        openNowPlaying: Boolean = true
    ) {
        val local = findLocalSongFor(track.identity)
        if (local != null) {
            playSong(local, openNowPlaying = openNowPlaying)
        } else {
            playOnlineCatalogTrackAsStream(track, openNowPlaying = openNowPlaying)
        }
    }

    /** Plays collection of candidates, resolving any available local tracks to avoid streaming. */
    fun playCatalogCandidates(
        candidates: List<CatalogTrackCandidate>,
        startIndex: Int = 0,
        startShuffled: Boolean = false,
        openNowPlaying: Boolean = true
    ) {
        _catalogPreviewKey.value = null
        val playables = candidates.toPlayableItems(getLocalSongsByMatchKey())
        playPlayableCollection(
            playables,
            startIndex = startIndex,
            startShuffled = startShuffled,
            openNowPlaying = openNowPlaying
        )
    }

    /** Plays a single catalog candidate using local file if present, or streaming. */
    fun playCatalogCandidate(
        candidate: CatalogTrackCandidate,
        openNowPlaying: Boolean = true
    ) {
        playCatalogOrLocalTrack(candidate.effectiveTrack, openNowPlaying = openNowPlaying)
    }

    /** Preview local file while reviewing identify candidates (toggle if already current). */
    fun previewIdentifyLocalSong(song: Song) {
        _catalogPreviewKey.value = null
        val current = playbackRuntime.currentItem.value
        if (current is PlayableItem.Local && current.song.id == song.id) {
            togglePlayPause()
            return
        }
        val local = PlayableItem.Local(
            song = song,
            resolvedArtworkUri = libraryProjection.resolveAlbumArtwork(song)
        )
        playPlayableCollection(
            items = listOf(local),
            startIndex = 0,
            rotate = false,
            openNowPlaying = false
        )
    }

    /** Stream-preview a ranked identify candidate via YouTube (same path as catalog). */
    fun previewIdentifyCandidate(candidate: IdentifyCandidate) {
        playOnlineCatalogTrackAsStream(candidate.track, openNowPlaying = false)
    }

    fun clearCatalogPreview() {
        _catalogPreviewKey.value = null
    }

    /** Stops active catalog preview and pauses playback if currently playing. */
    fun stopCatalogPreview() {
        if (_catalogPreviewKey.value != null) {
            _catalogPreviewKey.value = null
            if (isPlaying.value) {
                togglePlayPause()
            }
        }
    }

    fun shuffleCollection(songs: List<Song>) {
        shufflePlayableCollection(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
    }

    fun shufflePlayableCollection(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None
    ): Boolean {
        if (items.isEmpty()) return false
        playPlayableCollection(
            items,
            startIndex = items.indices.random(),
            rotate = false,
            applyManualModes = false,
            startShuffled = true,
            origin = origin
        )
        return true
    }

    fun enqueueCollection(songs: List<Song>) {
        addToQueueBatch(songs)
    }

    fun addToQueue(song: Song) {
        addToQueueBatch(listOf(song))
    }

    fun addToQueueBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        addPlayableBatch(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
    }

    fun addPlayableBatch(items: List<PlayableItem>) {
        playbackRuntime.addPlayableBatch(items)
    }

    fun playNextInQueue(song: Song) {
        playNextBatch(listOf(song))
    }

    fun playNextBatch(songs: List<Song>) {
        playbackRuntime.playNextBatch(songs.toPlayableItems { libraryProjection.resolveAlbumArtwork(it) })
    }

    fun playNextPlayableBatch(items: List<PlayableItem>) {
        playbackRuntime.playNextBatch(items)
    }

    fun playMatchedCollection(
        items: List<PlayableItem>,
        startIndex: Int = 0,
        origin: DiscoverPlaybackOrigin = DiscoverPlaybackOrigin.None
    ): Boolean {
        if (items.isEmpty() || startIndex !in items.indices) return false
        _catalogPreviewKey.value = null
        playPlayableCollection(items, startIndex, origin = origin)
        return true
    }

    /** Play discover-matched tracks (CF / LB) with session origin. */
    fun playMatchedTracks(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin,
        startIndex: Int = 0
    ) {
        playMatchedCollection(items, startIndex = startIndex, origin = origin)
    }

    fun shuffleMatchedTracks(
        items: List<PlayableItem>,
        origin: DiscoverPlaybackOrigin
    ) {
        shufflePlayableCollection(items, origin = origin)
    }
}
