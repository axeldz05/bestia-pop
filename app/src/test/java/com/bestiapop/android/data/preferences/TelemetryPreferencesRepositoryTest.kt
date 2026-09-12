package com.bestiapop.android.data.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class TelemetryPreferencesRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(
            TelemetryPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        CrashReporter.isEnabled = true
    }

    @Test
    fun defaultTelemetry_isEnabled() {
        val repository = TelemetryPreferencesRepository(context)
        assertTrue(repository.initialTelemetryEnabled)
        assertTrue(TelemetryPreferencesRepository.isTelemetryEnabledSync(context))
        assertTrue(CrashReporter.isEnabled)
    }

    @Test
    fun setTelemetryEnabled_updatesPreferencesAndCrashReporter() = runTest {
        val repository = TelemetryPreferencesRepository(context)

        repository.setTelemetryEnabled(false)
        assertFalse(repository.initialTelemetryEnabled)
        assertFalse(TelemetryPreferencesRepository.isTelemetryEnabledSync(context))
        assertFalse(CrashReporter.isEnabled)
        assertEquals(false, repository.telemetryEnabledFlow.first())

        repository.setTelemetryEnabled(true)
        assertTrue(repository.initialTelemetryEnabled)
        assertTrue(TelemetryPreferencesRepository.isTelemetryEnabledSync(context))
        assertTrue(CrashReporter.isEnabled)
        assertEquals(true, repository.telemetryEnabledFlow.first())
    }
}
