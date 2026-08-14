package com.bestiapop.android.service

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.preferences.ListenBrainzSettings
import com.bestiapop.android.data.preferences.PlaybackSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress tests para verificar la robustez de PlaybackRuntime, sincronización de la cola,
 * resolución concurrente de streams y políticas del Foreground Service bajo carga extrema.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackForegroundStressTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Stress Test de Concurrencia Extrema:
     * Ejecuta 10 corrutinas en paralelo en [Dispatchers.Default], realizando 500 operaciones
     * aleatorias combinadas (play, pause, skip, addBatch, remove, shuffle, playNextBatch) para
     * verificar que no ocurran race conditions, desincronización de estado o excepciones.
     */
    @Test
    fun stressTest_concurrencyBurst_randomOperationsMaintainStateConsistency() = runBlocking {
        val fixture = fixture()
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val numCoroutines = 10
        val opsPerCoroutine = 50

        try {
            // Inicializar la cola con algunas canciones
            fixture.runtime.playPlayableCollection(
                items = (1..5).map { song(it.toLong(), "Initial $it") }.map { PlayableItem.Local(it) },
                rotate = false
            )

            val jobs = (1..numCoroutines).map { coroutineId ->
                async(Dispatchers.Default) {
                    val rng = Random(coroutineId * 42)
                    repeat(opsPerCoroutine) { opIndex ->
                        try {
                            when (rng.nextInt(7)) {
                                0 -> fixture.runtime.togglePlayPause()
                                1 -> fixture.runtime.playNextBatch(listOf(PlayableItem.Local(song((100..999).random(rng).toLong(), "Next Track"))))
                                2 -> {
                                    val currentQueue = fixture.runtime.queue.value
                                    if (currentQueue.isNotEmpty()) {
                                        val removeIdx = rng.nextInt(currentQueue.size)
                                        fixture.runtime.removeFromQueue(removeIdx)
                                    }
                                }
                                3 -> fixture.runtime.toggleShuffle()
                                4 -> fixture.runtime.skipToNext()
                                5 -> fixture.runtime.skipToPrevious()
                                6 -> fixture.runtime.addPlayableBatch(listOf(PlayableItem.Local(song((1000..9999).random(rng).toLong(), "Added Track"))))
                            }
                            delay(5L) // Micro-delays para favorecer el entrelazado de hilos
                        } catch (e: Throwable) {
                            errors.add(e)
                        }
                    }
                }
            }

            jobs.awaitAll()

            assertTrue(
                "No deben ocurrir excepciones durante la ráfaga de concurrencia. Ocurrieron ${errors.size}:\n" +
                    errors.joinToString("\n---\n") { it.stackTraceToString() },
                errors.isEmpty()
            )

            // Verificar la consistencia final del estado
            val runtimeQueue = fixture.runtime.queue.value
            val controllerItems = fixture.controller.items()
            assertEquals("La cola en PlaybackRuntime y en FakeController deben estar sincronizadas", controllerItems.size, runtimeQueue.size)

            val currentSong = fixture.runtime.currentSong.value
            if (runtimeQueue.isNotEmpty()) {
                assertNotNull("Si la cola no está vacía, debe haber una canción seleccionada", currentSong)
            }
        } finally {
            fixture.close()
        }
    }

    /**
     * Stress Test de Resolución Remota Concurrente:
     * Simula la resolución lenta de streams remotos mientras la cola sufre mutaciones concurrentes
     * (limpieza de cola, eliminación de tracks, saltos). Verifica que las resoluciones canceladas
     * o desactualizadas no corrompan la cola ni reemplacen items incorrectos.
     */
    @Test
    fun stressTest_remoteStreamResolutionUnderConcurrentQueueMutations() = runBlocking {
        val slowStreamAccess = SlowResolvingStreamAccess(delayMs = 150L)
        val fixture = fixture(streamAccess = slowStreamAccess)

        try {
            val remoteItems = (1..5).map { remoteItem("remote-$it", "Remote Song $it") }
            fixture.runtime.playPlayableCollection(remoteItems, rotate = false)

            // Mientras se resuelven los streams remotos, mutar la cola concurrentemente
            val mutationJob = async(Dispatchers.Default) {
                repeat(15) {
                    delay(30L)
                    when (it % 4) {
                        0 -> fixture.runtime.addPlayableBatch(listOf(PlayableItem.Local(song(50L + it, "Local Interruption"))))
                        1 -> fixture.runtime.skipToNext()
                        2 -> fixture.runtime.toggleShuffle()
                        3 -> {
                            val q = fixture.runtime.queue.value
                            if (q.size > 1) fixture.runtime.removeFromQueue(0)
                        }
                    }
                }
            }

            mutationJob.await()
            delay(500L) // Dar tiempo a que terminen resoluciones pendientes

            val finalQueue = fixture.runtime.queue.value
            assertTrue("La cola debe permanecer funcional", finalQueue.isNotEmpty())
            val state = fixture.controller.playbackState
            assertTrue("El reproductor debe terminar en un estado válido", state != Player.STATE_ENDED || finalQueue.isEmpty())
        } finally {
            fixture.close()
        }
    }

    /**
     * Stress Test de Interrupciones de Foco de Audio en Ráfaga:
     * Ejecuta 100 ciclos rápidos de pérdida transitoria, ducking y recuperación de foco de audio
     * mientras se alternan canciones para asegurar que el reproductor no quede bloqueado.
     */
    @Test
    fun stressTest_rapidAudioFocusInterruptionBurst() = runBlocking {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "Focus Track 1")),
                    PlayableItem.Local(song(2, "Focus Track 2"))
                ),
                rotate = false
            )
            withTimeout(5_000L) {
                while (!fixture.controller.isPlaying) delay(50L)
            }

            repeat(100) { cycle ->
                when (cycle % 3) {
                    0 -> fixture.controller.simulateAudioFocusLossTransient()
                    1 -> fixture.controller.simulateAudioFocusLossTransientCanDuck()
                    2 -> fixture.controller.simulateAudioFocusGain()
                }
                if (cycle % 10 == 0) {
                    fixture.runtime.togglePlayPause()
                }
                delay(15L)
            }

            // Asegurar que tras la ráfaga de interrupciones se recupere la reproducción si se desea
            fixture.controller.simulateAudioFocusGain()
            delay(200L)

            assertTrue(
                "Tras ráfagas de interrupción de foco, playWhenReady debe ser recuperable",
                fixture.controller.playWhenReady || fixture.controller.isPlaying
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Stress Test de Presión de Memoria del Sistema Operativo:
     * Verifica la respuesta de la política del servicio ante eventos de recorte de memoria del SO
     * (Simulando [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] y [ComponentCallbacks2.TRIM_MEMORY_COMPLETE]).
     */
    @Test
    fun stressTest_systemTrimMemory_musicServiceLifetime() = runBlocking {
        val memoryTrims = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )

        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(PlayableItem.Local(song(1, "Memory Test"))),
                rotate = false
            )
            withTimeout(3_000L) {
                while (!fixture.controller.isPlaying) delay(50L)
            }

            memoryTrims.forEach { trimLevel ->
                // Asignación de memoria simulada para acompañar el evento de recorte
                val block = ByteArray(4 * 1024 * 1024)
                block.fill(1)
                
                // Evaluar si la política de servicio retiene la necesidad de notificación e intención de playback
                val engaged = PlaybackServiceLifetimePolicy.isPlaybackEngaged(
                    playWhenReady = fixture.controller.playWhenReady,
                    mediaItemCount = fixture.controller.mediaItemCount,
                    playbackState = fixture.controller.playbackState
                )
                assertTrue("Playback debe mantenerse en engagement durante nivel de memoria $trimLevel", engaged)
                assertTrue(
                    "La notificación de reproducción debe seguir siendo requerida bajo trimLevel $trimLevel",
                    PlaybackServiceLifetimePolicy.shouldShowPlaybackNotification(
                        mediaItemCount = fixture.controller.mediaItemCount,
                        playbackState = fixture.controller.playbackState
                    )
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stressTest_memoryPressure_keepsForegroundService() = runBlocking {
        val fixture = fixture()
        try {
            fixture.runtime.playPlayableCollection(
                listOf(
                    PlayableItem.Local(song(1, "Track 1")),
                    PlayableItem.Local(song(2, "Track 2")),
                    PlayableItem.Local(song(3, "Track 3"))
                ),
                rotate = false
            )

            withTimeout(5_000L) {
                while (!fixture.controller.isPlaying) delay(50L)
            }

            assertTrue("Playback debe estar activo", fixture.controller.isPlaying)
            assertTrue("playWhenReady debe ser true", fixture.controller.playWhenReady)

            val memoryBlocks = mutableListOf<ByteArray>()
            repeat(5) { iteration ->
                val blockSize = 5 * 1024 * 1024 // 5 MB por bloque
                memoryBlocks.add(ByteArray(blockSize))
                delay(200)
                assertTrue(
                    "Servicio debe mantenerse activo tras presión de memoria (iteración $iteration)",
                    fixture.controller.isPlaying || fixture.controller.playWhenReady
                )
            }
            memoryBlocks.clear()
            System.gc()
            delay(500)
            assertTrue(
                "Playback debe sobrevivir al stress test de memoria",
                fixture.controller.playWhenReady || fixture.controller.isPlaying
            )
            val playbackState = fixture.controller.playbackState
            assertTrue(
                "Estado de reproducción debe ser válido (no ENDED)",
                playbackState != Player.STATE_ENDED
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stressTest_foregroundPolicy_underPressure() = runBlocking {
        val testCases = listOf(
            Triple(true, 3, Player.STATE_READY),      // Normal playing
            Triple(true, 1, Player.STATE_IDLE),       // Remote placeholder
            Triple(true, 2, Player.STATE_BUFFERING),  // Buffering
            Triple(false, 3, Player.STATE_READY),     // Paused but engaged
            Triple(true, 0, Player.STATE_IDLE),       // Empty queue
            Triple(true, 2, Player.STATE_ENDED)       // Ended
        )
        testCases.forEachIndexed { index, (playWhenReady, itemCount, state) ->
            val shouldBeEngaged = PlaybackServiceLifetimePolicy.isPlaybackEngaged(
                playWhenReady, itemCount, state
            )
            val expectedEngaged = when (index) {
                0 -> true   // Playing normally
                1 -> true   // Remote placeholder with play intent
                2 -> true   // Buffering with play intent
                3 -> false  // Paused
                4 -> false  // No queue
                5 -> false  // Ended
                else -> false
            }

            assertEquals(
                "Caso $index: isPlaybackEngaged debe ser consistente",
                expectedEngaged,
                shouldBeEngaged
            )
        }
    }

    private fun fixture(
        streamAccess: PlaybackRuntimeStreamAccess = PlaybackContinuityFunctionalTest.InstantStreamAccess(),
        playbackSettings: MutableStateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings())
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaybackContinuityFunctionalTest.FakeController()

        val runtime = PlaybackRuntime(
            PlaybackRuntimeDependencies(
                scope = scope,
                libraryUpdates = MutableStateFlow(emptyList()),
                playbackSettings = playbackSettings,
                playbackSettingsReady = MutableStateFlow(true),
                listenSettings = MutableStateFlow(ListenBrainzSettings()),
                listenSettingsReady = MutableStateFlow(true),
                persistence = PlaybackContinuityFunctionalTest.RecordingPersistence(),
                listenTracker = PlaybackContinuityFunctionalTest.NoopListenTracker,
                streamAccess = streamAccess,
                saveDownloads = PlaybackContinuityFunctionalTest.FakeSaveDownloads(),
                radioSuggester = PlaybackRuntimeRadioSuggester {
                    com.bestiapop.android.domain.radio.RadioSuggestResult(emptyList(), false, false)
                },
                isOnline = { true },
                persistShuffle = {},
                clockMs = { System.currentTimeMillis() },
                elapsedRealtimeMs = { System.currentTimeMillis() },
                controllerReconnectBackoffMs = { 0L },
                startTicker = false
            )
        )

        runtime.attachControllerForTest(controller)
        return Fixture(runtime, controller, scope)
    }

    private data class Fixture(
        val runtime: PlaybackRuntime,
        val controller: PlaybackContinuityFunctionalTest.FakeController,
        val scope: CoroutineScope
    ) {
        fun close() = scope.cancel()
    }

    private fun song(id: Long, title: String): Song {
        return Song(
            id = id,
            uriString = "file:///dummy/path/song_$id.m4a",
            title = title,
            artist = "Test Artist"
        )
    }

    private fun remoteItem(query: String, title: String): PlayableItem.Remote {
        return PlayableItem.remoteFrom(
            identity = TrackIdentity(
                title = title,
                artist = "Remote Artist",
                durationMs = 180_000L
            ),
            youtubeQueryOrId = query
        )
    }

    private class SlowResolvingStreamAccess(
        private val delayMs: Long
    ) : PlaybackRuntimeStreamAccess {
        override fun needsResolve(item: PlayableItem.Remote): Boolean =
            item.resolved?.audioUrl.isNullOrBlank()

        override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote {
            delay(delayMs)
            val query = item.youtubeQueryOrId.orEmpty()
            return item.copy(
                resolved = ResolvedStream(
                    audioUrl = "https://cdn.example.com/$query",
                    userAgent = "stress-test-UA",
                    videoId = "vid-$query",
                    resolvedAtEpochMs = System.currentTimeMillis()
                )
            )
        }

        override suspend fun invalidate(item: PlayableItem.Remote) = Unit
    }
}
