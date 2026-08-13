package com.bestiapop.android.service.library

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class BestiaPopMediaLibraryCallbackTest {

    @Test
    fun untrustedTransportPolicyOnlyAddsPlaybackAndSkipCommands() {
        assertEquals(
            setOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT
            ),
            UNTRUSTED_TRANSPORT_PLAYER_COMMANDS
        )
    }
}
