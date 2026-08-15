package com.bestiapop.android.data.system

import android.os.Build
import org.junit.Assert.assertEquals
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
        assertTrue(status.blocksBackgroundPlayback)
    }

    @Test
    fun runAnyInBackgroundIgnored_blocksPlaybackEvenWhenNotUiRestricted() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { false },
            ignoringBatteryOptimizations = { true },
            runAnyInBackgroundIgnored = { true }
        )

        assertFalse(status.backgroundRestricted)
        assertTrue(status.runAnyInBackgroundIgnored)
        assertTrue(status.blocksBackgroundPlayback)
    }

    @Test
    fun oemScreenOffCleanupIntent_targetsUnisocBatteryPageNotStorageClear() {
        assertEquals("unisoc.intent.action.POWER_BACKGROUND_CLEAN", OEM_POWER_BACKGROUND_CLEAN_ACTION)
        assertEquals("lock_screen_battery_save", OEM_LOCK_SCREEN_BATTERY_SAVE_KEY)
        assertEquals(":settings:fragment_args_key", SETTINGS_FRAGMENT_ARG_KEY)
    }

    @Test
    fun restrictionGuidance_isOemSpecificAndFallsBackToUnrestricted() {
        val motorola = backgroundRestrictionGuidance("motorola")
        assertTrue(motorola.body.contains("Recientes"))
        assertEquals(motorola, backgroundRestrictionGuidance("Lenovo"))

        val samsung = backgroundRestrictionGuidance("samsung")
        assertTrue(samsung.body.contains("nunca duermen"))

        val xiaomi = backgroundRestrictionGuidance("Xiaomi")
        assertTrue(xiaomi.body.contains("inicio automático"))
        assertEquals(xiaomi.title, backgroundRestrictionGuidance("POCO").title)
        assertEquals(xiaomi.body, backgroundRestrictionGuidance("Redmi").body)

        val stock = backgroundRestrictionGuidance("Google")
        assertTrue(stock.body.contains("Sin restricciones"))
        assertFalse(stock.body.contains("Motorola"))
        assertFalse(stock.body.contains("Recientes"))
    }
}
