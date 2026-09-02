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
import com.bestiapop.android.domain.util.KnownAlbumTrack
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.albumGroupKey
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
        val appliedFields = mutableListOf<IdentifyApplyFields>()
        val reviews = mutableListOf<Long>()
        val fixture = fixture(
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.HIGH) },
            apply = { songId, _, fields ->
                applied += songId
                appliedFields += fields
                IdentifyResult.Updated(songId)
            },
            appendReview = { proposals, _ -> reviews += proposals.map { it.songId } }
        )
        try {
            fixture.runtime.submit(listOf(song(10L))).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertEquals(listOf(10L), applied)
            assertEquals(1, appliedFields.size)
            assertFalse(appliedFields.single().title)
            assertTrue(appliedFields.single().artist)
            assertTrue(appliedFields.single().album)
            assertTrue(reviews.isEmpty())
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mediumMatch_flushesReviewAtEndOfBatch() = runBlocking {
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
            appendReview = { proposals, _ -> reviews += proposals.map { it.songId } }
        )
        try {
            fixture.runtime.submit(listOf(song(1L), song(2L)))
            withTimeout(TIMEOUT_MS) { enteredSecond.await() }
            assertTrue(reviews.isEmpty())
            assertTrue(fixture.work.single().remainingSongIds.contains(2L))

            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(setOf(1L, 2L), reviews.toSet())
            assertTrue(fixture.work.isEmpty())
        } finally {
            gate.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun workPersist_coalescesUntilBatchFinishes() = runBlocking {
        val saves = AtomicInteger(0)
        val fixture = fixture(
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.HIGH) },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) },
            onSaveWork = { saves.incrementAndGet() }
        )
        try {
            fixture.runtime.submit((1L..5L).map { song(it) }).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertTrue(saves.get() <= 3)
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reviewBuffer_flushesInBatchesNotPerSong() = runBlocking {
        val flushes = AtomicInteger(0)
        val reviews = mutableListOf<Long>()
        val fixture = fixture(
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.MEDIUM) },
            appendReview = { proposals, _ ->
                flushes.incrementAndGet()
                reviews += proposals.map { it.songId }
            }
        )
        try {
            fixture.runtime.submit((1L..8L).map { song(it) }).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals(8, reviews.toSet().size)
            assertTrue(flushes.get() < 8)
            assertTrue(flushes.get() >= 1)
        } finally {
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
            assertEquals(setOf(1L, 2L), processed.toSet())
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
            assertEquals(setOf(3L, 4L), processed.toSet())
            assertTrue(fixture.work.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun parallelWorkers_proposeEverySong() = runBlocking {
        val proposed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val fixture = fixture(
            propose = { song, _, _ ->
                proposed += song.id
                proposal(song.id, IdentifyConfidence.HIGH)
            },
            apply = { songId, _, _ -> IdentifyResult.Updated(songId) }
        )
        try {
            val songs = (20L..26L).map { song(it) }
            fixture.runtime.submit(songs).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }
            assertEquals((20L..26L).toSet(), proposed.toSet())
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

    @Test
    fun fillGapsOnly_appliesPlaceholderArtist_notRealAlbum() = runBlocking {
        val appliedFields = mutableListOf<IdentifyApplyFields>()
        val tagged = Song(
            id = 10L,
            uriString = "file://song-10.mp3",
            title = "Creep",
            artist = "Unknown Artist",
            album = "Pablo Honey",
            year = 1993,
            trackNumber = 2,
            artworkUri = "file:///cover.jpg"
        )
        val fixture = fixture(
            songsById = mapOf(10L to tagged),
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.HIGH) },
            apply = { songId, _, fields ->
                appliedFields += fields
                IdentifyResult.Updated(songId)
            }
        )
        try {
            fixture.runtime.submit(listOf(tagged), fillGapsOnly = true).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertEquals(1, appliedFields.size)
            assertTrue(appliedFields.single().artist)
            assertFalse(appliedFields.single().album)
            assertFalse(appliedFields.single().title)
            assertFalse(appliedFields.single().year)
            assertFalse(appliedFields.single().trackNumber)
            assertFalse(appliedFields.single().artwork)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun fillGapsOnly_highMatch_appliesWeakTitle() = runBlocking {
        val appliedFields = mutableListOf<IdentifyApplyFields>()
        val tagged = Song(
            id = 11L,
            uriString = "file://song-11.mp3",
            title = "01",
            artist = "Radiohead",
            album = "Pablo Honey",
            year = 1993,
            trackNumber = 2,
            artworkUri = "file:///cover.jpg"
        )
        val fixture = fixture(
            songsById = mapOf(11L to tagged),
            propose = { song, _, _ -> proposal(song.id, IdentifyConfidence.HIGH) },
            apply = { _, _, fields ->
                appliedFields += fields
                IdentifyResult.Updated(11L)
            }
        )
        try {
            fixture.runtime.submit(listOf(tagged), fillGapsOnly = true).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertTrue(appliedFields.single().title)
            assertFalse(appliedFields.single().artist)
            assertFalse(appliedFields.single().album)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun offline_pausesWithoutReviewOrPropose() = runBlocking {
        val proposes = AtomicInteger(0)
        val reviews = mutableListOf<Long>()
        val fixture = fixture(
            isOnline = { false },
            propose = { song, _, _ ->
                proposes.incrementAndGet()
                proposal(song.id, IdentifyConfidence.NONE)
            },
            appendReview = { proposals, _ -> reviews += proposals.map { it.songId } }
        )
        try {
            fixture.runtime.submit(listOf(song(5L))).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertEquals(0, proposes.get())
            assertTrue(reviews.isEmpty())
            assertEquals(listOf(5L), fixture.work.single().remainingSongIds)
            assertTrue(fixture.work.single().interrupted)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun highMatch_fansOutRemainingSiblingsWithoutPropose() = runBlocking {
        val proposed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val reviews = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        fun untitled(id: Long, title: String, durationMs: Long) = song(id).copy(
            title = title,
            artist = "Unknown Artist",
            album = "Unknown Album",
            durationMs = durationMs
        )
        val songs = listOf(
            untitled(1L, "Antes y Después", 210_000L),
            untitled(2L, "Míralo", 190_000L),
            untitled(3L, "Astros", 230_000L),
            untitled(4L, "Caminos", 180_000L),
            untitled(5L, "Barómetro", 200_000L)
        )
        val album = KnownAlbumTracks(
            key = albumGroupKey("Ciro y Los Persas", "Espejos"),
            artist = "Ciro y Los Persas",
            album = "Espejos",
            artworkUri = null,
            tracks = listOf(
                KnownAlbumTrack("Antes y Después", durationMs = 210_000L, trackNumber = 1),
                KnownAlbumTrack("Míralo", durationMs = 190_000L, trackNumber = 2),
                KnownAlbumTrack("Astros", durationMs = 230_000L, trackNumber = 3),
                KnownAlbumTrack("Caminos", durationMs = 180_000L, trackNumber = 4),
                KnownAlbumTrack("Barómetro", durationMs = 200_000L, trackNumber = 5)
            )
        )
        val seedApplied = CompletableDeferred<Unit>()
        val getSongCalls = AtomicInteger(0)
        val getSongsCalls = AtomicInteger(0)
        val fixture = fixture(
            songsById = songs.associateBy { it.id },
            getSong = { id ->
                getSongCalls.incrementAndGet()
                songs.firstOrNull { it.id == id }
            },
            getSongs = { ids ->
                getSongsCalls.incrementAndGet()
                val byId = songs.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            },
            propose = { song, _, _ ->
                proposed += song.id
                if (song.id != 1L) seedApplied.await()
                if (song.id == 1L) {
                    proposal(song.id, IdentifyConfidence.HIGH).copy(
                        suggested = IdentifyCandidate(
                            track = OnlineCatalogTrack(
                                id = "dz-espejos",
                                title = "Antes y Después",
                                artist = "Ciro y Los Persas",
                                album = "Espejos",
                                durationMs = 210_000L,
                                provider = "Deezer"
                            ),
                            score = 0.95f
                        )
                    )
                } else {
                    proposal(song.id, IdentifyConfidence.NONE)
                }
            },
            apply = { songId, _, _ ->
                applied += songId
                if (songId == 1L) seedApplied.complete(Unit)
                IdentifyResult.Updated(songId)
            },
            appendReview = { proposals, _ -> reviews += proposals.map { it.songId } },
            loadScopedAlbumTracks = { _, _ -> album }
        )
        try {
            fixture.runtime.submit(songs).join()
            withTimeout(TIMEOUT_MS) { fixture.runtime.awaitIdle() }

            assertTrue(proposed.contains(1L))
            assertTrue(applied.contains(1L))
            assertTrue(applied.contains(4L) || applied.contains(5L))
            assertTrue(getSongsCalls.get() >= 1)
            assertTrue(getSongCalls.get() <= ProcessIdentifyRuntime.IDENTIFY_PARALLEL + 1)
            assertTrue(proposed.size <= ProcessIdentifyRuntime.IDENTIFY_PARALLEL)
            assertTrue(reviews.none { it in applied })
            assertTrue(fixture.work.isEmpty())
        } finally {
            seedApplied.complete(Unit)
            fixture.close()
        }
    }

    private fun fixture(
        pendingIds: Set<Long> = emptySet(),
        initialWork: IdentifyWorkSnapshot? = null,
        songsById: Map<Long, Song> = emptyMap(),
        isOnline: () -> Boolean = { true },
        acquireExecutionLease: suspend () -> AutoCloseable = { AutoCloseable {} },
        getSong: (suspend (Long) -> Song?)? = null,
        getSongs: (suspend (List<Long>) -> List<Song>)? = null,
        propose: suspend (Song, Boolean, String?) -> IdentifyProposal,
        apply: suspend (Long, IdentifyProposal, IdentifyApplyFields) -> IdentifyResult = { _, _, _ ->
            IdentifyResult.NoMatch
        },
        appendReview: suspend (List<IdentifyProposal>, IdentifyApplyFields) -> Unit = { _, _ -> },
        onSaveWork: () -> Unit = {},
        loadScopedAlbumTracks: suspend (String, String) ->
            com.bestiapop.android.domain.util.KnownAlbumTracks? = { _, _ -> null }
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = mutableListOf<IdentifyWorkSnapshot>()
        if (initialWork != null) work += initialWork
        val runtime = ProcessIdentifyRuntime(
            scope = scope,
            dependencies = ProcessIdentifyRuntime.Dependencies(
                getSong = getSong ?: { id -> songsById[id] ?: song(id) },
                getSongs = getSongs,
                propose = propose,
                apply = apply,
                listenBrainzToken = { null },
                pendingSongIds = { pendingIds },
                appendReview = appendReview,
                loadWork = { work.lastOrNull() },
                saveWork = { snapshot ->
                    onSaveWork()
                    work.clear()
                    if (snapshot != null) work += snapshot
                },
                isOnline = isOnline,
                acquireExecutionLease = acquireExecutionLease,
                loadScopedAlbumTracks = loadScopedAlbumTracks
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
