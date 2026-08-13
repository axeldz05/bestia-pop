package com.bestiapop.android.service

import com.bestiapop.android.data.model.ActiveDownloadSource
import com.bestiapop.android.data.model.DownloadLane
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineDownloadServiceLauncherTest {

    @Test
    fun android14ExplicitDownload_usesUserInitiatedJob() {
        assertEquals(
            OnlineDownloadBackend.USER_INITIATED_JOB,
            onlineDownloadBackend(34, ActiveDownloadSource.CATALOG)
        )
    }

    @Test
    fun android13ExplicitDownload_usesForegroundService() {
        assertEquals(
            OnlineDownloadBackend.FOREGROUND_SERVICE,
            onlineDownloadBackend(33, ActiveDownloadSource.BATCH)
        )
    }

    @Test
    fun saveWhileListening_usesConstraintAwareBackgroundJob() {
        assertEquals(
            OnlineDownloadBackend.BACKGROUND_JOB,
            onlineDownloadBackend(36, ActiveDownloadSource.SAVE_WHILE_LISTENING)
        )
    }

    @Test
    fun backendOwnsItsDownloadLane() {
        assertEquals(DownloadLane.AUTOSAVE, OnlineDownloadBackend.BACKGROUND_JOB.lane)
        assertEquals(DownloadLane.EXPLICIT, OnlineDownloadBackend.USER_INITIATED_JOB.lane)
        assertEquals(DownloadLane.EXPLICIT, OnlineDownloadBackend.FOREGROUND_SERVICE.lane)
    }

    @Test
    fun leaseRelease_isIdempotent() {
        var releases = 0
        val lease = OnlineDownloadLease(OnlineDownloadBackend.USER_INITIATED_JOB) {
            releases++
        }

        lease.close()
        lease.close()

        assertEquals(1, releases)
    }
}
