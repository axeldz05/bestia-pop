package com.bestiapop.android.data.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class LibraryPreferencesRepositoryPersistenceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun navigationSnapshot_survivesRepositoryRecreationAndClearsStaleDetailIds() = runTest {
        val firstRepository = LibraryPreferencesRepository(context)
        val expected = UiNavSnapshot(
            navIndex = NAV_PLAYLISTS,
            browseFilterName = "ARTISTS",
            libraryArtistName = "Massive Attack",
            libraryAlbumName = "Mezzanine",
            playlistDetailKind = PLAYLIST_DETAIL_LOCAL,
            playlistLocalId = 42L
        )

        firstRepository.setNavSnapshot(expected)

        assertEquals(
            expected,
            LibraryPreferencesRepository(context).navSnapshotFlow.first()
        )

        firstRepository.setNavSnapshot(
            UiNavSnapshot(
                navIndex = NAV_LIBRARY,
                browseFilterName = "RECENT",
                playlistDetailKind = PLAYLIST_DETAIL_CF
            )
        )
        val replaced = LibraryPreferencesRepository(context).navSnapshotFlow.first()

        assertEquals(NAV_LIBRARY, replaced.navIndex)
        assertEquals("RECENT", replaced.browseFilterName)
        assertEquals(PLAYLIST_DETAIL_CF, replaced.playlistDetailKind)
        assertNull(replaced.playlistLocalId)
        assertNull(replaced.playlistLbMbid)
    }

    @Test
    fun downgradeHighWaterMark_survivesRepositoryRecreation() = runTest {
        val newerVersion = AppDatabase.VERSION + 1
        LibraryPreferencesRepository(context).setHighestDbVersionSeen(newerVersion)

        val restored = LibraryPreferencesRepository(context).highestDbVersionSeen()

        assertEquals(newerVersion, restored)
        assertTrue(restored > AppDatabase.VERSION)
    }

    @Test
    fun fastScrollSettings_survivesRepositoryRecreation() = runTest {
        val repo = LibraryPreferencesRepository(context)
        val defaultSettings = repo.fastScrollSettingsFlow.first()
        assertEquals(true, defaultSettings.enabled)
        assertEquals(FastScrollSide.RIGHT, defaultSettings.side)

        repo.setFastScrollEnabled(false)
        repo.setFastScrollSide(FastScrollSide.LEFT)

        val reloaded = LibraryPreferencesRepository(context).fastScrollSettingsFlow.first()
        assertEquals(false, reloaded.enabled)
        assertEquals(FastScrollSide.LEFT, reloaded.side)
    }

    @Test
    fun submenuGestureSettings_survivesRepositoryRecreation() = runTest {
        val repo = LibraryPreferencesRepository(context)
        val defaultSettings = repo.submenuGestureSettingsFlow.first()
        assertEquals(true, defaultSettings.swipeBackEnabled)
        assertEquals(SubmenuSwipeAction.ENQUEUE_ALL, defaultSettings.swipeLeftAction)

        repo.setSubmenuSwipeBackEnabled(false)
        repo.setSubmenuSwipeLeftAction(SubmenuSwipeAction.START_RADIO)

        val reloaded = LibraryPreferencesRepository(context).submenuGestureSettingsFlow.first()
        assertEquals(false, reloaded.swipeBackEnabled)
        assertEquals(SubmenuSwipeAction.START_RADIO, reloaded.swipeLeftAction)
    }
}
