package com.bestiapop.android.service

import android.os.PowerManager
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bestiapop.android.persistence.HostOrchestratedProcessDeathTest
import com.bestiapop.android.testutil.PlaybackDeviceProbe
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-only characterization of a live session with the real screen locked.
 *
 * The host must start playback before invoking this test. It intentionally does not change
 * sideload app-ops, the queue, Room or DataStore.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HostOrchestratedProcessDeathTest
class LockedScreenPlaybackFunctionalTest {

    private val probe = PlaybackDeviceProbe()
    private val context
        get() = probe.context

    @Test
    @HostOrchestratedProcessDeathTest
    fun activePlayback_keepsForegroundNotificationWhileScreenIsLocked() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wasInteractive = powerManager.isInteractive
        val controller = probe.connectController()
        try {
            val baseline = probe.onMain {
                check(controller.playWhenReady && controller.isPlaying) {
                    "Start playback before running the locked-screen characterization"
                }
                controller.currentPosition
            }
            assertTrue(probe.musicServiceInfo()?.foreground == true)
            assertTrue(probe.playbackNotification() != null)

            probe.executeShell("input keyevent ${KeyEvent.KEYCODE_SLEEP}")
            probe.await("screen turns off") { !powerManager.isInteractive }
            probe.await("playback advances while locked") {
                probe.onMain {
                    controller.isPlaying &&
                        controller.currentPosition >= baseline + MIN_POSITION_ADVANCE_MS
                }
            }

            assertTrue(probe.musicServiceInfo()?.foreground == true)
            assertTrue(probe.playbackNotification() != null)
        } finally {
            probe.onMain { controller.release() }
            if (wasInteractive && !powerManager.isInteractive) {
                probe.executeShell("input keyevent ${KeyEvent.KEYCODE_WAKEUP}")
            }
        }
    }

    private companion object {
        const val MIN_POSITION_ADVANCE_MS = 250L
    }
}
