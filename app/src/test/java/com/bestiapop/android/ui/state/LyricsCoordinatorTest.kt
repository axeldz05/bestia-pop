package com.bestiapop.android.ui.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import com.bestiapop.android.data.preferences.LyricsPreferencesRepository
import com.bestiapop.android.testutil.FakeMusicRepository
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class LyricsCoordinatorTest {

    private fun testSong(id: Long, title: String = "Title", artist: String = "Artist", lyrics: String? = null): Song = Song(
        id = id,
        uriString = "file:///song_$id.mp3",
        title = title,
        artist = artist,
        album = "Album",
        lyrics = lyrics
    )

    private class TestLyricsRepo(
        var localLyrics: String? = null,
        var onlineLyrics: String? = null
    ) : FakeMusicRepository() {
        var updatedSongId: Long? = null
        var updatedLyrics: String? = null

        override suspend fun findLocalLyrics(song: Song): String? = localLyrics
        override suspend fun fetchSongLyrics(song: Song): String? = onlineLyrics
        override suspend fun updateSongLyrics(songId: Long, lyrics: String?) {
            updatedSongId = songId
            updatedLyrics = lyrics
        }
    }

    @Test
    fun ensureLyrics_whenLocalLyricsExist_updatesRepoAndRuntime() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = TemporaryPreferencesDataStore(context, "lyrics-test-1")
        val prefs = LyricsPreferencesRepository(storage.dataStore)
        val repo = TestLyricsRepo(localLyrics = "[00:01.00]Local lyrics line")

        var runtimeUpdatedSongId: Long? = null
        var runtimeUpdatedLyrics: String? = null

        val coordinator = LyricsCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repo,
            lyricsPreferences = prefs,
            updateCurrentSongLyrics = { id, l ->
                runtimeUpdatedSongId = id
                runtimeUpdatedLyrics = l
            },
            updateCurrentItemLyrics = {}
        )

        val song = testSong(42L)
        coordinator.ensureLyrics(song)

        assertEquals(42L, repo.updatedSongId)
        assertEquals("[00:01.00]Local lyrics line", repo.updatedLyrics)
        assertEquals(42L, runtimeUpdatedSongId)
        assertEquals("[00:01.00]Local lyrics line", runtimeUpdatedLyrics)
        assertFalse(coordinator.isFetching.value)
        assertNull(coordinator.fetchError.value)
    }

    @Test
    fun ensureLyrics_whenNoLyricsFound_setsFetchError() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = TemporaryPreferencesDataStore(context, "lyrics-test-2")
        val prefs = LyricsPreferencesRepository(storage.dataStore)
        val repo = TestLyricsRepo(localLyrics = null, onlineLyrics = null)

        val coordinator = LyricsCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repo,
            lyricsPreferences = prefs,
            updateCurrentSongLyrics = { _, _ -> },
            updateCurrentItemLyrics = {}
        )

        val song = testSong(99L)
        coordinator.ensureLyrics(song)

        assertEquals("No se encontró letra", coordinator.fetchError.value)
        assertFalse(coordinator.isFetching.value)

        coordinator.clearFetchError()
        assertNull(coordinator.fetchError.value)
    }

    @Test
    fun preferences_updateCorrectly() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = TemporaryPreferencesDataStore(context, "lyrics-test-3")
        val prefs = LyricsPreferencesRepository(storage.dataStore)
        val repo = TestLyricsRepo()

        val coordinator = LyricsCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repo,
            lyricsPreferences = prefs,
            updateCurrentSongLyrics = { _, _ -> },
            updateCurrentItemLyrics = {}
        )

        coordinator.setPhoneticGuideEnabled(false)
        coordinator.setJapanesePhoneticMode(JapanesePhoneticMode.HIRAGANA)
        coordinator.setAskBeforeGoogleTranslate(false)

        val settings = prefs.settingsFlow.first { !it.askBeforeGoogleTranslate }
        assertFalse(settings.phoneticGuideEnabled)
        assertEquals(JapanesePhoneticMode.HIRAGANA, settings.japanesePhoneticMode)
        assertFalse(settings.askBeforeGoogleTranslate)
    }

    @Test
    fun ensureRomanization_forLatinText_cachesEmptyListWithoutRemoteCall() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = TemporaryPreferencesDataStore(context, "lyrics-test-4")
        val prefs = LyricsPreferencesRepository(storage.dataStore)
        val repo = TestLyricsRepo()

        val coordinator = LyricsCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repo,
            lyricsPreferences = prefs,
            updateCurrentSongLyrics = { _, _ -> },
            updateCurrentItemLyrics = {}
        )

        // Latin only text
        coordinator.ensureRomanization(10L, listOf("Hello world", "Another line"))

        // Should return null for romanized lines since it's empty
        assertNull(coordinator.getRomanizedLines(10L))
        assertEquals(0, coordinator.translationState.value.romanizationVersion)
    }
}
