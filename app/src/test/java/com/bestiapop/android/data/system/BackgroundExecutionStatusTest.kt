package com.bestiapop.android.data.system

import android.app.AppOpsManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundExecutionStatusTest {

    @Test
    fun preAndroidP_hasNoBackgroundRestrictionApi_butKeepsDozeStatus() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.O_MR1,
            backgroundRestricted = { true },
            ignoringBatteryOptimizations = { true },
            runAnyInBackgroundIgnored = { true }
        )

        assertFalse(status.backgroundRestricted)
        assertFalse(status.runAnyInBackgroundIgnored)
        assertFalse(status.blocksBackgroundPlayback)
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
        assertFalse(status.blocksBackgroundPlayback)
    }

    @Test
    fun uiRestrictedWithoutIgnoredAppOp_doesNotClaimBlockedPlayback() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { true },
            ignoringBatteryOptimizations = { true },
            runAnyInBackgroundIgnored = { false }
        )

        assertTrue(status.backgroundRestricted)
        assertFalse(status.runAnyInBackgroundIgnored)
        assertFalse(status.blocksBackgroundPlayback)
    }

    @Test
    fun runAnyInBackgroundIgnored_withoutUiRestriction_doesNotClaimBlockedPlayback() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { false },
            ignoringBatteryOptimizations = { true },
            runAnyInBackgroundIgnored = { true }
        )

        assertFalse(status.backgroundRestricted)
        assertTrue(status.runAnyInBackgroundIgnored)
        assertFalse(status.blocksBackgroundPlayback)
    }

    @Test
    fun confirmedBlock_requiresUiRestrictionAndIgnoredAppOp() {
        val status = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { true },
            ignoringBatteryOptimizations = { true },
            runAnyInBackgroundIgnored = { true }
        )

        assertTrue(status.backgroundRestricted)
        assertTrue(status.runAnyInBackgroundIgnored)
        assertTrue(status.blocksBackgroundPlayback)
    }

    @Test
    fun defaultForegroundAndErroredAppOpModes_areNotARestriction() {
        assertFalse(isRunAnyInBackgroundBlocked(AppOpsManager.MODE_ALLOWED))
        assertFalse(isRunAnyInBackgroundBlocked(AppOpsManager.MODE_DEFAULT))
        assertFalse(isRunAnyInBackgroundBlocked(AppOpsManager.MODE_FOREGROUND))
        assertFalse(isRunAnyInBackgroundBlocked(AppOpsManager.MODE_ERRORED))
        assertTrue(isRunAnyInBackgroundBlocked(AppOpsManager.MODE_IGNORED))
    }

    @Test
    fun oemScreenOffCleanup_onlyWarnsWhenSettingIsExplicitlyOn() {
        val unknown = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { false },
            ignoringBatteryOptimizations = { true },
            oemScreenOffCleanupEnabled = { null }
        )
        assertNull(unknown.oemScreenOffCleanupEnabled)
        assertFalse(unknown.oemScreenOffCleanupActive)

        val disabled = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { false },
            ignoringBatteryOptimizations = { true },
            oemScreenOffCleanupEnabled = { false }
        )
        assertEquals(false, disabled.oemScreenOffCleanupEnabled)
        assertFalse(disabled.oemScreenOffCleanupActive)

        val enabled = resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION_CODES.P,
            backgroundRestricted = { false },
            ignoringBatteryOptimizations = { true },
            oemScreenOffCleanupEnabled = { true }
        )
        assertEquals(true, enabled.oemScreenOffCleanupEnabled)
        assertTrue(enabled.oemScreenOffCleanupActive)
    }

    @Test
    fun parseOemScreenOffCleanupEnabled_readsCommonToggleEncodings() {
        assertNull(parseOemScreenOffCleanupEnabled(null))
        assertNull(parseOemScreenOffCleanupEnabled(""))
        assertNull(parseOemScreenOffCleanupEnabled("null"))
        assertNull(parseOemScreenOffCleanupEnabled("maybe"))
        assertEquals(true, parseOemScreenOffCleanupEnabled("1"))
        assertEquals(true, parseOemScreenOffCleanupEnabled("true"))
        assertEquals(true, parseOemScreenOffCleanupEnabled("ON"))
        assertEquals(false, parseOemScreenOffCleanupEnabled("0"))
        assertEquals(false, parseOemScreenOffCleanupEnabled("false"))
        assertEquals(false, parseOemScreenOffCleanupEnabled("off"))
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
