package com.bestiapop.android.service.concurrent

import android.app.ActivityManager
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.CandidateDownloadState
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.service.wifi.WebServerServiceTestContract
import com.bestiapop.android.service.wifi.WebServerServiceTestFixture
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.ui.download.CatalogDownloadTestContract
import com.bestiapop.android.ui.download.CatalogDownloadTestFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device E2E coverage for real playback overlapping the app's two independent I/O pipelines.
 *
 * Both scenarios use one instrumentation process and localhost-only transport. Explicit network
 * gates prove overlap without fixed sleeps; bounded polling observes asynchronous Android state.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ConcurrentServiceOperationsE2ETest {
    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    @Test
    fun playbackContinuesWhileWebServerReceivesAndPersistsWav() {
        WebServerServiceTestFixture().use { webServer ->
            webServer.prepare()
            ConcurrentPlaybackTestFixture().use { playback ->
                playback.start(launchForegroundHost = false)
                val playbackPid = playback.assertPlaybackForeground()

                val runningWebServer = webServer.startAndAwait()
                assertWebServerForeground(runningWebServer.serviceInfo, webServer)

                ControlledWifiUpload().use { upload ->
                    val positionBeforeUpload = playback.position()
                    upload.start()
                    upload.awaitFirstChunkSent()

                    val uploading = awaitConcurrentValue(
                        "WiFi transfer makes partial progress",
                        diagnostics = {
                            "${webServer.diagnostics()}, ${upload.diagnostics()}, " +
                                playback.diagnostics()
                        }
                    ) {
                        WebServerService.transfers.value.firstOrNull {
                            it.fileName == WebServerServiceTestContract.FILE_NAME &&
                                it.state == WifiTransferState.UPLOADING &&
                                it.progressPercent in 1..99
                        }
                    }
                    assertTrue(uploading.progressPercent in 1..99)

                    playback.assertPositionAdvancesFrom(
                        positionBeforeUpload,
                        "blocked localhost WAV upload"
                    )
                    assertEquals(playbackPid, playback.assertPlaybackForeground())
                    assertWebServerForeground(
                        checkNotNull(webServer.serviceInfo()),
                        webServer
                    )

                    upload.release()
                    assertEquals(200, upload.awaitResponseCode())
                }

                val completed = webServer.awaitCompletedTransfer()
                assertEquals(WifiTransferState.DONE, completed.state)
                assertEquals(100, completed.progressPercent)
                val songId = checkNotNull(completed.songId)
                val persistedSong = webServer.awaitPersistedSong(songId)
                assertEquals(songId, persistedSong.id)
                assertTrue(persistedSong.durationMs > 0L)
                assertTrue(webServer.verifyPlayable(persistedSong).durationMs > 0)

                val positionAfterUpload = playback.position()
                playback.assertPositionAdvancesFrom(
                    positionAfterUpload,
                    "WiFi upload persistence"
                )
                assertEquals(playbackPid, playback.assertPlaybackForeground())

                webServer.deleteFixtureArtifacts()
                webServer.awaitFixtureRemoved(songId)
                webServer.stopAndAwait()
                assertTrue(webServer.serviceInfo()?.foreground != true)
                assertTrue(webServer.webServerNotification() == null)
            }
        }
    }

    @Test
    fun playbackContinuesWhileOnlinePipelineDownloadsFromMockWebServer() {
        CatalogDownloadTestFixture().use { download ->
            download.prepare()
            ConcurrentPlaybackTestFixture().use { playback ->
                playback.start(launchForegroundHost = true)
                val playbackPid = playback.assertPlaybackForeground()
                val positionBeforeDownload = playback.position()

                val viewModel = playback.viewModel()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    viewModel.downloadOnlineTrack(
                        OnlineCatalogTrack(
                            id = "4242",
                            title = CatalogDownloadTestContract.TITLE,
                            artist = CatalogDownloadTestContract.ARTIST,
                            album = CatalogDownloadTestContract.ALBUM,
                            durationMs = 3_000L,
                            provider = "Deezer",
                            trackNumber = CatalogDownloadTestContract.TRACK_NUMBER
                        )
                    )
                }

                download.awaitAudioRequest()
                awaitConcurrent(
                    "online download waits at the gated audio response",
                    diagnostics = {
                        "${download.diagnostic()}, ${playback.diagnostics()}"
                    }
                ) {
                    download.isDownloadingAt(75)
                }
                awaitConcurrent(
                    "online download notification",
                    diagnostics = download::diagnostic
                ) {
                    downloadNotificationIsVisible()
                }

                playback.assertPositionAdvancesFrom(
                    positionBeforeDownload,
                    "gated MockWebServer audio response"
                )
                assertEquals(playbackPid, playback.assertPlaybackForeground())

                download.releaseAudioDownload()
                awaitConcurrent(
                    "online pipeline SUCCESS",
                    diagnostics = {
                        "${download.diagnostic()}, ${playback.diagnostics()}"
                    }
                ) {
                    download.isDownloadComplete()
                }

                val persistedSong = download.persistedSong()
                download.verifyPersistedSongAndFile(persistedSong)
                val completed = (
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext.applicationContext as BestiaPopApplication
                    ).processDownloads.findByTrack(
                    downloadId = CatalogDownloadTestContract.DOWNLOAD_ID,
                    artist = CatalogDownloadTestContract.ARTIST,
                    title = CatalogDownloadTestContract.TITLE
                )
                assertNotNull(completed)
                assertEquals(CandidateDownloadState.SUCCESS, completed?.state)
                assertEquals(persistedSong.id, completed?.resultSongId)

                val positionAfterDownload = playback.position()
                playback.assertPositionAdvancesFrom(
                    positionAfterDownload,
                    "completed online download"
                )
                assertEquals(playbackPid, playback.assertPlaybackForeground())
            }
        }
    }

    private fun assertWebServerForeground(
        service: ActivityManager.RunningServiceInfo,
        fixture: WebServerServiceTestFixture
    ) {
        assertTrue(service.foreground)
        val notification = fixture.webServerNotification()
        assertNotNull(notification)
        assertEquals(
            WebServerServiceTestContract.NOTIFICATION_CHANNEL_ID,
            notification?.channelId
        )
    }

    private fun downloadNotificationIsVisible(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.id == DownloadNotificationHelper.NOTIFICATION_ID }
    }
}
