package com.bestiapop.android.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class YouTubeExtractionIntegrationTest {

    @Test
    fun extractAudioStream_testUserProvidedYouTubeLink() = runBlocking {
        val testUrl = "https://youtu.be/Ak7BQaaizlM"
        
        val extractResult = YouTubeExtractor.extractAudioStreamDetailed(testUrl)
        
        println("Extract Result: $extractResult")

        assertTrue(
            "Extraction should succeed for link $testUrl, but got: $extractResult",
            extractResult is YouTubeExtractResult.Success
        )

        val successRes = (extractResult as YouTubeExtractResult.Success).result
        assertNotNull("Audio stream URL should not be null", successRes.audioUrl)
        assertTrue("Audio stream URL should start with http", successRes.audioUrl.startsWith("http"))

        println("Extracted Title: ${successRes.title}")
        println("Extracted Audio URL: ${successRes.audioUrl}")
        println("User-Agent: ${successRes.userAgent}")

        // Test downloading 1 byte range from googlevideo CDN using extracted headers
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(successRes.audioUrl)
            .header("User-Agent", successRes.userAgent.removeSuffix(" gzip").trim())
            .header("Accept", "*/*")
            .header("Range", "bytes=0-100")
            .build()

        client.newCall(request).execute().use { response ->
            println("CDN HTTP Response Code: ${response.code}")
            assertTrue(
                "CDN response code should be 200 or 206, but got ${response.code} (Message: ${response.message})",
                response.code == 200 || response.code == 206
            )
        }
    }
}
