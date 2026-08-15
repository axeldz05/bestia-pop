package com.bestiapop.android.service

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.LibraryJobKind
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.IdentifyWorkSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessIdentifyRuntimeTest {

    @Test
    fun submit_skipsPendingAndNoopsWhenNothingToProcess() = runBlocking {
        val proposes = AtomicInteger(0)
        val fixture = fixture(
            pendingIds = setOf(1L, 2L),
            propose = { song, _, _ ->
                proposes.incrementAndGet()
                proposal(song.id, IdentifyConfidence.HIGH)
            }
        )
        try {
            fixture.runtime.submit(listOf(song(1L), song(2L))).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertEquals(0, proposes.get())
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun highMatch_appliesAndDoesNotEnqueueReview() = runBlocking {
        val applied = mutableListOf<Long>()
        val reviews = mutableListOf<Long>()
        val fixture = fixture(
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.HIGH) },
            apply = { songId, _, _ ->
                applied += songId
                IdentifyResult.Updated(songId)
            },
            appendReview = { proposal, _ -> reviews += proposal.songId }
        )
        try {
            fixture.runtime.submit(listOf(song(10L))).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertEquals(listOf(10L), applied)
            assertTrue(reviews.isEmpty())
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mediumMatch_appendsReviewIncrementally() = runBlocking {
        val reviews = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val enteredSecond = CompletableDeferred<Unit>()
        val fixture = fixture(
            propose = { song, _, _ ->
                if (song.id == 2L) {
                    enteredSecond.complete(Unit)
                    gate.await()
                }
                proposal(song.id, IdentifyConfidence.MEDIUM)
            },
            appendReview = { proposal, _ -> reviews += proposal.songId }
        )
        try {
            fixture.runtime.submit(listOf(song(1L), song(2L)))
            withTimeout(TIMEOUT_MS) { enteredSecond.await() }
            assertEquals(listOf(1L), reviews)
            assertEquals(listOf(2L), fixture.work.single().remainingSongIds)

            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(listOf(1L, 2L), reviews)
            assertTrue(fixture.work.isEmpty())
        } finally {
            gate.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun submit_appendsToRunningBatch() = runBlocking {
        val processed = mutableListOf<Long>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val fixture = fixture(
            propose = { song, _, _ ->
                processed += song.id
                if (song.id == 1L) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                proposal(song.id, IdentifyConfidence.HIGH)
            },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) }
        )
        try {
            fixture.runtime.submit(listOf(song(1L)))
            withTimeout(TIMEOUT_MS) { firstEntered.await() }
            fixture.runtime.submit(listOf(song(2L)))
            assertEquals(listOf(1L, 2L), fixture.work.single().remainingSongIds)
            releaseFirst.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(listOf(1L, 2L), processed)
        } finally {
            releaseFirst.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun resumeInterrupted_continuesRemaining() = runBlocking {
        val processed = mutableListOf<Long>()
        val fixture = fixture(
            initialWork = IdentifyWorkSnapshot(
                remainingSongIds = listOf(3L, 4L),
                force = true,
                totalCount = 2,
                interrupted = true
            ),
            propose = { song, force, _ ->
                processed += song.id
                assertTrue(force)
                proposal(song.id, IdentifyConfidence.HIGH)
            },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) }
        )
        try {
            fixture.runtime.resumeInterrupted().join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(listOf(3L, 4L), processed)
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun waitsForPlatformExecutionLease() = runBlocking {
        val leaseRequested = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val proposes = AtomicInteger(0)
        val fixture = fixture(
            acquireExecutionLease = {
                leaseRequested.complete(Unit)
                releaseLease.await()
                AutoCloseable {}
            },
            propose = { song, _, _ ->
                proposes.incrementAndGet()
                proposal(song.id, IdentifyConfidence.HIGH)
            },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) }
        )
        try {
            val job = fixture.runtime.submit(listOf(song(8L)))
            withTimeout(TIMEOUT_MS) { leaseRequested.await() }
            assertEquals(0, proposes.get())
            releaseLease.complete(Unit)
            job.join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(1, proposes.get())
        } finally {
            releaseLease.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun progress_isIdentifyKindWhileRunning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            propose = { song, _, _ ->
                entered.complete(Unit)
                release.await()
                proposal(song.id, IdentifyConfidence.HIGH)
            },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) }
        )
        try {
            fixture.runtime.submit(listOf(song(9L), song(10L)))
            withTimeout(TIMEOUT_MS) { entered.await() }
            val progress = fixture.runtime.progress.value
            assertEquals(LibraryJobKind.IDENTIFY, progress?.kind)
            assertEquals(2, progress?.total)
            release.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertNull(fixture.runtime.progress.value)
            assertFalse(fixture.runtime.running.value)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    private fun fixture(
        pendingIds: Set<Long> = emptySet(),
        initialWork: IdentifyWorkSnapshot? = null,
        acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} },
        propose: suspend (Song, Boolean, String?) -> IdentifyProposal,
        apply: suspend (Long, IdentifyProposal, IdentifyApplyFields) -> IdentifyResult = { _, _, _ ->
            IdentifyResult.NoMatch
        },
        appendReview: suspend (IdentifyProposal, IdentifyApplyFields) -> Unit = { _, _ -> }
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = mutableListOf<IdentifyWorkSnapshot>()
        if (initialWork != null) work += initialWork
        val songs = mutableMapOf<Long, Song>()
        val runtime = ProcessIdentifyRuntime(
            scope = scope,
            dependencies = ProcessIdentifyRuntime.Dependencies(
                getSong = { id -> songs[id] ?: song(id).also { songs[id] = it } },
                propose = propose,
                apply = apply,
                listenBrainzToken = { null },
                pendingSongIds = { pendingIds },
                appendReview = appendReview,
                loadWork = { work.lastOrNull() },
                saveWork = { snapshot ->
                    work.clear()
                    if (snapshot != null) work += snapshot
                },
                acquireExecutionLease = acquireExecutionLease
            )
        )
        return Fixture(scope, runtime, work)
    }

    private class Fixture(
        val scope: CoroutineScope,
        val runtime: ProcessIdentifyRuntime,
        val work: MutableList<IdentifyWorkSnapshot>
    ) {
        fun close() {
            scope.cancel()
        }
    }

    private fun song(id: Long) = Song(
        id = id,
        uriString = "file://song-$id.mp3",
        title = "Title $id",
        artist = "Unknown Artist",
        album = "Unknown Album"
    )

    private fun proposal(songId: Long, confidence: IdentifyConfidence): IdentifyProposal {
        val candidate = IdentifyCandidate(
            track = OnlineCatalogTrack(
                id = "dz-$songId",
                title = "Title $songId",
                artist = "Artist $songId",
                album = "Album $songId",
                provider = "Deezer"
            ),
            score = if (confidence == IdentifyConfidence.HIGH) 0.95f else 0.6f
        )
        return IdentifyProposal(
            songId = songId,
            queryArtist = "Unknown Artist",
            queryTitle = "Title $songId",
            candidates = listOf(candidate),
            confidence = confidence,
            suggested = candidate
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
