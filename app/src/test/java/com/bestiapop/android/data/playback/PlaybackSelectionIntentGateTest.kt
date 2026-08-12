package com.bestiapop.android.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackSelectionIntentGateTest {

    @Test
    fun tapsAThenB_completingBThenA_appliesOnlyB() {
        val gate = PlaybackSelectionIntentGate()
        val intentA = gate.beginRemoteSelection()
        val intentB = gate.beginRemoteSelection()
        val applied = mutableListOf<String>()

        if (gate.isCurrent(intentB)) applied += "B"
        if (gate.isCurrent(intentA)) applied += "A"

        assertEquals(listOf("B"), applied)
    }

    @Test
    fun selectingLocal_invalidatesPendingRemoteIntent() {
        val gate = PlaybackSelectionIntentGate()
        val remoteIntent = gate.beginRemoteSelection()

        gate.onLocalSelected()

        assertFalse(gate.isCurrent(remoteIntent))
    }
}
