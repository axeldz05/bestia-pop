package com.bestiapop.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifyExecutionLauncherTest {

    @Test
    fun android14_usesUserInitiatedJob() {
        assertEquals(
            IdentifyExecutionBackend.USER_INITIATED_JOB,
            identifyExecutionBackend(34)
        )
    }

    @Test
    fun android13_usesForegroundService() {
        assertEquals(
            IdentifyExecutionBackend.FOREGROUND_SERVICE,
            identifyExecutionBackend(33)
        )
    }

    @Test
    fun leaseRelease_isIdempotent() {
        var releases = 0
        val lease = IdentifyExecutionLease(IdentifyExecutionBackend.USER_INITIATED_JOB) {
            releases++
        }
        lease.close()
        lease.close()
        assertEquals(1, releases)
    }
}
