package com.bestiapop.android.service

import android.app.job.JobParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineDownloadServiceSupportTest {

    @Test
    fun userStop_dismissesAndDoesNotReschedule() {
        var dismissed = false
        var interrupted = false

        val shouldReschedule = handleOnlineDownloadJobStop(
            backend = OnlineDownloadBackend.BACKGROUND_JOB,
            userStopped = true,
            dismissRunning = { dismissed = true },
            interruptNow = { interrupted = true }
        )

        assertTrue(dismissed)
        assertFalse(interrupted)
        assertFalse(shouldReschedule)
    }

    @Test
    fun systemStop_interruptsAndRequestsReschedule() {
        var dismissed = false
        var interrupted = false

        val shouldReschedule = handleOnlineDownloadJobStop(
            backend = OnlineDownloadBackend.BACKGROUND_JOB,
            userStopped = false,
            dismissRunning = { dismissed = true },
            interruptNow = { interrupted = true }
        )

        assertFalse(dismissed)
        assertTrue(interrupted)
        assertTrue(shouldReschedule)
    }

    @Test
    fun stopReasons_haveStableDiagnosticLabels() {
        assertEquals(
            "background_restriction",
            onlineDownloadStopReasonName(JobParameters.STOP_REASON_BACKGROUND_RESTRICTION)
        )
        assertEquals(
            "user",
            onlineDownloadStopReasonName(JobParameters.STOP_REASON_USER)
        )
        assertEquals("other_999", onlineDownloadStopReasonName(999))
    }
}
