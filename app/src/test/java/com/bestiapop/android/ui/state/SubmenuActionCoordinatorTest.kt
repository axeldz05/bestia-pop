package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.CatalogTrackCandidate
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmenuActionCoordinatorTest {

    private fun createCoordinator(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        addPlayableBatch: (List<PlayableItem>) -> Unit = {},
        playNextPlayableBatch: (List<PlayableItem>) -> Unit = {},
        startRadioForSong: (Song) -> Unit = {},
        startRadioForStream: () -> Unit = {},
        playPlayableCollection: (List<PlayableItem>, Int) -> Unit = { _, _ -> },
        searchCatalog: (String) -> Unit = {},
        navigateToDiscover: () -> Unit = {},
        toast: (String) -> Unit = {},
        getLibrarySongs: () -> List<Song> = { emptyList() },
        resolveAlbumArtwork: (Song) -> String? = { null },
        getLocalSongsByMatchKey: () -> Map<String, Song> = { emptyMap() },
        getAllSongsByMatchKey: () -> Map<String, Song> = { emptyMap() },
        getCatalogCollection: () -> CatalogCollectionUiState = { CatalogCollectionUiState() },
        findLocalSongFor: (TrackMeta) -> Song? = { null }
    ) = SubmenuActionCoordinator(
        scope = scope,
        addPlayableBatch = addPlayableBatch,
        playNextPlayableBatch = playNextPlayableBatch,
        startRadioForSong = startRadioForSong,
        startRadioForStream = startRadioForStream,
        playPlayableCollection = playPlayableCollection,
        searchCatalog = searchCatalog,
        navigateToDiscover = navigateToDiscover,
        toast = toast,
        getLibrarySongs = getLibrarySongs,
        resolveAlbumArtwork = resolveAlbumArtwork,
        getLocalSongsByMatchKey = getLocalSongsByMatchKey,
        getAllSongsByMatchKey = getAllSongsByMatchKey,
        getCatalogCollection = getCatalogCollection,
        findLocalSongFor = findLocalSongFor
    )

    @Test
    fun executeForTrack_playNext_invokesCallbackAndToasts() {
        var playedNextItems: List<PlayableItem>? = null
        var toastMessage: String? = null

        val coordinator = createCoordinator(
            playNextPlayableBatch = { playedNextItems = it },
            toast = { toastMessage = it }
        )

        val meta = TrackIdentity(
            title = "Get Lucky",
            artist = "Daft Punk",
            album = "RAM",
            durationMs = 240000L
        )

        coordinator.executeForTrack(SubmenuSwipeAction.PLAY_NEXT, meta)

        assertEquals(1, playedNextItems?.size)
        assertEquals("Se reproducirá a continuación", toastMessage)
    }

    @Test
    fun executeForTrack_enqueueAll_invokesCallbackAndToasts() {
        var enqueuedItems: List<PlayableItem>? = null
        var toastMessage: String? = null

        val coordinator = createCoordinator(
            addPlayableBatch = { enqueuedItems = it },
            toast = { toastMessage = it }
        )

        val meta = TrackIdentity(
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery"
        )

        coordinator.executeForTrack(SubmenuSwipeAction.ENQUEUE_ALL, meta)

        assertEquals(1, enqueuedItems?.size)
        assertEquals("Canción añadida a la cola", toastMessage)
    }

    @Test
    fun executeForTrack_addToPlaylist_invokesOnAddToPlaylistWithResolvedSong() {
        var addedSong: Song? = null
        val song = Song(
            id = 101L,
            uriString = "content://music/101",
            title = "Around The World",
            artist = "Daft Punk",
            album = "Homework",
            durationMs = 200000L
        )

        val coordinator = createCoordinator(
            findLocalSongFor = { song }
        )

        val meta = TrackIdentity(
            title = "Around The World",
            artist = "Daft Punk",
            album = "Homework"
        )

        coordinator.executeForTrack(
            SubmenuSwipeAction.ADD_TO_PLAYLIST,
            meta,
            onAddToPlaylist = { addedSong = it }
        )

        assertEquals(101L, addedSong?.id)
        assertEquals("Around The World", addedSong?.title)
    }

    @Test
    fun executeForDomainAlbum_playNext_findsSongsAndPlaysBatch() {
        var playedNextItems: List<PlayableItem>? = null
        var toastMessage: String? = null

        val song1 = Song(
            id = 1L,
            uriString = "content://music/1",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery"
        )
        val song2 = Song(
            id = 2L,
            uriString = "content://music/2",
            title = "Aerodynamic",
            artist = "Daft Punk",
            album = "Discovery"
        )

        val coordinator = createCoordinator(
            getLibrarySongs = { listOf(song1, song2) },
            playNextPlayableBatch = { playedNextItems = it },
            toast = { toastMessage = it }
        )

        val album = Album(
            name = "Discovery",
            artist = "Daft Punk",
            songCount = 2,
            year = 2001
        )

        coordinator.executeForAlbum(SubmenuSwipeAction.PLAY_NEXT, album)

        assertEquals(2, playedNextItems?.size)
        assertEquals("2 canciones se reproducirán a continuación", toastMessage)
    }

    @Test
    fun executeForCatalogAlbum_searchSimilar_searchesAndNavigates() {
        var searchedQuery: String? = null
        var navigatedToDiscover = false

        val candidate = CatalogTrackCandidate(
            identity = TrackIdentity(
                title = "Give Life Back to Music",
                artist = "Daft Punk",
                album = "Random Access Memories",
                durationMs = 274000L
            ),
            candidates = emptyList()
        )

        val coordinator = createCoordinator(
            searchCatalog = { searchedQuery = it },
            navigateToDiscover = { navigatedToDiscover = true },
            getCatalogCollection = {
                CatalogCollectionUiState(
                    selectionKey = "key1",
                    title = "Random Access Memories",
                    candidates = listOf(candidate)
                )
            }
        )

        val catalogAlbum = CatalogAlbum(
            id = "album-1",
            title = "Random Access Memories",
            artist = "Daft Punk",
            coverUrl = "https://example.com/cover.jpg"
        )

        coordinator.executeForAlbum(SubmenuSwipeAction.SEARCH_SIMILAR, catalogAlbum)

        assertEquals("Daft Punk", searchedQuery)
        assertTrue(navigatedToDiscover)
    }

    @Test
    fun executeForArtist_searchSimilar_searchesAndNavigates() {
        var searchedQuery: String? = null
        var navigatedToDiscover = false

        val coordinator = createCoordinator(
            searchCatalog = { searchedQuery = it },
            navigateToDiscover = { navigatedToDiscover = true }
        )

        coordinator.executeForArtist(SubmenuSwipeAction.SEARCH_SIMILAR, "Justice")

        assertEquals("Justice", searchedQuery)
        assertTrue(navigatedToDiscover)
    }

    @Test
    fun executeForPlayables_playNext_invokesCallback() {
        var playedItems: List<PlayableItem>? = null
        var toastMessage: String? = null

        val coordinator = createCoordinator(
            playNextPlayableBatch = { playedItems = it },
            toast = { toastMessage = it }
        )

        val song = Song(
            id = 42L,
            uriString = "content://music/42",
            title = "Genesis",
            artist = "Justice",
            album = "Cross",
            durationMs = 230000L
        )
        val playable = PlayableItem.Local(song)

        coordinator.executeForPlayables(SubmenuSwipeAction.PLAY_NEXT, listOf(playable))

        assertEquals(1, playedItems?.size)
        assertEquals(playable, playedItems?.first())
        assertEquals("Se reproducirá a continuación", toastMessage)
    }
}
