package com.bestiapop.android.service

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.testutil.SideloadPlaybackAppOps
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Media3 interruption handling. The synthetic WAV is muted at the player, so the tests exercise
 * AudioManager focus/noisy policy without producing audible output.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackAudioInterruptionFunctionalTest {

    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var sideloadPolicy: AutoCloseable? = null

    @Before
    fun preparePlaybackPolicy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ).forEach { permission ->
                if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                    instrumentation.uiAutomation.grantRuntimePermission(
                        context.packageName,
                        permission
                    )
                }
            }
        }
        sideloadPolicy = SideloadPlaybackAppOps.acquire()
    }

    @After
    fun restorePlaybackPolicy() {
        sideloadPolicy?.close()
        sideloadPolicy = null
    }

    @Test
    fun transientAudioFocusLoss_suppressesThenResumesPlayback() {
        withPlayingFixture { controller ->
            val audioManager = context.getSystemService(AudioManager::class.java)
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()

            try {
                assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audioManager.requestAudioFocus(focusRequest)
                )
                await("Media3 pauses under transient audio-focus loss") {
                    onMain {
                        controller.playWhenReady &&
                            !controller.isPlaying &&
                            controller.playbackSuppressionReason ==
                            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    }
                }
            } finally {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }

            await("Media3 resumes after the competing focus owner abandons focus") {
                onMain {
                    controller.playWhenReady &&
                        controller.isPlaying &&
                        controller.playbackSuppressionReason ==
                        Player.PLAYBACK_SUPPRESSION_REASON_NONE
                }
            }
        }
    }

    @Test
    fun audioBecomingNoisy_shellProtectedBroadcast_pausesPlayback() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val hasDisconnectableOutput = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { device ->
                device.type in setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET
                )
            }
        assumeTrue(
            "AUDIO_BECOMING_NOISY is meaningful only with a disconnectable output route",
            hasDisconnectableOutput
        )
        withPlayingFixture { controller ->
            val output = executeShell(
                "am broadcast --user current --receiver-registered-only " +
                    "-a ${AudioManager.ACTION_AUDIO_BECOMING_NOISY}"
            )
            assertFalse("Protected noisy broadcast was rejected: $output", "SecurityException" in output)

            await("Media3 pauses after simulated wired-output unplug") {
                onMain { !controller.playWhenReady && !controller.isPlaying }
            }
        }
    }

    private fun withPlayingFixture(assertions: (MediaController) -> Unit) {
        val fixtureDir = File(context.cacheDir, "audio-interruption-${System.nanoTime()}")
        val wav = File(fixtureDir, "muted.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: MediaController? = null

        try {
            check(fixtureDir.mkdirs()) { "Could not create $fixtureDir" }
            PcmWavFixture.write(wav, durationMs = PLAYBACK_DURATION_MS)
            scenario = ActivityScenario.launch(MainActivity::class.java).also {
                it.moveToState(Lifecycle.State.RESUMED)
            }
            val connected = connectController()
            controller = connected
            val playable = PlayableItem.Local(
                Song(
                    id = System.nanoTime(),
                    uriString = wav.absolutePath,
                    title = "Muted audio-interruption fixture",
                    artist = "BestiaPop instrumentation",
                    durationMs = PLAYBACK_DURATION_MS.toLong()
                )
            )
            onMain {
                connected.volume = 0f
                (context.applicationContext as BestiaPopApplication)
                    .playbackRuntime
                    .playPlayableCollection(listOf(playable), rotate = false)
            }
            await("synthetic WAV starts in real ExoPlayer") {
                onMain {
                    connected.playWhenReady &&
                        connected.isPlaying &&
                        connected.playbackState == Player.STATE_READY &&
                        connected.currentPosition > 0L
                }
            }

            assertions(connected)
        } finally {
            controller?.let { connected ->
                runCatching {
                    onMain {
                        connected.stop()
                        connected.clearMediaItems()
                        connected.release()
                    }
                }
            }
            runCatching { scenario?.close() }
            context.stopService(Intent(context, MusicService::class.java))
            fixtureDir.deleteRecursively()
        }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun executeShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ASYNC_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val PLAYBACK_DURATION_MS = 30_000
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val ASYNC_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 25L
    }
}
