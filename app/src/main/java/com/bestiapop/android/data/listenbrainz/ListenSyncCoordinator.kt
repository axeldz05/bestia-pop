package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.db.PendingListenDao
import com.bestiapop.android.data.db.PendingListenEntity
import com.bestiapop.android.data.network.ListenBrainzClient
import com.bestiapop.android.data.network.ListenPayload
import com.bestiapop.android.data.network.SubmitListensResult
import com.bestiapop.android.data.preferences.ListenBrainzPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ListenSyncCoordinator(
    private val scope: CoroutineScope,
    private val pendingListenDao: PendingListenDao,
    private val preferences: ListenBrainzPreferencesRepository,
    private val isOnline: () -> Boolean
) {
    private val mutex = Mutex()
    private var syncJob: Job? = null

    fun requestSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            mutex.withLock {
                drainQueue()
            }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            if (!isOnline()) return

            val settings = preferences.settingsFlow.first()
            if (!settings.enabled || settings.userToken.isBlank()) return

            val batch = pendingListenDao.getOldest(BATCH_SIZE)
            if (batch.isEmpty()) return

            // Skip exhausted rows so they don't block the queue forever.
            val eligible = batch.filter { it.attempts < MAX_ATTEMPTS }
            if (eligible.isEmpty()) {
                pendingListenDao.deleteExhausted(MAX_ATTEMPTS)
                continue
            }

            val payloads = eligible.map { it.toPayload() }
            when (val result = ListenBrainzClient.submitListens(settings.userToken, payloads)) {
                is SubmitListensResult.Success -> {
                    pendingListenDao.deleteByIds(eligible.map { it.id })
                    preferences.setLastSyncAt(System.currentTimeMillis())
                    val remaining = result.rateLimitRemaining
                    if (remaining != null && remaining <= 1) {
                        delay(((result.rateLimitResetInSec ?: 30).coerceAtLeast(1)) * 1000L)
                    } else {
                        delay(BATCH_DELAY_MS)
                    }
                }
                is SubmitListensResult.RateLimited -> {
                    delay(result.resetInSec.coerceAtLeast(1) * 1000L)
                }
                is SubmitListensResult.Failure -> {
                    pendingListenDao.incrementAttempts(
                        ids = eligible.map { it.id },
                        error = result.message
                    )
                    if (result.isNetworkError) return
                    // Soft-fail server errors: brief pause then continue with next attempt cycle.
                    delay(BATCH_DELAY_MS)
                    if (result.rateLimitRemaining != null && result.rateLimitRemaining <= 0) {
                        delay(((result.rateLimitResetInSec ?: 30).coerceAtLeast(1)) * 1000L)
                    }
                }
            }
        }
    }

    private fun PendingListenEntity.toPayload() = ListenPayload(
        listenedAt = listenedAt,
        trackName = trackName,
        artistName = artistName,
        releaseName = releaseName,
        durationMs = durationMs
    )

    companion object {
        private const val BATCH_SIZE = 5
        private const val BATCH_DELAY_MS = 2_000L
        private const val MAX_ATTEMPTS = 10
    }
}
