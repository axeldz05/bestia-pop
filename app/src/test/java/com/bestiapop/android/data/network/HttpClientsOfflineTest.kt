package com.bestiapop.android.data.network

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class HttpClientsOfflineTest {

    @After
    fun tearDown() {
        HttpClients.isOfflineMode = { false }
    }

    @Test
    fun offlineMode_blocksApiRequest() {
        HttpClients.isOfflineMode = { true }
        val client = HttpClients.api
        val request = Request.Builder().url("https://example.com/test").build()

        assertThrows(IOException::class.java) {
            client.newCall(request).execute()
        }
    }

    @Test
    fun offlineMode_blocksTransferRequest() {
        HttpClients.isOfflineMode = { true }
        val client = HttpClients.transfer
        val request = Request.Builder().url("https://example.com/test").build()

        assertThrows(IOException::class.java) {
            client.newCall(request).execute()
        }
    }
}
