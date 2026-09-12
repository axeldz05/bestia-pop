package com.bestiapop.android.data.system

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.preferences.TelemetryPreferencesRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.testutil.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class SystemStabilityMonitorTest {

    @Test
    fun formatReason_mapsStandardReasonsCorrectly() {
        assertEquals("LOW_MEMORY", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals("ANR", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_ANR))
        assertEquals("CRASH_NATIVE", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("USER_REQUESTED", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("USER_STOPPED", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals("EXIT_SELF", SystemStabilityMonitor.formatReason(ApplicationExitInfo.REASON_EXIT_SELF))
    }

    @Test
    fun formatImportance_mapsProcessStatesCorrectly() {
        assertEquals("FOREGROUND", SystemStabilityMonitor.formatImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals("FOREGROUND_SERVICE", SystemStabilityMonitor.formatImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals("SERVICE", SystemStabilityMonitor.formatImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertEquals("CACHED", SystemStabilityMonitor.formatImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
    }

    @Test
    fun formatTrimMemoryLevel_onlyAcknowledgesSeverePressureSignals() {
        // Critical pressure levels must be formatted for logging (15, 10, 80, 60)
        assertEquals("RUNNING_CRITICAL", SystemStabilityMonitor.formatTrimMemoryLevel(15))
        assertEquals("RUNNING_LOW", SystemStabilityMonitor.formatTrimMemoryLevel(10))
        assertEquals("COMPLETE", SystemStabilityMonitor.formatTrimMemoryLevel(80))
        assertEquals("MODERATE", SystemStabilityMonitor.formatTrimMemoryLevel(60))

        // Benign/routine transitions (like UI hidden = 20, background = 40) must be ignored (return null)
        assertNull(SystemStabilityMonitor.formatTrimMemoryLevel(20))
        assertNull(SystemStabilityMonitor.formatTrimMemoryLevel(40))
    }

    @Test
    fun exceptionHierarchy_inheritsSystemProcessKilledException() {
        val lmk: SystemProcessKilledException = LowMemoryKillException("LMK killed process")
        assertEquals("LMK killed process", lmk.message)

        val excessive: SystemProcessKilledException = ExcessiveResourceUsageException("CPU/Battery overuse")
        assertEquals("CPU/Battery overuse", excessive.message)

        val anr: SystemProcessKilledException = ApplicationNotRespondingException("ANR freeze")
        assertEquals("ANR freeze", anr.message)

        val nativeCrash: SystemProcessKilledException = NativeCrashKillException("SIGSEGV")
        assertEquals("SIGSEGV", nativeCrash.message)
    }

    @Test
    fun checkHistoricalExitReasons_whenTelemetryDisabled_doesNotInvokeReporter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            TelemetryPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(TelemetryPreferencesRepository.KEY_TELEMETRY_ENABLED, false).commit()

        SystemStabilityMonitor.checkHistoricalExitReasons(context) { _, _ ->
            fail("Reporter must not be called when telemetry is disabled")
        }
    }

    @Test
    fun inFlightTelemetry_whenCrashReporterDisabled_doesNotThrow() {
        CrashReporter.isEnabled = false
        // Must safely early return without recording or failing
        SystemStabilityMonitor.recordMemoryTrim(15)
        SystemStabilityMonitor.recordLowMemory()
        CrashReporter.isEnabled = true
    }
}
