package com.bestiapop.android.data.system

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundExecutionStatusTest {

    @Test
    fun preAndroidP_hasNoBackgroundRestrictionApi_butKeepsDozeStatus() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.O_MR1,
            backgroundRestricted = { true },
            ignoringBatteryOptimizations = { true }
        )

        assertFalse(status.backgroundRestricted)
        assertTrue(status.ignoringBatteryOptimizations)
    }

    @Test
    fun androidPAndLater_reportsIndependentPlatformSignals() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { true },
            ignoringBatteryOptimizations = { false }
        )

        assertTrue(status.backgroundRestricted)
        assertFalse(status.ignoringBatteryOptimizations)
    }
}
