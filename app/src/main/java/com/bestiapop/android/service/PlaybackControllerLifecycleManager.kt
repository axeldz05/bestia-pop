package com.bestiapop.android.service

import com.bestiapop.android.data.util.PlaybackDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val POSITION_TICK_MS = 200L

internal class PlaybackControllerLifecycleManager(
    private val scope: CoroutineScope,
    private val dependencies: PlaybackRuntimeDependencies,
    private val getPlayWhenReadyIntent: () -> Boolean,
    private val getIsPlaying: () -> Boolean,
    private val getQueueSize: () -> Int,
    private val emitEvent: (String) -> Unit,
    private val getPlayerListener: () -> PlaybackControllerFacade.Listener,
    private val onControllerAttached: (PlaybackControllerFacade) -> Unit,
    private val onControllerDisconnectedCleanup: (PlaybackControllerFacade) -> Unit,
    private val onTaskRemovedCleanup: () -> Unit,
    private val onIdleReleasedCleanup: () -> Unit,
    private val samplePositionAndOwnership: suspend () -> Unit
) {
    private val warmingUp = AtomicBoolean(false)
    private val uiAttachments = AtomicInteger(0)

    var controller: PlaybackControllerFacade? = null
        private set

    private var controllerConnector: PlaybackControllerConnector? = null
    private var controllerFuture: PlaybackControllerConnection? = null
    private var controllerReconnectJob: Job? = null
    private var consecutiveControllerFailures = 0
    private var tickerJob: Job? = null

    val isControllerConnected: Boolean
        get() = controller != null

    val isTickerActive: Boolean
        get() = tickerJob?.isActive == true

    val uiAttachmentCount: Int
        get() = uiAttachments.get()

    fun configureControllerConnector(connector: PlaybackControllerConnector) {
        controllerConnector = connector
        ensureControllerConnection()
    }

    fun warmUpController() {
        if (controller != null || uiAttachments.get() > 0) return
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.warmUpController() - eagerly connecting controller"
        )
        warmingUp.set(true)
        ensureControllerConnection()
    }

    fun markWarmUpFinished() {
        if (!warmingUp.getAndSet(false)) return
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.markWarmUpFinished() - releasing warmup lock"
        )
        postOrRunReleaseControllerIfIdle()
    }

    fun attachUi() {
        warmingUp.set(false)
        val count = uiAttachments.incrementAndGet()
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.attachUi() (uiAttachments=$count)"
        )
        ensureControllerConnection()
        updateTickerLifecycle()
    }

    fun detachUi() {
        val count = uiAttachments.updateAndGet { c -> (c - 1).coerceAtLeast(0) }
        PlaybackDiagnostics.log(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.detachUi() (uiAttachments=$count)"
        )
        updateTickerLifecycle()
        if (count == 0) {
            postOrRunReleaseControllerIfIdle()
        }
    }

    fun attachControllerForTest(testController: PlaybackControllerFacade) {
        attachController(testController)
    }

    fun ensureControllerConnection() {
        if (!shouldRetainController()) return
        if (controller != null || controllerFuture != null ||
            controllerReconnectJob?.isActive == true
        ) {
            return
        }
        val connector = controllerConnector ?: return
        val future = runCatching(connector::connect).getOrElse {
            onControllerConnectionFailed()
            return
        }
        controllerFuture = future
        future.addListener {
            scope.launch {
                if (controllerFuture !== future) return@launch
                controllerFuture = null
                runCatching(future::get).fold(
                    onSuccess = { connected ->
                        consecutiveControllerFailures = 0
                        if (shouldRetainController()) {
                            attachController(connected)
                        } else {
                            connected.release()
                        }
                    },
                    onFailure = { onControllerConnectionFailed() }
                )
            }
        }
    }

    private fun onControllerConnectionFailed() {
        consecutiveControllerFailures++
        if (consecutiveControllerFailures == 1) {
            emitEvent("No se pudo conectar la reproducción")
        }
        if (!shouldRetainController() || controllerReconnectJob?.isActive == true) return
        val delayMs = dependencies.controllerReconnectBackoffMs(consecutiveControllerFailures)
            .coerceAtLeast(0L)
        controllerReconnectJob = scope.launch {
            delay(delayMs)
            controllerReconnectJob = null
            ensureControllerConnection()
        }
    }

    private fun attachController(newController: PlaybackControllerFacade) {
        if (controller === newController) return
        check(controller == null) { "PlaybackRuntime already owns a controller" }
        controller = newController
        newController.addListener(getPlayerListener())
        onControllerAttached(newController)
        updateTickerLifecycle()
    }

    fun handleControllerDisconnected(disconnected: PlaybackControllerFacade) {
        if (controller !== disconnected) return
        controller = null
        tickerJob?.cancel()
        tickerJob = null
        onControllerDisconnectedCleanup(disconnected)
        disconnected.release()
        ensureControllerConnection()
    }

    fun onTaskRemovedNotEngaged() {
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.onTaskRemovedNotEngaged: task removed while not engaged, releasing controller"
        )
        warmingUp.set(false)
        uiAttachments.set(0)
        onTaskRemovedCleanup()
        controllerReconnectJob?.cancel()
        controllerReconnectJob = null
        controllerFuture?.let { future ->
            controllerFuture = null
            future.cancel()
        }
        tickerJob?.cancel()
        tickerJob = null
        val owned = controller
        controller = null
        owned?.release()
    }

    fun shouldRetainController(): Boolean =
        warmingUp.get() ||
                uiAttachments.get() > 0 ||
                getPlayWhenReadyIntent() ||
                getIsPlaying()

    fun releaseControllerIfIdle() {
        val shouldRetain = shouldRetainController()
        if (shouldRetain) {
            PlaybackDiagnostics.log(
                PlaybackDiagnostics.TAG_RUNTIME,
                "PlaybackRuntime.releaseControllerIfIdle: RETAINING controller (uiAttachments=${uiAttachments.get()}, queueSize=${getQueueSize()}, playWhenReadyIntent=${getPlayWhenReadyIntent()}, isPlaying=${getIsPlaying()})"
            )
            ensureControllerConnection()
            return
        }
        PlaybackDiagnostics.warn(
            PlaybackDiagnostics.TAG_RUNTIME,
            "PlaybackRuntime.releaseControllerIfIdle: RELEASING controller (idle, no UI, no playback)"
        )
        controllerReconnectJob?.cancel()
        controllerReconnectJob = null
        controllerFuture?.let { future ->
            controllerFuture = null
            future.cancel()
        }
        tickerJob?.cancel()
        tickerJob = null
        val owned = controller
        controller = null
        onIdleReleasedCleanup()
        owned?.release()
    }

    fun postOrRunReleaseControllerIfIdle() {
        val posted = try {
            val looper = android.os.Looper.myLooper()
            if (looper != null) {
                android.os.Handler(looper).post {
                    releaseControllerIfIdle()
                }
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
        if (!posted) {
            releaseControllerIfIdle()
        }
    }

    fun updateTickerLifecycle() {
        val shouldTick = dependencies.startTicker &&
                controller?.isPlaying == true &&
                uiAttachments.get() > 0
        if (!shouldTick) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && controller?.isPlaying == true && uiAttachments.get() > 0) {
                samplePositionAndOwnership()
                delay(POSITION_TICK_MS)
            }
            tickerJob = null
        }
    }
}
