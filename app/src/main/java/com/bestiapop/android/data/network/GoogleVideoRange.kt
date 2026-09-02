package com.bestiapop.android.data.network

/**
 * Googlevideo rejects open-ended Range and also a single GET that covers the whole `clen`.
 * Playback bounds unset Media3 lengths with [remainingLength]. Download uses [nextChunkRange]
 * so each request is a closed partial Range.
 */
internal object GoogleVideoRange {
    const val DEFAULT_CHUNK_BYTES = 256 * 1024L

    fun remainingLength(
        host: String?,
        contentLengthParam: String?,
        position: Long
    ): Long? {
        if (host?.endsWith(".googlevideo.com") != true) return null
        val contentLength = contentLengthParam?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val remaining = contentLength - position
        return remaining.takeIf { it > 0L }
    }

    fun httpRangeHeader(
        host: String?,
        contentLengthParam: String?,
        startByte: Long
    ): String? {
        val remaining = remainingLength(host, contentLengthParam, startByte) ?: return null
        val lastByte = startByte + remaining - 1L
        return "bytes=$startByte-$lastByte"
    }

    fun nextChunkRange(
        host: String?,
        contentLengthParam: String?,
        startByte: Long,
        chunkSize: Long = DEFAULT_CHUNK_BYTES
    ): String? {
        val remaining = remainingLength(host, contentLengthParam, startByte) ?: return null
        val contentLength = startByte + remaining
        val chunkEnd = (startByte + chunkSize.coerceAtLeast(1L) - 1L).coerceAtMost(contentLength - 1L)
        val end = if (startByte == 0L && chunkEnd == contentLength - 1L && contentLength > 1L) {
            contentLength - 2L
        } else {
            chunkEnd
        }
        return "bytes=$startByte-$end"
    }
}
