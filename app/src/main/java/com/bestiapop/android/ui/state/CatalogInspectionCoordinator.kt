package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogGenre
import com.bestiapop.android.data.model.CatalogPlaylist
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.firstArtworkUri
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinator for inspecting online catalog collections (albums, playlists, genres, artists),
 * selecting candidates, and expanding YouTube search alternatives.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class CatalogInspectionCoordinator(
    private val scope: CoroutineScope,
    private val playOnlineCatalogTrackAsStream: (OnlineCatalogTrack, Boolean) -> Unit,
    private val onResetBatchPlaylistTarget: () -> Unit = {}
) {
    private val _catalogCollection = MutableStateFlow(CatalogCollectionUiState())
    val catalogCollection: StateFlow<CatalogCollectionUiState> = _catalogCollection.asStateFlow()

    private var catalogCollectionJob: Job? = null
    private var catalogCollectionGeneration = 0L

    /** Level 1: Low-level primitive album inspection with explicit title, artist and cover. */
    fun selectAlbumForInspection(
        title: String,
        artist: String,
        coverUrl: String? = null,
        albumId: String = ""
    ) {
        val key = if (albumId.isNotBlank()) "album:$albumId" else "album:$artist:$title"
        selectCollectionForInspection(
            selectionKey = key,
            title = title,
            kind = CatalogCollectionKind.ALBUM,
            coverUrl = coverUrl
        ) {
            MetadataFetcher.fetchAlbumTrackCandidates(albumId, title, artist, coverUrl)
        }
    }

    /** Level 2: Inspect a [CatalogAlbum]. */
    fun selectAlbumForInspection(album: CatalogAlbum) {
        selectAlbumForInspection(
            title = album.title,
            artist = album.artist,
            coverUrl = album.coverUrl,
            albumId = album.id
        )
    }

    /** Level 2: Inspect a [RelatedAlbumItem] without converting to a dummy [CatalogAlbum]. */
    fun selectAlbumForInspection(album: RelatedAlbumItem) {
        selectAlbumForInspection(
            title = album.title,
            artist = album.artist,
            coverUrl = album.artworkUri,
            albumId = ""
        )
    }

    fun selectPlaylistForInspection(playlist: CatalogPlaylist) {
        selectCollectionForInspection(
            selectionKey = "playlist:${playlist.id}",
            title = playlist.title,
            kind = CatalogCollectionKind.PLAYLIST,
            coverUrl = playlist.coverUrl
        ) {
            MetadataFetcher.fetchPlaylistTrackCandidates(playlist.id, playlist.title)
        }
    }

    fun selectGenreForInspection(genre: CatalogGenre) {
        selectCollectionForInspection(
            selectionKey = "genre:${genre.id}",
            title = genre.name,
            kind = CatalogCollectionKind.GENRE,
            coverUrl = genre.pictureUrl
        ) {
            MetadataFetcher.searchTracksByGenre(genre.id, genre.name)
                .map { MetadataFetcher.toCatalogCandidate(it) }
        }
    }

    fun selectArtistForInspection(artistName: String) {
        val cleanArtist = artistName.trim()
        if (cleanArtist.isEmpty()) return
        val current = _catalogCollection.value
        val parent = if (current.isOpen && current.kind != CatalogCollectionKind.ARTIST) current else null
        val requestKey = "artist:$cleanArtist#${++catalogCollectionGeneration}"
        catalogCollectionJob?.cancel()
        onResetBatchPlaylistTarget()
        _catalogCollection.value = CatalogCollectionUiState(
            selectionKey = requestKey,
            title = cleanArtist,
            kind = CatalogCollectionKind.ARTIST,
            parent = parent,
            isLoading = true
        )
        catalogCollectionJob = scope.launch {
            val deezerHit = MetadataFetcher.searchDeezerArtist(cleanArtist)
            val albums = MetadataFetcher.fetchArtistAlbums(cleanArtist, deezerHit?.id)
            val topTracks = MetadataFetcher.fetchArtistTopTracks(cleanArtist, deezerHit?.id)
            val candidates = topTracks.map { MetadataFetcher.toCatalogCandidate(it) }
            val coverUrl = deezerHit?.pictureUrl ?: albums.firstOrNull()?.coverUrl
            updateCatalogCollection(requestKey) { state ->
                state.copy(
                    coverUrl = coverUrl,
                    candidates = candidates,
                    albums = albums,
                    isLoading = false
                )
            }
        }
    }

    fun updateCatalogCollection(
        selectionKey: String,
        transform: (CatalogCollectionUiState) -> CatalogCollectionUiState
    ): Boolean {
        while (true) {
            val current = _catalogCollection.value
            if (current.selectionKey != selectionKey) return false
            val updated = transform(current)
            if (updated == current) return true
            if (_catalogCollection.compareAndSet(current, updated)) return true
        }
    }

    private fun selectCollectionForInspection(
        selectionKey: String,
        title: String,
        kind: CatalogCollectionKind,
        coverUrl: String?,
        fetch: suspend () -> List<CatalogTrackCandidate>
    ) {
        val current = _catalogCollection.value
        val parent = if (current.isOpen && current.kind != kind) current else null
        val requestKey = "$selectionKey#${++catalogCollectionGeneration}"
        catalogCollectionJob?.cancel()
        onResetBatchPlaylistTarget()
        _catalogCollection.value = CatalogCollectionUiState(
            selectionKey = requestKey,
            title = title,
            kind = kind,
            coverUrl = coverUrl,
            parent = parent,
            isLoading = true
        )
        catalogCollectionJob = scope.launch {
            val candidates = fetch()
            updateCatalogCollection(requestKey) { state ->
                val resolvedCover = state.coverUrl ?: candidates.firstArtworkUri()
                state.copy(candidates = candidates, coverUrl = resolvedCover, isLoading = false)
            }
        }
    }

    suspend fun expandCandidates(
        query: String,
        current: List<OnlineCatalogTrack>
    ): List<OnlineCatalogTrack> {
        if (current.size > 1 || query.isBlank()) return current
        return YouTubeExtractor.searchYouTube(query).ifEmpty { current }
    }

    /**
     * Shared "Buscar otro" skeleton: expand YT matches → apply mutation → optional re-preview.
     * Callers keep domain-specific list updates in [apply].
     */
    fun launchCycleYouTubeMatch(
        query: String,
        current: List<OnlineCatalogTrack>,
        wasPreviewing: Boolean,
        apply: suspend (expanded: List<OnlineCatalogTrack>) -> OnlineCatalogTrack?
    ) {
        scope.launch {
            val expanded = expandCandidates(query, current)
            if (expanded.isEmpty()) return@launch
            val previewTrack = apply(expanded)
            if (wasPreviewing && previewTrack != null) {
                playOnlineCatalogTrackAsStream(previewTrack, false)
            }
        }
    }

    fun toggleTrackSelection(index: Int) {
        val collection = _catalogCollection.value
        val selectionKey = collection.selectionKey ?: return
        val list = collection.candidates.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            list[index] = item.copy(isSelected = !item.isSelected)
            updateCatalogCollection(selectionKey) { it.copy(candidates = list) }
        }
    }

    fun setAllTrackCandidatesSelection(selected: Boolean) {
        val collection = _catalogCollection.value
        val selectionKey = collection.selectionKey ?: return
        val list = collection.candidates.map { it.copy(isSelected = selected) }
        updateCatalogCollection(selectionKey) { it.copy(candidates = list) }
    }

    fun toggleAllTrackCandidatesSelection() {
        val collection = _catalogCollection.value
        val allSelected = collection.candidates.isNotEmpty() && collection.candidates.all { it.isSelected }
        setAllTrackCandidatesSelection(!allSelected)
    }

    fun clearSelectedCollection() {
        catalogCollectionJob?.cancel()
        catalogCollectionJob = null
        onResetBatchPlaylistTarget()
        val parent = _catalogCollection.value.parent
        if (parent != null) {
            _catalogCollection.value = parent
        } else {
            _catalogCollection.value = CatalogCollectionUiState()
        }
    }
}
