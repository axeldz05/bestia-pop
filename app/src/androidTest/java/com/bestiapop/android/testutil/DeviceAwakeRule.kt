package com.bestiapop.android.testutil

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Keeps the device awake/unlocked for Compose instrumentation. */
class DeviceAwakeRule : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        fun executeShellCommand(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
        }

        val keyguard = instrumentation.targetContext
            .getSystemService(KeyguardManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS
        while (keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < deadline) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP")
            executeShellCommand("wm dismiss-keyguard")
            executeShellCommand("input keyevent 82")
            instrumentation.waitForIdleSync()
            // OEM secure locks may keep isDeviceLocked=true after dismiss; keyguard showing is the
            // gate that blocks Compose input.
            if (keyguard.isKeyguardLocked) {
                SystemClock.sleep(UNLOCK_POLL_MS)
            }
        }

        check(!keyguard.isKeyguardLocked) {
            "Compose instrumentation tests require an unlocked keyguard"
        }
    }

    private companion object {
        const val UNLOCK_TIMEOUT_MS = 3_000L
        const val UNLOCK_POLL_MS = 100L
    }
}
