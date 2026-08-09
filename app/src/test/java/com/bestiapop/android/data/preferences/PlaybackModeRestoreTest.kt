package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeRestoreTest {

    @Test
    fun coldStart_rememberOn_restoresLastModes() {
        val resolved = PlaybackModeRestore.resolve(
            rememberShuffle = true,
            rememberRepeat = true,
            lastShuffle = true,
            lastRepeat = RepeatMode.ALL,
            hasLiveSession = false,
            liveRepeat = RepeatMode.OFF
        )
        assertTrue(resolved.shuffle)
        assertEquals(RepeatMode.ALL, resolved.repeat)
        assertTrue(resolved.applyRepeatToPlayer)
    }

    @Test
    fun coldStart_rememberOff_forcesOff() {
        val resolved = PlaybackModeRestore.resolve(
            rememberShuffle = false,
            rememberRepeat = false,
            lastShuffle = true,
            lastRepeat = RepeatMode.ONE,
            hasLiveSession = false,
            liveRepeat = RepeatMode.OFF
        )
        assertFalse(resolved.shuffle)
        assertEquals(RepeatMode.OFF, resolved.repeat)
        assertTrue(resolved.applyRepeatToPlayer)
    }

    @Test
    fun coldStart_rememberShuffleOnly() {
        val resolved = PlaybackModeRestore.resolve(
            rememberShuffle = true,
            rememberRepeat = false,
            lastShuffle = true,
            lastRepeat = RepeatMode.ALL,
            hasLiveSession = false,
            liveRepeat = RepeatMode.OFF
        )
        assertTrue(resolved.shuffle)
        assertEquals(RepeatMode.OFF, resolved.repeat)
    }

    @Test
    fun coldStart_rememberRepeatOnly() {
        val resolved = PlaybackModeRestore.resolve(
            rememberShuffle = false,
            rememberRepeat = true,
            lastShuffle = true,
            lastRepeat = RepeatMode.ONE,
            hasLiveSession = false,
            liveRepeat = RepeatMode.OFF
        )
        assertFalse(resolved.shuffle)
        assertEquals(RepeatMode.ONE, resolved.repeat)
    }

    @Test
    fun liveSession_keepsControllerRepeat_andShuffleFromPrefs() {
        val resolved = PlaybackModeRestore.resolve(
            rememberShuffle = false,
            rememberRepeat = false,
            lastShuffle = true,
            lastRepeat = RepeatMode.OFF,
            hasLiveSession = true,
            liveRepeat = RepeatMode.ALL
        )
        assertTrue(resolved.shuffle)
        assertEquals(RepeatMode.ALL, resolved.repeat)
        assertFalse(resolved.applyRepeatToPlayer)
    }

    @Test
    fun resolve_settingsOverload_delegatesToPrimitive() {
        val settings = PlaybackSettings(
            rememberShuffleOnLaunch = true,
            rememberRepeatOnLaunch = false,
            lastShuffleEnabled = true,
            lastRepeatMode = RepeatMode.ALL
        )
        val viaSettings = PlaybackModeRestore.resolve(settings, false, RepeatMode.OFF)
        val viaPrimitive = PlaybackModeRestore.resolve(
            rememberShuffle = true,
            rememberRepeat = false,
            lastShuffle = true,
            lastRepeat = RepeatMode.ALL,
            hasLiveSession = false,
            liveRepeat = RepeatMode.OFF
        )
        assertEquals(viaPrimitive, viaSettings)
    }

    @Test
    fun playbackSettings_autoplayOnLaunchDefaultsOff() {
        assertFalse(PlaybackSettings().autoplayOnLaunch)
    }

    @Test
    fun parseRepeatModeName_validAndFallback() {
        assertEquals(RepeatMode.OFF, parseRepeatModeName(null))
        assertEquals(RepeatMode.OFF, parseRepeatModeName(""))
        assertEquals(RepeatMode.OFF, parseRepeatModeName("nope"))
        assertEquals(RepeatMode.ALL, parseRepeatModeName("ALL"))
        assertEquals(RepeatMode.ONE, parseRepeatModeName("ONE"))
        assertEquals(RepeatMode.OFF, parseRepeatModeName("OFF"))
    }
}
