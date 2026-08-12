package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.db.PendingListenEntity
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.data.network.SubmitListensResult
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenSyncCoordinatorTest {

    @Test
    fun offline_doesNotSubmit() = runBlocking {
        val dao = FakePendingListenDao(
            listOf(pending(id = 1L, title = "A", artist = "B"))
        )
        val submits = mutableListOf<List<ListenPayload>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sync = ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = true, userToken = "tok")
                ),
                isOnline = { false },
                submitListens = { _, listens ->
                    submits.add(listens)
                    SubmitListensResult.Success(null, null)
                }
            )
            sync.requestSync()
            assertTrue(submits.isEmpty())
            assertEquals(1, dao.rows.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disabledOrBlankToken_doesNotSubmit() = runBlocking {
        val dao = FakePendingListenDao(listOf(pending(1L, "A", "B")))
        val submits = mutableListOf<List<ListenPayload>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = false, userToken = "tok")
                ),
                isOnline = { true },
                submitListens = { _, listens ->
                    submits.add(listens)
                    SubmitListensResult.Success(null, null)
                }
            ).requestSync()
            assertTrue(submits.isEmpty())

            ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = true, userToken = "  ")
                ),
                isOnline = { true },
                submitListens = { _, listens ->
                    submits.add(listens)
                    SubmitListensResult.Success(null, null)
                }
            ).requestSync()
            assertTrue(submits.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun success_deletesBatchAndRecordsLastSync() = runBlocking {
        val dao = FakePendingListenDao(
            listOf(
                pending(1L, "One", "Artist"),
                pending(2L, "Two", "Artist")
            )
        )
        var lastSync: Long? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sync = ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = true, userToken = "tok")
                ),
                isOnline = { true },
                submitListens = { token, listens ->
                    assertEquals("tok", token)
                    assertEquals(2, listens.size)
                    SubmitListensResult.Success(rateLimitRemaining = 10, rateLimitResetInSec = null)
                },
                setLastSyncAt = { lastSync = it }
            )
            sync.requestSync()
            withTimeout(2_000) {
                while (dao.rows.isNotEmpty()) delay(10)
            }
            assertTrue(dao.rows.isEmpty())
            assertTrue(lastSync != null && lastSync!! > 0L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun networkFailure_incrementsAttemptsAndStops() = runBlocking {
        val dao = FakePendingListenDao(listOf(pending(1L, "One", "Artist")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = true, userToken = "tok")
                ),
                isOnline = { true },
                submitListens = { _, _ ->
                    SubmitListensResult.Failure("offline", isNetworkError = true)
                }
            ).requestSync()
            withTimeout(2_000) {
                while (dao.rows.single().attempts < 1) delay(10)
            }
            assertEquals(1, dao.rows.single().attempts)
            assertEquals("offline", dao.rows.single().lastError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exhaustedRows_areDeletedWithoutSubmit() = runBlocking {
        val dao = FakePendingListenDao(
            listOf(pending(1L, "Old", "Artist", attempts = ListenSyncCoordinator.MAX_ATTEMPTS))
        )
        var submitted = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ListenSyncCoordinator(
                scope = scope,
                pendingListenDao = dao,
                settingsFlow = MutableStateFlow(
                    ListenBrainzSettings(enabled = true, userToken = "tok")
                ),
                isOnline = { true },
                submitListens = { _, _ ->
                    submitted = true
                    SubmitListensResult.Success(null, null)
                }
            ).requestSync()
            withTimeout(2_000) {
                while (dao.rows.isNotEmpty()) delay(10)
            }
            assertTrue(dao.rows.isEmpty())
            assertTrue(!submitted)
        } finally {
            scope.cancel()
        }
    }

    private fun pending(
        id: Long,
        title: String,
        artist: String,
        attempts: Int = 0
    ) = PendingListenEntity(
        id = id,
        listenedAt = 100L + id,
        trackName = title,
        artistName = artist,
        attempts = attempts
    )

    private class FakePendingListenDao(
        initial: List<PendingListenEntity>
    ) : PendingListenDao {
        val rows = initial.toMutableList()

        override suspend fun insert(listen: PendingListenEntity): Long {
            val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
            rows.add(listen.copy(id = id))
            return id
        }

        override suspend fun getOldest(limit: Int): List<PendingListenEntity> =
            rows.sortedBy { it.listenedAt }.take(limit)

        override suspend fun deleteByIds(ids: List<Long>) {
            rows.removeAll { it.id in ids }
        }

        override fun countFlow(): Flow<Int> = emptyFlow()

        override suspend fun count(): Int = rows.size

        override suspend fun incrementAttempts(ids: List<Long>, error: String?) {
            for (i in rows.indices) {
                val row = rows[i]
                if (row.id in ids) {
                    rows[i] = row.copy(attempts = row.attempts + 1, lastError = error)
                }
            }
        }

        override suspend fun deleteExhausted(maxAttempts: Int) {
            rows.removeAll { it.attempts >= maxAttempts }
        }
    }
}
