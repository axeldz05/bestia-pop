package com.bestiapop.android.ui.settings

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.MAX_VOLUME_BOOST_GAIN_MB
import com.bestiapop.android.data.preferences.PlaybackPreferencesRepository
import com.bestiapop.android.data.preferences.ThemePreferencesRepository
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.MusicServiceAppliedSettings
import com.bestiapop.android.service.MusicServiceSettingsProbe
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.PcmWavFixture
import com.bestiapop.android.ui.persistence.MainActivityStateRule
import com.bestiapop.android.ui.theme.ThemePresets
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsRuntimeFunctionalTest {
    private val activityRule = createAndroidComposeRule<MainActivity>()
    private val mainStateRule = MainActivityStateRule()
    private val settingsStateRule = SettingsStateRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(settingsStateRule)
        .around(mainStateRule)
        .around(activityRule)

    @Test
    fun themePreset_selectedFromUi_survivesActivityRecreate() {
        val preferences = ThemePreferencesRepository(context)
        openSettingsSection("Temas")

        activityRule.onNodeWithTag("theme-preset-${ThemePresets.SunsetGold.id}").performClick()
        activityRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
            runBlocking { preferences.selectedThemeFlow.first().id == ThemePresets.SunsetGold.id }
        }
        activityRule
            .onNodeWithTag("theme-preset-${ThemePresets.SunsetGold.id}")
            .assertIsSelected()

        activityRule.activityRule.scenario.recreate()
        activityRule.waitForIdle()
        openSettingsSection("Temas")

        activityRule
            .onNodeWithTag("theme-preset-${ThemePresets.SunsetGold.id}")
            .assertIsSelected()
        assertEquals(
            ThemePresets.SunsetGold.id,
            runBlocking { preferences.selectedThemeFlow.first().id }
        )
    }

    @Test
    fun playbackLaunchSwitches_changedFromUi_surviveActivityRecreate() {
        val preferences = PlaybackPreferencesRepository(context)
        openSettingsSection("Reproducción")

        switchNode("Reproducir al abrir").performClick()
        switchNode("Recordar aleatorio").performClick()
        switchNode("Recordar repetición").performClick()

        activityRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
            runBlocking {
                preferences.settingsFlow.first().let {
                    it.autoplayOnLaunch &&
                        !it.rememberShuffleOnLaunch &&
                        !it.rememberRepeatOnLaunch
                }
            }
        }

        activityRule.activityRule.scenario.recreate()
        activityRule.waitForIdle()
        openSettingsSection("Reproducción")

        switchNode("Reproducir al abrir").assertIsOn()
        switchNode("Recordar aleatorio").assertIsOff()
        switchNode("Recordar repetición").assertIsOff()
    }

    @Test
    fun soundUi_changesReachRunningMusicService() {
        val fixtureDir = File(context.cacheDir, "settings-service-${System.nanoTime()}")
        val wav = File(fixtureDir, "settings.wav")
        val applied = AtomicReference<MusicServiceAppliedSettings>()
        val observer = MusicServiceSettingsProbe.observe(applied::set)
        var controller: MediaController? = null

        try {
            check(fixtureDir.mkdirs())
            PcmWavFixture.write(wav, durationMs = PLAYBACK_DURATION_MS, toneHz = 220.0)
            controller = connectController()
            val connected = requireNotNull(controller)
            onMain {
                connected.volume = 0f
                (context.applicationContext as BestiaPopApplication)
                    .playbackRuntime
                    .playPlayableCollection(
                        listOf(
                            PlayableItem.Local(
                                Song(
                                    id = 91_001L,
                                    uriString = wav.absolutePath,
                                    title = "Settings service probe",
                                    artist = "BestiaPop instrumentation",
                                    durationMs = PLAYBACK_DURATION_MS.toLong()
                                )
                            )
                        ),
                        rotate = false
                    )
            }
            activityRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
                onMain {
                    connected.playbackState == Player.STATE_READY && connected.playWhenReady
                }
            }

            openSettingsSection("Sonido")
            switchNode("Amplificar volumen").performClick()
            setSlider("Balance Izquierdo", 0.25f)
            setSlider("Balance Derecho", 0.75f)

            val expectedTarget =
                (SettingsStateRule.TEST_BOOST_AMOUNT * MAX_VOLUME_BOOST_GAIN_MB).toInt()
            activityRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MS) {
                applied.get()?.let {
                    it.leftGain.closeTo(0.25f) &&
                        it.rightGain.closeTo(0.75f) &&
                        it.targetGainMb == expectedTarget
                } == true
            }
            val persisted = runBlocking { PlaybackPreferencesRepository(context).settingsFlow.first() }
            assertEquals(true, persisted.volumeBoostEnabled)
            assertEquals(0.25f, persisted.stereoLeftGain, FLOAT_TOLERANCE)
            assertEquals(0.75f, persisted.stereoRightGain, FLOAT_TOLERANCE)
        } finally {
            observer.close()
            controller?.let { connected ->
                runCatching {
                    onMain {
                        connected.stop()
                        connected.clearMediaItems()
                        connected.release()
                    }
                }
            }
            context.stopService(Intent(context, MusicService::class.java))
            fixtureDir.deleteRecursively()
        }
    }

    private fun openSettingsSection(title: String) {
        activityRule
            .onNodeWithContentDescription("Ajustes", useUnmergedTree = true)
            .performClick()
        activityRule.onNodeWithText(title).performClick()
    }

    private fun switchNode(title: String) =
        activityRule.onNode(hasText(title) and hasClickAction())

    private fun setSlider(contentDescription: String, value: Float) {
        activityRule
            .onNodeWithContentDescription(contentDescription)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                check(setProgress(value))
            }
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun Float.closeTo(expected: Float): Boolean =
        kotlin.math.abs(this - expected) <= FLOAT_TOLERANCE

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val ASYNC_TIMEOUT_MS = 10_000L
        const val PLAYBACK_DURATION_MS = 20_000
        const val FLOAT_TOLERANCE = 0.02f
    }
}
