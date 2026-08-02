package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.OnlineCatalogTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeLinkFetcher {

    suspend fun fetchTrackFromUrlDetailed(rawUrl: String): Pair<OnlineCatalogTrack?, String?> = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return@withContext Pair(null, "Ingresa un enlace de YouTube")

        val extractRes = YouTubeExtractor.extractAudioStreamDetailed(trimmed)
        if (extractRes is YouTubeExtractResult.Success) {
            val ytStream = extractRes.result
            val track = OnlineCatalogTrack(
                id = ytStream.videoId,
                title = ytStream.title,
                artist = ytStream.artist,
                album = "YouTube Music",
                artworkUrl = ytStream.artworkUrl,
                durationMs = ytStream.durationMs,
                audioUrl = ytStream.audioUrl,
                provider = "YouTube",
                userAgent = ytStream.userAgent
            )

            return@withContext Pair(track, null)
        } else if (extractRes is YouTubeExtractResult.Error) {
            return@withContext Pair(null, extractRes.message)
        }

        return@withContext Pair(null, "No se pudo procesar el enlace de YouTube")
    }
}
