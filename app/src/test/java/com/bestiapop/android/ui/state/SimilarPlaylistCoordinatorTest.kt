package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.domain.radio.RadioEngine
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.usecase.BuildSimilarPlaylistPreviewUseCase
import com.bestiapop.android.testutil.FakeMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarPlaylistCoordinatorTest {

    private fun testSong(id: Long, title: String, artist: String): Song = Song(
        id = id,
        uriString = "content://music/$id",
        title = title,
        artist = artist,
        album = "Album"
    )

    private fun createCoordinator(
        isOnline: Boolean = true,
        preferredMode: RadioMode = RadioMode.BOTH
    ): SimilarPlaylistCoordinator {
        val radioEngine = RadioEngine()
        val repository = FakeMusicRepository()
        val useCase = BuildSimilarPlaylistPreviewUseCase(radioEngine, repository)

        return SimilarPlaylistCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            useCase = useCase,
            isNetworkOnline = { isOnline },
            resolvePreferredRadioMode = { mode, _ -> mode ?: preferredMode },
            getListenBrainzSettings = { ListenBrainzSettings() },
            getAllSongs = { emptyList() },
            onPlaylistCreated = { _, _, _, _ -> },
            playPlayableCollection = {},
            addPlayableBatch = {},
            toast = {}
        )
    }

    @Test
    fun open_initializesStateWithLoadingAndSeeds() {
        val coordinator = createCoordinator(isOnline = true, preferredMode = RadioMode.BOTH)

        val song = testSong(1L, "Harder Better Faster", "Daft Punk")
        val playable = PlayableItem.Local(song)

        coordinator.open(listOf(playable), RadioMode.BOTH)

        val state = coordinator.state.value
        assertNotNull(state)
        assertEquals(1, state?.seedCount)
        assertEquals("Similares · Daft Punk", state?.playlistName)
        assertEquals(RadioMode.BOTH, state?.mode)
    }

    @Test
    fun toggleItem_addsAndRemovesKeys() {
        val coordinator = createCoordinator(isOnline = false, preferredMode = RadioMode.KNOWN)

        val song = testSong(1L, "One More Time", "Daft Punk")
        coordinator.open(listOf(PlayableItem.Local(song)))

        // Toggle key1
        coordinator.toggleItem("item-1")
        assertTrue(coordinator.state.value?.selectedKeys?.contains("item-1") == true)

        // Toggle key1 again -> removes it
        coordinator.toggleItem("item-1")
        assertFalse(coordinator.state.value?.selectedKeys?.contains("item-1") == true)
    }

    @Test
    fun setPlaylistName_updatesState() {
        val coordinator = createCoordinator(isOnline = true, preferredMode = RadioMode.BOTH)

        val song = testSong(1L, "Aerodynamic", "Daft Punk")
        coordinator.open(listOf(PlayableItem.Local(song)))
        coordinator.setPlaylistName("My Awesome Mix")

        assertEquals("My Awesome Mix", coordinator.state.value?.playlistName)
    }

    @Test
    fun dismiss_clearsState() {
        val coordinator = createCoordinator(isOnline = true, preferredMode = RadioMode.BOTH)

        val song = testSong(1L, "Da Funk", "Daft Punk")
        coordinator.open(listOf(PlayableItem.Local(song)))
        assertNotNull(coordinator.state.value)

        coordinator.dismiss()
        assertNull(coordinator.state.value)
    }
}
