package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.TemporaryPreferencesDataStore
import com.bestiapop.android.ui.state.IdentifyReviewPhase
import com.bestiapop.android.ui.state.identifyReviewFromPersisted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class IdentifyReviewStoreIntegrationTest {

    @Test
    fun pendingQueue_hydratesHiddenWithoutCdnAndPrunesOrphansAcrossColdStarts() = runTest {
        val storage = TemporaryPreferencesDataStore(
            ApplicationProvider.getApplicationContext(),
            "identify-review"
        )
        try {
            val repository = IdentifyReviewStore(storage.dataStore)
            repository.save(
                PersistedIdentifyReviewQueue(
                    proposals = listOf(proposal(songId = 1L), proposal(songId = 2L)),
                    phase = IdentifyReviewPhase.Overview.name
                )
            )
            assertEquals(2, repository.queueFlow.first().proposals.size)

            storage.restart()

            val coldQueue = IdentifyReviewStore(storage.dataStore).load()
            val hydrated = identifyReviewFromPersisted(
                proposals = coldQueue.proposals,
                phaseName = coldQueue.phase,
                songs = listOf(
                    Song(
                        id = 2L,
                        uriString = "file:///library/hysteria.mp3",
                        title = "Hysteria",
                        artist = "Muse",
                        album = "Absolution"
                    )
                )
            )
            assertEquals(1, hydrated.pendingCount)
            assertEquals(2L, hydrated.current?.song?.id)
            assertEquals(IdentifyReviewPhase.Item, hydrated.phase)
            assertFalse(hydrated.isVisible)
            assertEquals("", hydrated.current?.proposal?.suggested?.track?.audioUrl)

            val prunedQueue = PersistedIdentifyReviewQueue(
                proposals = hydrated.remaining.map { it.proposal },
                phase = hydrated.phase.name
            )
            val prunedRepository = IdentifyReviewStore(storage.dataStore)
            prunedRepository.save(prunedQueue)
            assertEquals(listOf(2L), prunedRepository.queueFlow.first().proposals.map { it.songId })

            storage.restart()

            val restoredPruned = IdentifyReviewStore(storage.dataStore).queueFlow.first()
            assertEquals(listOf(2L), restoredPruned.proposals.map { it.songId })
            assertEquals("", restoredPruned.proposals.single().suggested?.track?.audioUrl)
        } finally {
            storage.close()
        }
    }

    private fun proposal(songId: Long): IdentifyProposal {
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                id = "deezer-$songId",
                title = "Hysteria",
                artist = "Muse",
                album = "Absolution",
                audioUrl = "https://cdn.example/ephemeral-$songId",
                provider = "Deezer"
            ),
            score = 0.78f,
            reasons = listOf("metadata match")
        )
        return IdentifyProposal(
            songId = songId,
            queryArtist = "Muse",
            queryTitle = "Hysteria",
            candidates = listOf(candidate),
            confidence = IdentifyConfidence.MEDIUM,
            suggested = candidate
        )
    }
}
