package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.model.toPlayableItems
import com.bestiapop.android.data.network.MetadataFetcher
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Coordinator for executing submenu and swipe-left quick actions ([SubmenuSwipeAction])
 * across songs, catalog candidates, albums, and artists.
 * Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean while preserving its UI facade API.
 */
class SubmenuActionCoordinator(
    private val scope: CoroutineScope,
    private val addPlayableBatch: (List<PlayableItem>) -> Unit,
    private val playNextPlayableBatch: (List<PlayableItem>) -> Unit,
    private val startRadioForSong: (Song) -> Unit,
    private val startRadioForStream: () -> Unit,
    private val playPlayableCollection: (List<PlayableItem>, Int) -> Unit,
    private val searchCatalog: (String) -> Unit,
    private val navigateToDiscover: () -> Unit,
    private val toast: (String) -> Unit,
    private val getLibrarySongs: () -> List<Song>,
    private val resolveAlbumArtwork: (Song) -> String?,
    private val getLocalSongsByMatchKey: () -> Map<String, Song>,
    private val getAllSongsByMatchKey: () -> Map<String, Song>,
    private val getCatalogCollection: () -> CatalogCollectionUiState,
    private val findLocalSongFor: (TrackMeta) -> Song?
) {
    private fun enqueueToastMessage(count: Int): String =
        if (count == 1) "Canción añadida a la cola" else "$count canciones añadidas a la cola"

    private fun playNextToastMessage(count: Int): String =
        if (count == 1) "Se reproducirá a continuación" else "$count canciones se reproducirán a continuación"

    fun executeForPlayables(
        action: SubmenuSwipeAction,
        items: List<PlayableItem>,
        onAddToPlaylist: ((List<PlayableItem>) -> Unit)? = null
    ) {
        if (items.isEmpty() || action == SubmenuSwipeAction.DISABLED) return
        when (action) {
            SubmenuSwipeAction.ENQUEUE_ALL -> {
                addPlayableBatch(items)
                toast(enqueueToastMessage(items.size))
            }
            SubmenuSwipeAction.PLAY_NEXT -> {
                playNextPlayableBatch(items)
                toast(playNextToastMessage(items.size))
            }
            SubmenuSwipeAction.START_RADIO -> {
                val seed = items.firstOrNull()
                if (seed != null) {
                    if (seed is PlayableItem.Local) {
                        startRadioForSong(seed.song)
                    } else {
                        val local = TrackMatchKeys.lookupLocalSong(getAllSongsByMatchKey(), seed)
                        if (local != null) {
                            startRadioForSong(local)
                        } else {
                            playPlayableCollection(listOf(seed), 0)
                            startRadioForStream()
                        }
                    }
                    val artist = seed.artist
                    toast(if (artist.isNotBlank()) "Iniciando radio de $artist" else "Iniciando radio")
                }
            }
            SubmenuSwipeAction.SEARCH_SIMILAR -> {
                val artist = items.firstOrNull()?.artist.orEmpty()
                if (artist.isNotBlank()) {
                    searchCatalog(artist)
                    navigateToDiscover()
                }
            }
            SubmenuSwipeAction.ADD_TO_PLAYLIST -> {
                onAddToPlaylist?.invoke(items)
            }
            SubmenuSwipeAction.DISABLED -> Unit
        }
    }

    fun executeForSongs(
        action: SubmenuSwipeAction,
        songs: List<Song>,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) {
        if (songs.isEmpty() || action == SubmenuSwipeAction.DISABLED) return
        if (action == SubmenuSwipeAction.ADD_TO_PLAYLIST) {
            onAddToPlaylist?.invoke(songs)
            return
        }
        val playables = songs.toPlayableItems { resolveAlbumArtwork(it) }
        executeForPlayables(action, playables)
    }

    fun executeForCandidates(
        action: SubmenuSwipeAction,
        candidates: List<CatalogTrackCandidate>,
        onAddToPlaylist: ((List<CatalogTrackCandidate>) -> Unit)? = null
    ) {
        if (candidates.isEmpty() || action == SubmenuSwipeAction.DISABLED) return
        if (action == SubmenuSwipeAction.ADD_TO_PLAYLIST) {
            onAddToPlaylist?.invoke(candidates)
            return
        }
        val playables = candidates.toPlayableItems(getLocalSongsByMatchKey())
        executeForPlayables(action, playables)
    }

    fun executeForTrack(
        action: SubmenuSwipeAction,
        track: TrackMeta,
        onAddToPlaylist: ((Song) -> Unit)? = null
    ) {
        if (action == SubmenuSwipeAction.DISABLED) return
        val local = if (track is Song) track else findLocalSongFor(track)
        if (action == SubmenuSwipeAction.ADD_TO_PLAYLIST) {
            if (local != null) {
                onAddToPlaylist?.invoke(local)
            } else {
                toast("Descarga la canción para añadirla a playlists")
            }
            return
        }
        val playable = PlayableItem.fromLibraryOrRemote(local, track.toIdentity())
        executeForPlayables(action, listOf(playable))
    }

    fun executeForAlbum(
        action: SubmenuSwipeAction,
        album: CatalogAlbum,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) {
        executeForAlbum(
            action = action,
            albumTitle = album.title,
            artistName = album.artist,
            albumId = album.id,
            coverUrl = album.coverUrl,
            onAddToPlaylist = onAddToPlaylist
        )
    }

    fun executeForAlbum(
        action: SubmenuSwipeAction,
        album: Album,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) {
        executeForAlbum(
            action = action,
            albumTitle = album.name,
            artistName = album.artist,
            coverUrl = album.artworkUri,
            onAddToPlaylist = onAddToPlaylist
        )
    }

    fun executeForAlbum(
        action: SubmenuSwipeAction,
        albumTitle: String,
        artistName: String = "",
        albumId: String = "",
        coverUrl: String? = null,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) {
        if (action == SubmenuSwipeAction.DISABLED) return
        val cleanTitle = albumTitle.trim()
        if (cleanTitle.isEmpty()) return

        val localSongs = getLibrarySongs().filter { it.album.equals(cleanTitle, ignoreCase = true) }
        if (localSongs.isNotEmpty()) {
            executeForSongs(action, localSongs, onAddToPlaylist)
            return
        }

        val currentCollection = getCatalogCollection()
        if (currentCollection.isOpen && currentCollection.title.equals(cleanTitle, ignoreCase = true) && currentCollection.candidates.isNotEmpty()) {
            executeForCandidates(action, currentCollection.candidates)
            return
        }

        scope.launch {
            val candidates = MetadataFetcher.fetchAlbumTrackCandidates(albumId, cleanTitle, artistName, coverUrl)
            if (candidates.isNotEmpty()) {
                executeForCandidates(action, candidates)
            }
        }
    }

    fun executeForArtist(
        action: SubmenuSwipeAction,
        artistName: String,
        onAddToPlaylist: ((List<Song>) -> Unit)? = null
    ) {
        if (action == SubmenuSwipeAction.DISABLED) return
        val cleanArtist = artistName.trim()
        if (cleanArtist.isEmpty()) return

        if (action == SubmenuSwipeAction.SEARCH_SIMILAR) {
            searchCatalog(cleanArtist)
            navigateToDiscover()
            return
        }

        val localSongs = getLibrarySongs().filter { it.artist.equals(cleanArtist, ignoreCase = true) }
        if (localSongs.isNotEmpty()) {
            executeForSongs(action, localSongs, onAddToPlaylist)
            return
        }

        if (action == SubmenuSwipeAction.START_RADIO) {
            startRadioForStream()
            toast("Iniciando radio de $cleanArtist")
            return
        }

        val currentCollection = getCatalogCollection()
        if (currentCollection.isOpen && currentCollection.title.equals(cleanArtist, ignoreCase = true) && currentCollection.candidates.isNotEmpty()) {
            executeForCandidates(action, currentCollection.candidates)
            return
        }

        scope.launch {
            val deezerHit = MetadataFetcher.searchDeezerArtist(cleanArtist)
            val topTracks = MetadataFetcher.fetchArtistTopTracks(cleanArtist, deezerHit?.id)
            val candidates = topTracks.map { MetadataFetcher.toCatalogCandidate(it) }
            if (candidates.isNotEmpty()) {
                executeForCandidates(action, candidates)
            }
        }
    }
}
