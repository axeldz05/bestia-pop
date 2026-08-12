package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeClearTest {

    @Test
    fun afterRadioStart_disablesShuffleAndRepeatOne() {
        val result = PlaybackModeClear.afterRadioStart(
            shuffle = true,
            repeat = RepeatMode.ONE
        )

        assertFalse(result.first)
        assertEquals(RepeatMode.OFF, result.second)
    }

    @Test
    fun afterRadioStart_keepsRepeatAll() {
        val result = PlaybackModeClear.afterRadioStart(
            shuffle = false,
            repeat = RepeatMode.ALL
        )

        assertFalse(result.first)
        assertEquals(RepeatMode.ALL, result.second)
    }

    @Test
    fun afterRadioStart_keepsRepeatOff() {
        val result = PlaybackModeClear.afterRadioStart(
            shuffle = true,
            repeat = RepeatMode.OFF
        )

        assertFalse(result.first)
        assertEquals(RepeatMode.OFF, result.second)
    }

    @Test
    fun defaults_matchIntuitivePolicy() {
        val settings = PlaybackSettings()
        assertTrue(settings.clearShuffleOnManualPlay)
        assertFalse(settings.clearRepeatAllOnManualPlay)
        assertTrue(settings.clearRepeatOneOnManualPlay)
        assertFalse(settings.clearShuffleOnSkip)
        assertTrue(settings.clearRepeatOneOnSkip)
    }

    @Test
    fun afterManualPlay_default_clearsShuffleAndRepeatOne_keepsRepeatAll() {
        val settings = PlaybackSettings()
        val one = PlaybackModeClear.afterManualPlay(true, RepeatMode.ONE, settings)
        assertFalse(one.first)
        assertEquals(RepeatMode.OFF, one.second)

        val all = PlaybackModeClear.afterManualPlay(true, RepeatMode.ALL, settings)
        assertFalse(all.first)
        assertEquals(RepeatMode.ALL, all.second)
    }

    @Test
    fun afterManualPlay_allOff_keepsModes() {
        val settings = PlaybackSettings(
            clearShuffleOnManualPlay = false,
            clearRepeatAllOnManualPlay = false,
            clearRepeatOneOnManualPlay = false
        )
        val result = PlaybackModeClear.afterManualPlay(true, RepeatMode.ONE, settings)
        assertTrue(result.first)
        assertEquals(RepeatMode.ONE, result.second)
    }

    @Test
    fun afterManualPlay_clearRepeatAll_turnsAllOff() {
        val settings = PlaybackSettings(clearRepeatAllOnManualPlay = true)
        val result = PlaybackModeClear.afterManualPlay(false, RepeatMode.ALL, settings)
        assertFalse(result.first)
        assertEquals(RepeatMode.OFF, result.second)
    }

    @Test
    fun afterSkip_default_clearsRepeatOneOnly() {
        val settings = PlaybackSettings()
        val one = PlaybackModeClear.afterSkip(true, RepeatMode.ONE, settings)
        assertTrue(one.first)
        assertEquals(RepeatMode.OFF, one.second)

        val all = PlaybackModeClear.afterSkip(true, RepeatMode.ALL, settings)
        assertTrue(all.first)
        assertEquals(RepeatMode.ALL, all.second)
    }

    @Test
    fun afterSkip_clearShuffle_turnsShuffleOff() {
        val settings = PlaybackSettings(clearShuffleOnSkip = true)
        val result = PlaybackModeClear.afterSkip(true, RepeatMode.ALL, settings)
        assertFalse(result.first)
        assertEquals(RepeatMode.ALL, result.second)
    }

    @Test
    fun afterSkip_repeatOneOff_doesNotTouchRepeat() {
        val settings = PlaybackSettings(clearRepeatOneOnSkip = false)
        val result = PlaybackModeClear.afterSkip(false, RepeatMode.ONE, settings)
        assertFalse(result.first)
        assertEquals(RepeatMode.ONE, result.second)
    }
}
