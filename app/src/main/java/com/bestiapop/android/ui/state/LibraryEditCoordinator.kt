package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.domain.util.findAlbumMergeTarget
import com.bestiapop.android.domain.util.normalizeAlbumName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Immutable
data class PendingAlbumMerge(
    val source: Album,
    val target: Album
)

/**
 * Coordinator for editing track and album metadata, resolving and executing album merges,
 * changing album covers, and saving/removing catalog albums from the library.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class LibraryEditCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val getLibrarySongsUseCase: GetLibrarySongsUseCase = GetLibrarySongsUseCase(),
    private val updateAlbumArtworkInQueue: (albumName: String, artworkUri: String) -> Unit,
    private val onSongsDeleted: (Set<Long>) -> Unit = {},
    private val toast: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _pendingAlbumMerge = MutableStateFlow<PendingAlbumMerge?>(null)
    val pendingAlbumMerge: StateFlow<PendingAlbumMerge?> = _pendingAlbumMerge.asStateFlow()

    fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int = 0,
        trackNumber: Int = 0
    ) {
        scope.launch {
            repository.updateSongMetadata(songId, title, artist, album, genre, year, trackNumber)
        }
    }

    fun deleteSongsFromApp(songs: List<Song>) {
        scope.launch {
            repository.deleteSongsFromApp(songs)
            onSongsDeleted(songs.map { it.id }.toSet())
        }
    }

    fun deleteSongsFromDevice(songs: List<Song>) {
        scope.launch {
            repository.deleteSongsFromDevice(songs)
            onSongsDeleted(songs.map { it.id }.toSet())
        }
    }

    fun setAlbumArtwork(albumName: String, artworkUri: String) {
        updateAlbumArtworkInQueue(albumName, artworkUri)
        scope.launch(ioDispatcher) {
            repository.setAlbumArtwork(albumName, artworkUri)
        }
    }

    fun requestSaveAlbumMetadata(
        source: Album,
        displayName: String,
        artist: String,
        genre: String,
        year: Int,
        artworkUri: String?,
        propagateToSongs: Boolean
    ) {
        scope.launch {
            val songs = repository.getAllSongsSync()
            val overrides = repository.albumOverridesFlow.first()
            val albums = getLibrarySongsUseCase.extractAlbums(
                songs,
                overrides.associateBy { it.albumKey }
            )
            val conflict = findAlbumMergeTarget(albums, source.name, displayName)
            if (conflict != null) {
                _pendingAlbumMerge.value = PendingAlbumMerge(source = source, target = conflict)
                return@launch
            }
            val normalizedName = normalizeAlbumName(displayName).ifBlank { source.name }
            saveAlbumOverride(
                AlbumOverride(
                    albumKey = source.name,
                    displayName = normalizedName,
                    artist = artist.takeIf { it.isNotBlank() },
                    genre = genre.takeIf { it.isNotBlank() },
                    year = year.coerceAtLeast(0),
                    artworkUri = artworkUri
                ),
                propagateToSongs = propagateToSongs
            )
        }
    }

    fun confirmPendingAlbumMerge() {
        val pending = _pendingAlbumMerge.value ?: return
        scope.launch {
            repository.mergeAlbumInto(pending.source.name, pending.target.name)
            _pendingAlbumMerge.value = null
            toast(DownloadMessages.albumsMerged)
        }
    }

    fun dismissPendingAlbumMerge() {
        _pendingAlbumMerge.value = null
    }

    private suspend fun saveAlbumOverride(
        override: AlbumOverride,
        propagateToSongs: Boolean
    ) {
        if (propagateToSongs) repository.updateAlbumMetadataPropagateToSongs(override)
        else repository.upsertAlbumOverride(override)
    }

    fun mergeAlbumInto(sourceAlbumKey: String, targetAlbumKey: String) {
        scope.launch {
            repository.mergeAlbumInto(sourceAlbumKey, targetAlbumKey)
            toast(DownloadMessages.albumsMerged)
        }
    }

    fun saveAlbumToLibrary(
        albumTitle: String,
        artistName: String,
        coverUrl: String? = null,
        year: Int = 0,
        genre: String = Song.UNKNOWN_GENRE,
        candidates: List<CatalogTrackCandidate> = emptyList(),
        albumId: String = ""
    ) {
        scope.launch {
            try {
                val effectiveCandidates = if (candidates.isNotEmpty()) {
                    candidates
                } else {
                    MetadataFetcher.fetchAlbumTrackCandidates(
                        albumId = albumId,
                        albumTitle = albumTitle,
                        artistName = artistName,
                        albumCoverUrl = coverUrl
                    )
                }
                repository.saveAlbumTracksToLibrary(
                    albumTitle = albumTitle,
                    artistName = artistName,
                    coverUrl = coverUrl,
                    year = year,
                    genre = genre,
                    tracks = effectiveCandidates
                )
                toast(DownloadMessages.albumSaved)
            } catch (e: Exception) {
                toast("Error al guardar álbum: ${e.message}")
            }
        }
    }

    fun saveAlbumToLibrary(album: CatalogAlbum, candidates: List<CatalogTrackCandidate> = emptyList()) {
        saveAlbumToLibrary(
            albumTitle = album.title,
            artistName = album.artist,
            coverUrl = album.coverUrl,
            year = album.releaseYear.toIntOrNull() ?: 0,
            genre = Song.UNKNOWN_GENRE,
            candidates = candidates,
            albumId = album.id
        )
    }

    fun removeSavedAlbum(albumName: String, artistName: String) {
        scope.launch {
            try {
                val removed = repository.removeSavedAlbumFromLibrary(albumName, artistName)
                toast(if (removed > 0) "Álbum eliminado de la biblioteca" else "No se encontraron pistas para eliminar")
            } catch (e: Exception) {
                toast("Error al eliminar álbum: ${e.message}")
            }
        }
    }

    fun removeSavedAlbum(album: CatalogAlbum) = removeSavedAlbum(album.title, album.artist)

    fun removeSavedAlbum(album: Album) = removeSavedAlbum(album.name, album.artist)
}
