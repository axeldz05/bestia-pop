package com.bestiapop.android.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.PlaylistEntity
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.preferences.ActiveDownloadsStore
import com.bestiapop.android.data.preferences.IdentifyReviewStore
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import com.bestiapop.android.data.preferences.PersistedIdentifyReviewQueue
import com.bestiapop.android.data.preferences.PersistedQueueItem
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.PlaybackSessionStore
import com.bestiapop.android.data.preferences.QueueSnapshot
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.ui.theme.ThemePresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seed/verify phases for the LocalTransport host script. The destructive pm-clear boundary is never
 * executed by instrumentation and is gated to a disposable AOSP emulator by the host.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HostOrchestratedProcessDeathTest
class BackupRestoreE2ETest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase1_seedCloudBackupContract() = runBlocking {
        val dao = AppDatabase.getDatabase(context).musicDao()
        val songId = dao.insertSong(
            Song(
                uriString = "file:///backup-e2e/$ROOM_TITLE.wav",
                title = ROOM_TITLE,
                artist = FIXTURE_ARTIST,
                album = FIXTURE_ALBUM,
                durationMs = 12_345L
            )
        )
        check(songId > 0L) { "Could not seed Room backup fixture" }
        val playlistId = dao.insertPlaylist(
            PlaylistEntity(name = ROOM_PLAYLIST, description = "must not restore")
        )
        check(playlistId > 0L) { "Could not seed Room playlist fixture" }

        ThemePreferencesRepository(context).selectPreset(ThemePresets.SunsetGold.id)
        PlaybackPreferencesRepository(context).apply {
            setVolumeBoostEnabled(true)
            setVolumeBoostAmount(EXPECTED_BOOST)
            setStereoLeftGain(EXPECTED_LEFT_GAIN)
            setStereoRightGain(EXPECTED_RIGHT_GAIN)
            setAutoplayOnLaunch(true)
            setStreamSkipGraceSeconds(EXPECTED_GRACE_SECONDS)
        }
        LibraryPreferencesRepository(context).apply {
            setInitialScanCompleted(true)
            setHighestDbVersionSeen(AppDatabase.VERSION + 7)
        }
        PlaybackSessionStore(context).saveQueue(
            QueueSnapshot(
                currentIndex = 0,
                positionMs = 1_234L,
                items = listOf(
                    PersistedQueueItem.Local(
                        songId = songId,
                        uriString = "file:///backup-e2e/$ROOM_TITLE.wav",
                        identity = TrackIdentity(
                            title = SESSION_TITLE,
                            artist = FIXTURE_ARTIST
                        )
                    )
                )
            )
        )

        val catalogTrack = OnlineCatalogTrack(
            identity = TrackIdentity(
                title = EXCLUDED_TRACK_TITLE,
                artist = FIXTURE_ARTIST,
                album = FIXTURE_ALBUM
            ),
            id = "backup-e2e-catalog-id",
            provider = "BackupFixture"
        )
        ActiveDownloadsStore(context).save(
            listOf(
                ActiveDownload(
                    id = "backup-e2e-download",
                    source = ActiveDownloadSource.CATALOG,
                    candidates = listOf(catalogTrack),
                    state = CandidateDownloadState.ERROR,
                    errorMessage = "must not restore"
                )
            )
        )
        val candidate = IdentifyCandidate(
            track = catalogTrack,
            score = 0.75f,
            reasons = listOf("backup fixture")
        )
        IdentifyReviewStore(context).save(
            PersistedIdentifyReviewQueue(
                proposals = listOf(
                    IdentifyProposal(
                        songId = songId,
                        queryArtist = FIXTURE_ARTIST,
                        queryTitle = EXCLUDED_TRACK_TITLE,
                        candidates = listOf(candidate),
                        confidence = IdentifyConfidence.MEDIUM,
                        suggested = candidate
                    )
                )
            )
        )
        ListenBrainzPreferencesRepository(context).apply {
            setToken(LB_TOKEN)
            setEnabled(true)
        }

        assertEquals(ThemePresets.SunsetGold.id, ThemePreferencesRepository(context).selectedThemeFlow.first().id)
        assertTrue(PlaybackPreferencesRepository(context).settingsFlow.first().volumeBoostEnabled)
        assertTrue(LibraryPreferencesRepository(context).isInitialScanCompleted())
        assertTrue(PlaybackSessionStore(context).loadQueue() != null)
        assertTrue(ActiveDownloadsStore(context).load().isNotEmpty())
        assertTrue(IdentifyReviewStore(context).load().proposals.isNotEmpty())
        assertEquals(LB_TOKEN, ListenBrainzPreferencesRepository(context).settingsFlow.first().userToken)
    }

    @Test
    @HostOrchestratedProcessDeathTest
    fun phase2_verifyCloudBackupIncludesAndExcludes() = runBlocking {
        val theme = ThemePreferencesRepository(context).selectedThemeFlow.first()
        val playback = PlaybackPreferencesRepository(context).settingsFlow.first()
        assertEquals("Included theme preference was not restored", ThemePresets.SunsetGold.id, theme.id)
        assertTrue("Included playback setting was not restored", playback.volumeBoostEnabled)
        assertEquals(EXPECTED_BOOST, playback.volumeBoostAmount)
        assertEquals(EXPECTED_LEFT_GAIN, playback.stereoLeftGain)
        assertEquals(EXPECTED_RIGHT_GAIN, playback.stereoRightGain)
        assertTrue(playback.autoplayOnLaunch)
        assertEquals(EXPECTED_GRACE_SECONDS, playback.streamSkipGraceSeconds)

        val library = LibraryPreferencesRepository(context)
        assertFalse("Library scan marker must be excluded", library.isInitialScanCompleted())
        assertEquals("Library version marker must be excluded", 0, library.highestDbVersionSeen())
        assertNull("Playback session must be excluded", PlaybackSessionStore(context).loadQueue())
        assertTrue("Downloads must be excluded", ActiveDownloadsStore(context).load().isEmpty())
        assertTrue(
            "Identify review queue must be excluded",
            IdentifyReviewStore(context).load().proposals.isEmpty()
        )
        val listenBrainz = ListenBrainzPreferencesRepository(context).settingsFlow.first()
        assertFalse("ListenBrainz enabled state must be excluded with its credential", listenBrainz.enabled)
        assertTrue("ListenBrainz token must never restore", listenBrainz.userToken.isBlank())

        val dao = AppDatabase.getDatabase(context).musicDao()
        assertTrue(
            "Room songs must be excluded from cloud backup",
            dao.getAllSongs().none { it.title == ROOM_TITLE }
        )
        assertTrue(
            "Room playlists must be excluded from cloud backup",
            dao.getAllPlaylistsFlow().first().none { it.name == ROOM_PLAYLIST }
        )
    }

    private companion object {
        const val FIXTURE_ARTIST = "BestiaPop backup E2E"
        const val FIXTURE_ALBUM = "Cloud exclusion contract"
        const val ROOM_TITLE = "Room row must not restore"
        const val ROOM_PLAYLIST = "Room playlist must not restore"
        const val SESSION_TITLE = "Playback session must not restore"
        const val EXCLUDED_TRACK_TITLE = "DataStore queue must not restore"
        const val LB_TOKEN = "backup-e2e-secret-token"
        const val EXPECTED_BOOST = 0.37f
        const val EXPECTED_LEFT_GAIN = 0.41f
        const val EXPECTED_RIGHT_GAIN = 0.73f
        const val EXPECTED_GRACE_SECONDS = 17
    }
}
