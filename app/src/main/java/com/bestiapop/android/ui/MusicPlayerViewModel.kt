package com.bestiapop.android.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.bestiapop.android.data.model.*

import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.data.repository.MusicRepository
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.Context
import android.media.AudioManager

enum class SortOption {
    TITLE,
    ARTIST,
    ALBUM,
    GENRE,
    DATE_ADDED
}

class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val themeRepository = ThemePreferencesRepository(application)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Theme state
    val currentThemeState: StateFlow<CustomTheme> = themeRepository.selectedThemeFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, ThemePresets.MidnightDark)

    // Raw songs & playlists
    val rawSongs = repository.allSongsFlow
    val playlists = repository.playlistsFlow

    // Sorting & Searching
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.TITLE)
    val sortOption = _sortOption.asStateFlow()

    val songsState: StateFlow<List<Song>> = combine(rawSongs, _searchQuery, _sortOption) { list, query, sort ->
        val albumArtMap = list.groupBy { it.album }.mapValues { (_, albumSongs) ->
            albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
        }

        val unifiedList = list.map { song ->
            val albumArt = song.artworkUri ?: albumArtMap[song.album]
            if (albumArt != song.artworkUri) song.copy(artworkUri = albumArt) else song
        }

        var filtered = if (query.isBlank()) unifiedList else unifiedList.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true) ||
            it.album.contains(query, ignoreCase = true) ||
            it.genre.contains(query, ignoreCase = true)
        }

        when (sort) {
            SortOption.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortOption.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SortOption.ALBUM -> filtered.sortedBy { it.album.lowercase() }
            SortOption.GENRE -> filtered.sortedBy { it.genre.lowercase() }
            SortOption.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumsState: StateFlow<List<Album>> = songsState.map { songs ->
        songs.groupBy { it.album }.map { (albumName, albumSongs) ->
            Album(
                name = albumName,
                artist = albumSongs.firstOrNull()?.artist ?: "Unknown Artist",
                songCount = albumSongs.size,
                artworkUri = albumSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artistsState: StateFlow<List<Artist>> = songsState.map { songs ->
        songs.groupBy { it.artist }.map { (artistName, artistSongs) ->
            val photo = artistSongs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri
            Artist(
                name = artistName,
                songCount = artistSongs.size,
                albumCount = artistSongs.map { it.album }.distinct().size,
                photoUri = photo
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Player State
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle = _isShuffle.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    private val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _volumeLevel = MutableStateFlow(getDeviceVolumeRatio())
    val volumeLevel = _volumeLevel.asStateFlow()

    // Online Catalog & Link Downloader State
    private val _catalogSearchResults = MutableStateFlow<List<OnlineCatalogTrack>>(emptyList())
    val catalogSearchResults = _catalogSearchResults.asStateFlow()

    private val _catalogCategory = MutableStateFlow(CatalogCategory.SONGS)
    val catalogCategory = _catalogCategory.asStateFlow()

    private val _albumSearchResults = MutableStateFlow<List<CatalogAlbum>>(emptyList())
    val albumSearchResults = _albumSearchResults.asStateFlow()

    private val _playlistSearchResults = MutableStateFlow<List<CatalogPlaylist>>(emptyList())
    val playlistSearchResults = _playlistSearchResults.asStateFlow()

    private val _selectedCollectionTitle = MutableStateFlow<String?>(null)
    val selectedCollectionTitle = _selectedCollectionTitle.asStateFlow()

    private val _activeTrackCandidates = MutableStateFlow<List<CatalogTrackCandidate>>(emptyList())
    val activeTrackCandidates = _activeTrackCandidates.asStateFlow()

    private val _isLoadingCollection = MutableStateFlow(false)
    val isLoadingCollection = _isLoadingCollection.asStateFlow()

    private val _isSearchingCatalog = MutableStateFlow(false)
    val isSearchingCatalog = _isSearchingCatalog.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    private fun getDeviceVolumeRatio(): Float {

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    fun setVolume(ratio: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (ratio * max).toInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _volumeLevel.value = ratio
    }

    init {
        initMediaController()
        startPositionTracker()
        viewModelScope.launch {
            repository.scanMediaStore()
        }
        viewModelScope.launch {
            _catalogSearchResults.value = MetadataFetcher.getFeaturedDemoCatalog()
            _albumSearchResults.value = MetadataFetcher.searchAlbums("")
            _playlistSearchResults.value = MetadataFetcher.searchPlaylists("")
        }

        viewModelScope.launch {
            songsState.collect { songs ->
                _currentSong.value?.let { current ->
                    songs.find { it.uriString == current.uriString }?.let { updated ->
                        _currentSong.value = updated
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            songsState.collect { songs ->
                val unenhanced = songs.filter { it.artworkUri.isNullOrEmpty() || it.artworkUri?.startsWith("content://") == true }
                for (song in unenhanced.take(20)) {
                    repository.enhanceSongMetadataAndLyrics(song)
                }
            }
        }
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MusicService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupPlayerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item ->
                    val idOrUri = item.mediaId
                    val song = _queue.value.find { it.uriString == idOrUri || it.id.toString() == idOrUri }
                        ?: songsState.value.find { it.uriString == idOrUri || it.id.toString() == idOrUri }
                    if (song != null) {
                        _currentSong.value = song
                        fetchOnlineMetadataForSong(song)
                    }
                }
            }

        })
    }

    private var lastSeekTimestamp = 0L

    private fun startPositionTracker() {
        viewModelScope.launch {
            while (true) {
                mediaController?.let { controller ->
                    if (controller.isPlaying && System.currentTimeMillis() - lastSeekTimestamp > 600) {
                        _playbackPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                        val dur = controller.duration
                        val curr = _currentSong.value
                        if (dur > 0 && curr != null && curr.durationMs <= 0) {
                            updateSongDuration(curr.id, dur)
                        }
                    }
                }
                delay(200)
            }
        }
    }

    fun updateSongDuration(songId: Long, durationMs: Long) {
        viewModelScope.launch {
            repository.updateSongDuration(songId, durationMs)
        }
    }

    fun retryFetchLyrics(song: Song) {
        viewModelScope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    fun enhanceSongMetadataAndLyrics(song: Song) {
        viewModelScope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    private fun parseToMediaUri(uriStr: String?): Uri {
        if (uriStr.isNullOrBlank()) return Uri.EMPTY
        val trimmed = uriStr.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("content://")) {
            return Uri.parse(trimmed)
        }
        var cleanPath = trimmed
        while (cleanPath.startsWith("file:")) {
            cleanPath = cleanPath.removePrefix("file:")
        }
        cleanPath = "/" + cleanPath.trimStart('/')
        return Uri.fromFile(java.io.File(cleanPath))
    }

    private fun parseToArtworkUri(uriStr: String?): Uri? {
        if (uriStr.isNullOrBlank()) return null
        val trimmed = uriStr.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("content://")) {
            Uri.parse(trimmed)
        } else {
            null
        }
    }



    fun playSong(song: Song, playlistOrQueue: List<Song> = emptyList()) {
        val baseList = if (playlistOrQueue.isNotEmpty()) playlistOrQueue else songsState.value
        val indexInBase = baseList.indexOfFirst { it.id == song.id || it.uriString == song.uriString }

        val targetQueue = if (indexInBase != -1) baseList else listOf(song)
        val index = if (indexInBase != -1) indexInBase else 0

        _queue.value = targetQueue
        _currentSong.value = targetQueue[index]

        mediaController?.let { controller ->
            controller.clearMediaItems()
            val items = targetQueue.map { s ->
                MediaItem.Builder()
                    .setMediaId(s.uriString)
                    .setUri(parseToMediaUri(s.uriString))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.title)
                            .setArtist(s.artist)
                            .setAlbumTitle(s.album)
                            .setArtworkUri(parseToArtworkUri(s.artworkUri))
                            .build()
                    )

                    .build()
            }
            controller.setMediaItems(items)
            controller.seekTo(index, 0L)
            controller.prepare()
            controller.play()
        }

        fetchOnlineMetadataForSong(targetQueue[index])
    }





    // Unified Collection / Group Pipeline ("Everything is a Playlist")
    fun playCollection(songs: List<Song>, startSong: Song? = null) {
        if (songs.isEmpty()) return
        val targetStart = startSong ?: songs.first()
        playSong(targetStart, songs)
    }

    fun shuffleCollection(songs: List<Song>) {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        playSong(shuffled.first(), shuffled)
    }

    fun enqueueCollection(songs: List<Song>) {
        addToQueueBatch(songs)
    }

    private fun fetchOnlineMetadataForSong(song: Song) {
        viewModelScope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }

    fun skipToNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        mediaController?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        lastSeekTimestamp = System.currentTimeMillis()
        _playbackPositionMs.value = positionMs
        mediaController?.seekTo(positionMs)
    }

    fun toggleRepeatMode() {
        val nextMode = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = nextMode

        mediaController?.let { controller ->
            controller.repeatMode = when (nextMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        mediaController?.shuffleModeEnabled = newShuffle
    }

    // Queue Management
    fun addToQueue(song: Song) {
        addToQueueBatch(listOf(song))
    }

    fun addToQueueBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentList = _queue.value.toMutableList()
        currentList.addAll(songs)
        _queue.value = currentList

        mediaController?.let { controller ->
            val mediaItems = songs.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.uriString)
                    .setUri(parseToMediaUri(song.uriString))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(parseToArtworkUri(song.artworkUri))
                            .build()
                    ).build()


            }
            controller.addMediaItems(mediaItems)
        }

    }

    fun playNextInQueue(song: Song) {
        playNextBatch(listOf(song))
    }

    fun playNextBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentList = _queue.value.toMutableList()
        val currentIndex = (mediaController?.currentMediaItemIndex ?: 0).coerceAtLeast(0)
        val insertIndex = (currentIndex + 1).coerceAtMost(currentList.size)

        currentList.addAll(insertIndex, songs)
        _queue.value = currentList

        mediaController?.let { controller ->
            val mediaItems = songs.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.uriString)
                    .setUri(parseToMediaUri(song.uriString))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(parseToArtworkUri(song.artworkUri))
                            .build()
                    ).build()


            }
            controller.addMediaItems(insertIndex, mediaItems)
        }

    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch {
            songs.forEach { song ->
                repository.addSongToPlaylist(playlistId, song.id)
            }
        }
    }

    fun deleteSongsFromApp(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromApp(songs)
        }
    }

    fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String
    ) {
        viewModelScope.launch {
            repository.updateSongMetadata(songId, title, artist, album, genre)
        }
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        viewModelScope.launch {
            repository.deleteSongsFromDevice(songs)
        }
    }

    fun removeFromQueue(index: Int) {
        if (index in 0 until _queue.value.size) {
            val currentList = _queue.value.toMutableList()
            currentList.removeAt(index)
            _queue.value = currentList
            mediaController?.removeMediaItem(index)
        }
    }

    // Search and Sort
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
    }

    // SAF Import
    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            repository.scanFolderUri(treeUri)
        }
    }

    // Playlists
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, song.id)
        }
    }

    // Theme Actions
    fun selectThemePreset(presetId: String) {
        viewModelScope.launch {
            themeRepository.selectPreset(presetId)
        }
    }

    fun saveCustomTheme(colors: ColorSchemeData) {
        viewModelScope.launch {
            themeRepository.saveCustomColors(colors)
        }
    }

    // Online Catalog & Link Downloader Actions
    private var lastCatalogQuery = ""

    fun setCatalogCategory(category: CatalogCategory) {
        _catalogCategory.value = category
        searchCatalog(lastCatalogQuery)
    }

    fun searchCatalog(query: String) {
        lastCatalogQuery = query
        val cleanQ = query.trim()
        viewModelScope.launch {
            _isSearchingCatalog.value = true
            when (_catalogCategory.value) {
                CatalogCategory.SONGS -> {
                    _catalogSearchResults.value = MetadataFetcher.searchOnlineCatalog(cleanQ)
                }
                CatalogCategory.ALBUMS -> {
                    _albumSearchResults.value = MetadataFetcher.searchAlbums(cleanQ)
                }
                CatalogCategory.PLAYLISTS -> {
                    _playlistSearchResults.value = MetadataFetcher.searchPlaylists(cleanQ)
                }
            }
            _isSearchingCatalog.value = false
        }
    }


    fun searchOnlineCatalog(query: String) {
        searchCatalog(query)
    }

    fun selectAlbumForInspection(album: CatalogAlbum) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = album.title
            val candidates = MetadataFetcher.fetchAlbumTrackCandidates(album.id, album.title, album.artist, album.coverUrl)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        viewModelScope.launch {
            _isLoadingCollection.value = true
            _selectedCollectionTitle.value = playlist.title
            val candidates = MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
            _activeTrackCandidates.value = candidates
            _isLoadingCollection.value = false
        }
    }

    fun cycleTrackCandidate(index: Int) {
        val list = _activeTrackCandidates.value.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            viewModelScope.launch {
                var candidatesList = item.candidates
                if (candidatesList.size <= 1) {
                    val searchResults = com.bestiapop.android.data.network.YouTubeExtractor.searchYouTube("${item.artist} ${item.trackTitle}")
                    if (searchResults.isNotEmpty()) {
                        candidatesList = searchResults
                    }
                }
                if (candidatesList.isNotEmpty()) {
                    val nextIndex = (item.currentCandidateIndex + 1) % candidatesList.size
                    list[index] = item.copy(candidates = candidatesList, currentCandidateIndex = nextIndex)
                    _activeTrackCandidates.value = list
                }
            }
        }
    }

    fun toggleTrackSelection(index: Int) {
        val list = _activeTrackCandidates.value.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            list[index] = item.copy(isSelected = !item.isSelected)
            _activeTrackCandidates.value = list
        }
    }

    fun clearSelectedCollection() {
        _selectedCollectionTitle.value = null
        _activeTrackCandidates.value = emptyList()
        _isLoadingCollection.value = false
    }

    private fun updateCandidateState(
        trackTitle: String,
        state: CandidateDownloadState,
        percent: Int = 0,
        error: String? = null
    ) {
        val list = _activeTrackCandidates.value.toMutableList()
        val index = list.indexOfFirst { it.trackTitle == trackTitle }
        if (index != -1) {
            list[index] = list[index].copy(
                downloadState = state,
                downloadProgressPercent = percent,
                errorMessage = error
            )
            _activeTrackCandidates.value = list
        }
    }

    fun downloadSelectedCandidatesBatch() {
        val selected = _activeTrackCandidates.value.filter { it.isSelected && it.currentTrack != null }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading("Iniciando descarga de ${selected.size} canciones...")
            var count = 0
            var successCount = 0
            for (candidate in selected) {
                val track = candidate.currentTrack ?: continue
                count++
                _downloadStatus.value = DownloadStatus.Downloading("Descargando ($count/${selected.size}): ${track.title}...")

                updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 20)

                try {
                    updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 50)

                    repository.downloadAndSaveOnlineTrack(track) { msg ->
                        _downloadStatus.value = DownloadStatus.Downloading("Descargando ($count/${selected.size}): $msg")
                        if (msg.contains("Descargando audio", ignoreCase = true)) {
                            updateCandidateState(candidate.trackTitle, CandidateDownloadState.DOWNLOADING, percent = 75)
                        }
                    }

                    updateCandidateState(candidate.trackTitle, CandidateDownloadState.SUCCESS, percent = 100)
                    successCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                    val detailedErr = when {
                        e.message?.contains("403") == true -> "Error HTTP 403 Forbidden: Enlace o firma expirada de YouTube."
                        e.message?.contains("YouTube") == true -> e.message ?: "No se pudo obtener audio de YouTube."
                        else -> "Falló la descarga: ${e.localizedMessage ?: "Error de red o conexión."}"
                    }
                    updateCandidateState(candidate.trackTitle, CandidateDownloadState.ERROR, percent = 0, error = detailedErr)
                }
            }
            _downloadStatus.value = DownloadStatus.Success(
                song = Song(uriString = "", title = "Descarga completada"),
                message = "¡$successCount de ${selected.size} canciones procesadas!"
            )
        }
    }





    fun downloadFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading("Extrayendo información de YouTube...")
            val (track, errorMsg) = com.bestiapop.android.data.network.YouTubeLinkFetcher.fetchTrackFromUrlDetailed(trimmed)
            if (track == null) {
                _downloadStatus.value = DownloadStatus.Error(errorMsg ?: "No se pudieron obtener datos del enlace ingresado.")
                return@launch
            }
            downloadOnlineTrack(track)
        }
    }



    fun downloadOnlineTrack(track: OnlineCatalogTrack) {
        viewModelScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading("Iniciando descarga...")
            try {
                val song = repository.downloadAndSaveOnlineTrack(track) { progressMsg ->
                    _downloadStatus.value = DownloadStatus.Downloading(progressMsg)
                }
                _downloadStatus.value = DownloadStatus.Success(song, "¡${song.title} agregada a la biblioteca!")
            } catch (e: Exception) {
                _downloadStatus.value = DownloadStatus.Error("Error al descargar la canción: ${e.localizedMessage}")
            }
        }
    }

    fun resetDownloadStatus() {
        _downloadStatus.value = DownloadStatus.Idle
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

