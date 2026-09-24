package com.bestiapop.android.service

import com.bestiapop.android.data.listenbrainz.SaveWhileListeningEvent
import com.bestiapop.android.data.listenbrainz.SaveWhileListeningPolicy
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manages automatic library persistence for streaming tracks ("Guardar al escuchar")
 * once the listening threshold is reached.
 */
internal class PlaybackSaveWhileListeningCoordinator(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val onEmitEvent: (String) -> Unit,
) {
    private val attemptedKeys = mutableSetOf<String>()
    private val failureCooldowns = mutableMapOf<String, Long>()
    private val pendingSettingsJobs = mutableMapOf<String, Job>()

    fun maybeSaveWhileListening(
        remote: PlayableItem.Remote,
        event: SaveWhileListeningEvent,
        positionMs: Long,
        durationMs: Long = remote.durationMs,
    ) {
        if (!dependencies.listenSettingsReady.value) {
            val queueEntryId = remote.queueEntryId
            pendingSettingsJobs.remove(queueEntryId)?.cancel()
            pendingSettingsJobs[queueEntryId] =
                scope.launch {
                    val ownJob = coroutineContext[Job]
                    try {
                        dependencies.listenSettingsReady.first { it }
                        maybeSaveWhileListening(remote, event, positionMs, durationMs)
                    } finally {
                        if (pendingSettingsJobs[queueEntryId] === ownJob) {
                            pendingSettingsJobs.remove(queueEntryId)
                        }
                    }
                }
            return
        }
        val settings = dependencies.listenSettings.value
        if (!settings.saveWhileListening) return
        if (!SaveWhileListeningPolicy.shouldSave(
                positionMs = positionMs,
                durationMs = durationMs,
                thresholdPercent = settings.saveWhileListeningPercent,
                event = event,
            )
        ) {
            return
        }
        val key = TrackMatchKeys.downloadIdFor(remote.artist, remote.title)
        if (key.isEmpty() || key in attemptedKeys) return
        val failedAt = failureCooldowns[key]
        if (failedAt != null &&
            dependencies.clockMs() - failedAt < SAVE_RETRY_COOLDOWN_MS
        ) {
            return
        }
        attemptedKeys += key
        scope.launch {
            when (val result = dependencies.saveDownloads.save(remote)) {
                is SaveWhileListeningDownloadResult.Saved -> {
                    failureCooldowns.remove(key)
                    onEmitEvent("«${result.song.title}» guardada en la biblioteca")
                }

                is SaveWhileListeningDownloadResult.InFlight -> {
                    attemptedKeys.remove(key)
                }

                is SaveWhileListeningDownloadResult.Failed -> {
                    failureCooldowns[key] = dependencies.clockMs()
                    attemptedKeys.remove(key)
                    onEmitEvent(
                        "No se pudo guardar «${remote.title}»: " +
                            (result.error.localizedMessage ?: "error"),
                    )
                }
            }
        }
    }

    companion object {
        private const val SAVE_RETRY_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
