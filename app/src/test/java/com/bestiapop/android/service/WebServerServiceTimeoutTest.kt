package com.bestiapop.android.service

import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import org.junit.Assert.assertEquals
import org.junit.Test

class WebServerServiceTimeoutTest {

    @Test
    fun timeout_marksOnlyActiveTransfersAsRecoverableErrors() {
        val transfers = listOf(
            transfer("pending", WifiTransferState.PENDING),
            transfer("uploading", WifiTransferState.UPLOADING),
            transfer("processing", WifiTransferState.PROCESSING),
            transfer("done", WifiTransferState.DONE),
            transfer("error", WifiTransferState.ERROR, "original")
        )

        val result = markWifiTransfersTimedOut(transfers).associateBy(WifiTransferItem::id)

        listOf("pending", "uploading", "processing").forEach { id ->
            assertEquals(WifiTransferState.ERROR, result.getValue(id).state)
            assertEquals(WIFI_TIMEOUT_MESSAGE, result.getValue(id).errorMessage)
        }
        assertEquals(WifiTransferState.DONE, result.getValue("done").state)
        assertEquals("original", result.getValue("error").errorMessage)
    }

    private fun transfer(
        id: String,
        state: WifiTransferState,
        error: String? = null
    ) = WifiTransferItem(
        id = id,
        fileName = "$id.mp3",
        title = id,
        artist = "Artist",
        state = state,
        errorMessage = error
    )
}
