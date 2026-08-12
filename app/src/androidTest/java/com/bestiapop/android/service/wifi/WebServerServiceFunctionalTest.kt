package com.bestiapop.android.service.wifi

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.service.WebServerService
import com.bestiapop.android.testutil.DeviceAwakeRule
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level functional coverage for the real WiFi Service graph.
 *
 * Requests stay on localhost. Boundary-only host/size behavior remains covered by JVM tests; this
 * scenario proves Android foreground lifecycle, Ktor, managed storage, metadata parsing and Room.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WebServerServiceFunctionalTest {
    @get:Rule
    val deviceAwakeRule = DeviceAwakeRule()

    private lateinit var fixture: WebServerServiceTestFixture

    @Before
    fun setUp() {
        fixture = WebServerServiceTestFixture()
        fixture.prepare()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun realService_existingFilesUploadPersistenceAndStop_areIntegrated() {
        val running = fixture.startAndAwait()
        assertTrue(running.serverState.endsWith(":${WebServerService.PORT}"))
        assertTrue(running.serviceInfo.foreground)
        assertEquals(
            WebServerServiceTestContract.NOTIFICATION_CHANNEL_ID,
            running.notification.channelId
        )
        assertEquals(
            "Bestia Pop - Servidor WiFi Activo",
            running.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        )

        val beforeUpload = fixture.getExistingFiles()
        assertEquals(200, beforeUpload.code)
        val existingBefore = JSONArray(beforeUpload.body).toStringList()
        assertFalse(WebServerServiceTestContract.FILE_NAME.lowercase() in existingBefore)

        val upload = fixture.uploadGeneratedPcmWav()
        assertEquals(upload.body, 200, upload.code)
        assertTrue(upload.body.contains("\"status\":\"ok\""))
        assertTrue(upload.body.contains(WebServerServiceTestContract.FILE_NAME))

        val completed = fixture.awaitCompletedTransfer()
        assertEquals(WifiTransferState.DONE, completed.state)
        assertEquals(100, completed.progressPercent)
        val songId = requireNotNull(completed.songId)
        assertTrue(songId > 0L)
        assertEquals(WebServerServiceTestContract.FILE_NAME, completed.fileName)

        val song = fixture.awaitPersistedSong(songId)
        assertEquals(songId, song.id)
        assertEquals(completed.title, song.title)
        assertTrue(song.title.isNotBlank())
        assertTrue(song.durationMs > 0L)
        assertNotNull(song.uriString.takeIf(String::isNotBlank))

        val playable = fixture.verifyPlayable(song)
        assertTrue(playable.byteCount == -1L || playable.byteCount > 44L)
        assertTrue(playable.durationMs > 0)

        val afterUpload = fixture.getExistingFiles()
        assertEquals(200, afterUpload.code)
        assertTrue(
            WebServerServiceTestContract.FILE_NAME.lowercase() in
                JSONArray(afterUpload.body).toStringList()
        )

        fixture.deleteFixtureArtifacts()
        fixture.awaitFixtureRemoved(song.id)

        fixture.stopAndAwait()
        assertNull(WebServerService.serverState.value)
        assertFalse(fixture.serviceInfo()?.foreground == true)
        assertNull(fixture.webServerNotification())
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map(::getString)
}
