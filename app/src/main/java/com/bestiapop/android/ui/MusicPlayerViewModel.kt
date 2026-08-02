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
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.data.model.CustomTheme
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
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
                    val uri = item.mediaId
                    val song = _queue.value.find { it.uriString == uri } ?: songsState.value.find { it.uriString == uri }
                    _currentSong.value = song
                    song?.let { fetchOnlineMetadataForSong(it) }
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

    fun playSong(song: Song, playlistOrQueue: List<Song> = emptyList()) {
        val targetQueue = if (playlistOrQueue.isNotEmpty()) playlistOrQueue else songsState.value
        _queue.value = targetQueue
        _currentSong.value = song

        mediaController?.let { controller ->
            controller.clearMediaItems()
            val items = targetQueue.map { s ->
                MediaItem.Builder()
                    .setMediaId(s.uriString)
                    .setUri(s.uriString)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.title)
                            .setArtist(s.artist)
                            .setAlbumTitle(s.album)
                            .setArtworkUri(s.artworkUri?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()
            }
            controller.setMediaItems(items)
            val index = targetQueue.indexOfFirst { it.uriString == song.uriString }.coerceAtLeast(0)
            controller.seekTo(index, 0L)
            controller.prepare()
            controller.play()
        }

        fetchOnlineMetadataForSong(song)
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
                    .setUri(song.uriString)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(song.artworkUri?.let { Uri.parse(it) })
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
                    .setUri(song.uriString)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(song.artworkUri?.let { Uri.parse(it) })
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

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
