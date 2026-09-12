package com.bestiapop.android.data.repository

import android.content.Context
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.DownloadConflictPolicy
import com.bestiapop.android.data.model.DownloadPhase
import com.bestiapop.android.data.model.DuplicateSongException
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.model.youtubeSearchQuery
import com.bestiapop.android.data.network.GoogleVideoRange
import com.bestiapop.android.data.network.YouTubeExtractor
import com.bestiapop.android.data.network.YouTubeStreamResult
import com.bestiapop.android.data.stream.BestiaPopMediaCache
import com.bestiapop.android.data.stream.StreamResolver
import com.bestiapop.android.data.util.UploadNameSanitizer
import com.bestiapop.android.data.util.copyTransferToFile
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.pickPersistedAlbumName
import com.bestiapop.android.domain.util.pickPersistedArtistName
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

private const val MAX_DOWNLOAD_ATTEMPTS = 5
private const val DOWNLOAD_RETRY_BACKOFF_MS = 750L

internal class OnlineTrackDownloadOperator(
    private val context: Context,
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val audioStore: RepositoryFileStore,
    private val streamResolver: StreamResolver,
    private val downloadCallFactory: Call.Factory,
    private val downloadRetryDelay: suspend (Long) -> Unit,
    private val metadataSource: RepositoryMetadataSource,
    private val identityCache: RepositoryIdentityCache,
    private val onSongSaved: suspend (Song) -> Unit
) {
    suspend fun downloadAndSaveOnlineTrack(
        track: OnlineCatalogTrack,
        onProgress: ((DownloadPhase) -> Unit)?,
        conflictPolicy: DownloadConflictPolicy?
    ): Song = withContext(Dispatchers.IO) {
        onProgress?.invoke(DownloadPhase.Searching)

        val ytStream = resolveTrackStreamForDownload(track, forceRefresh = true).getOrElse { e ->
            throw IOException(e.message ?: "No se pudo resolver el stream de YouTube")
        }
        val downloadUrl = ytStream.audioUrl
        val userAgentToUse = ytStream.userAgent
        var identity = track.identity.copy(
            title = track.title.takeUnless { isPlaceholderTitle(it) }.orEmpty(),
            artist = track.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) }.orEmpty()
        ).mergePreferring(ytStream.identity)

        var overwriteTarget: Song? = null
        when (conflictPolicy) {
            is DownloadConflictPolicy.Overwrite -> {
                overwriteTarget = musicDao.getSongById(conflictPolicy.existingSongId)
                    ?: throw IllegalArgumentException("Canción a sobrescribir no encontrada")
                identity = identity.copy(
                    title = overwriteTarget.title,
                    artist = identity.artist.ifBlank { overwriteTarget.artist }
                )
            }

            is DownloadConflictPolicy.SaveAs -> {
                identity = identity.copy(
                    title = conflictPolicy.newTitle.trim().ifBlank { identity.title }
                )
            }

            null -> {
                val existing = identityCache.findSongByArtistTitle(identity.artist, identity.title)
                if (existing != null) {
                    throw DuplicateSongException(existing, track.copy(identity = identity))
                }
            }
        }
        var finalTitle = identity.title
        var finalArtist = identity.artist
        var finalArtwork = identity.artworkUri
        var finalDurationMs = identity.durationMs
        var finalTrackNumber = identity.trackNumber

        val ext = when {
            downloadUrl.contains("audio/mp4") || downloadUrl.contains("mime=audio%2Fmp4") || downloadUrl.endsWith(".m4a") -> "m4a"
            downloadUrl.contains("audio/webm") || downloadUrl.contains("mime=audio%2Fwebm") || downloadUrl.endsWith(".webm") -> "webm"
            downloadUrl.endsWith(".aac") -> "aac"
            downloadUrl.endsWith(".ogg") -> "ogg"
            downloadUrl.endsWith(".wav") -> "wav"
            else -> "m4a"
        }

        val sanitizedName = UploadNameSanitizer.sanitize(finalArtist + "_" + finalTitle)
        val displayName = "$sanitizedName.$ext"
        overwriteTarget?.let { target ->
            audioStore.delete(audioStore.canonicalize(target.uriString, target.folderPath))
        }
        val pendingWrite = audioStore.prepareWrite(displayName)
        val file = pendingWrite.stagingFile
        if (file.exists()) {
            file.delete()
        }

        onProgress?.invoke(DownloadPhase.Downloading(finalTitle))

        var currentUrl = downloadUrl
        var downloadedBytes = 0L
        var expectedTotalBytes = -1L
        var attempts = 0
        var downloadSuccess = false
        var lastResponseCode = 0
        var lastHttpError: String? = null

        val cachedPrefix = BestiaPopMediaCache.copyCachedPrefixToFile(
            context = context,
            videoId = ytStream.videoId,
            destination = file
        )
        if (cachedPrefix > 0L) {
            downloadedBytes = cachedPrefix
            val parsedUrl = currentUrl.toHttpUrlOrNull()
            val clen = GoogleVideoRange.remainingLength(
                parsedUrl?.host,
                parsedUrl?.queryParameter("clen"),
                0L
            )
            if (clen != null && downloadedBytes >= clen) {
                expectedTotalBytes = clen
                downloadSuccess = true
            }
        }

        while (attempts < MAX_DOWNLOAD_ATTEMPTS && !downloadSuccess) {
            attempts++
            lastResponseCode = 0
            try {
                var continueChunks = true
                while (continueChunks) {
                    continueChunks = false
                    val chunkStart = downloadedBytes
                    val reqBuilder = Request.Builder()
                        .url(currentUrl)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", userAgentToUse)

                    val parsedUrl = currentUrl.toHttpUrlOrNull()
                    val googlevideoClen = GoogleVideoRange.remainingLength(
                        parsedUrl?.host,
                        parsedUrl?.queryParameter("clen"),
                        0L
                    )
                    val rangeHeader = GoogleVideoRange.nextChunkRange(
                        parsedUrl?.host,
                        parsedUrl?.queryParameter("clen"),
                        downloadedBytes
                    ) ?: downloadedBytes.takeIf { it > 0L }?.let { "bytes=$it-" }
                    if (rangeHeader != null) {
                        reqBuilder.header("Range", rangeHeader)
                    }

                    downloadCallFactory.newCall(reqBuilder.build()).useCancellable { response ->
                        lastResponseCode = response.code
                        if (!response.isSuccessful) {
                            lastHttpError =
                                "HTTP ${response.code} (${response.message.ifBlank { "Error de servidor" }})"
                            return@useCancellable
                        }
                        val body = response.body
                        if (body == null) {
                            lastHttpError = "Respuesta sin cuerpo"
                            return@useCancellable
                        }
                        // 200 to a ranged request = the server ignored Range and is resending the whole body
                        val resuming = response.code == 206 && downloadedBytes > 0
                        if (!resuming) downloadedBytes = 0L
                        val bodyLength = body.contentLength()
                        expectedTotalBytes = googlevideoClen
                            ?: if (bodyLength > 0) downloadedBytes + bodyLength else -1L

                        body.byteStream().use { input ->
                            val baseBytes = downloadedBytes
                            val cacheChunkCapacity = 512 * 1024
                            val cacheChunkBuffer = ByteArray(cacheChunkCapacity)
                            var cacheBufferCount = 0
                            var cacheBufferStartPos = baseBytes
                            copyTransferToFile(
                                input = input,
                                destination = file,
                                append = resuming,
                                bufferSize = 65536,
                                onChunk = { relPos, buf, len ->
                                    if (cacheBufferCount == 0) {
                                        cacheBufferStartPos = baseBytes + relPos
                                    }
                                    var remainingLen = len
                                    var bufOffset = 0
                                    while (remainingLen > 0) {
                                        val space = cacheChunkCapacity - cacheBufferCount
                                        val toCopy = minOf(remainingLen, space)
                                        System.arraycopy(buf, bufOffset, cacheChunkBuffer, cacheBufferCount, toCopy)
                                        cacheBufferCount += toCopy
                                        bufOffset += toCopy
                                        remainingLen -= toCopy
                                        if (cacheBufferCount >= cacheChunkCapacity) {
                                            BestiaPopMediaCache.writeChunkToCache(
                                                context = context,
                                                videoId = ytStream.videoId,
                                                position = cacheBufferStartPos,
                                                bytes = cacheChunkBuffer,
                                                offset = 0,
                                                length = cacheBufferCount
                                            )
                                            cacheBufferStartPos += cacheBufferCount
                                            cacheBufferCount = 0
                                        }
                                    }
                                }
                            ) { copied ->
                                downloadedBytes = baseBytes + copied
                            }
                            if (cacheBufferCount > 0) {
                                BestiaPopMediaCache.writeChunkToCache(
                                    context = context,
                                    videoId = ytStream.videoId,
                                    position = cacheBufferStartPos,
                                    bytes = cacheChunkBuffer,
                                    offset = 0,
                                    length = cacheBufferCount
                                )
                                cacheBufferCount = 0
                            }
                        }
                        val minAudioBytes = 200_000L
                        downloadSuccess = if (expectedTotalBytes > 0L) {
                            downloadedBytes >= expectedTotalBytes
                        } else {
                            downloadedBytes >= minAudioBytes
                        }
                        if (!downloadSuccess) {
                            val targetStr = if (expectedTotalBytes > 0L) "/$expectedTotalBytes" else ""
                            lastHttpError = "Descarga incompleta ($downloadedBytes$targetStr bytes)"
                        }
                    }
                    continueChunks = googlevideoClen != null &&
                            !downloadSuccess &&
                            lastResponseCode == 206 &&
                            downloadedBytes > chunkStart &&
                            downloadedBytes < googlevideoClen
                }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                lastHttpError = e.localizedMessage ?: "Error de red"
                downloadedBytes = if (file.exists()) file.length() else 0L
            }

            if (!downloadSuccess && attempts < MAX_DOWNLOAD_ATTEMPTS) {
                if (lastResponseCode == 416) {
                    downloadedBytes = 0L
                    expectedTotalBytes = -1L
                    if (file.exists()) {
                        file.delete()
                    }
                } else if (lastResponseCode == 403 || lastResponseCode == 410) {
                    val refreshed = resolveTrackStreamForDownload(track, forceRefresh = true).getOrNull()
                    refreshed?.let {
                        currentUrl = it.audioUrl
                        downloadedBytes = 0L
                        expectedTotalBytes = -1L
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
                try {
                    downloadRetryDelay(DOWNLOAD_RETRY_BACKOFF_MS * attempts)
                } catch (e: CancellationException) {
                    file.delete()
                    throw e
                }
            }
        }

        if (!downloadSuccess) {
            file.delete()
            val errorDetails = if (!lastHttpError.isNullOrBlank()) " ($lastHttpError)" else ""
            throw IOException(
                "No se pudo descargar el archivo de audio de YouTube$errorDetails. Verifica tu conexión a internet o intenta con otra canción."
            )
        }

        val savedRef = audioStore.canonicalize(pendingWrite.publish())

        onProgress?.invoke(DownloadPhase.FetchingMetadata)

        var finalAlbum = track.album
        val hasUsefulAlbum = !IdentifyRanking.isGenericAlbum(finalAlbum)
        val hasArtwork = !finalArtwork.isNullOrEmpty()

        if (!hasUsefulAlbum || !hasArtwork) {
            val fullMeta = metadataSource.fetchFullTrackMetadata(finalArtist, finalTitle)
            if (fullMeta != null) {
                val lookedUpAlbum = fullMeta.album
                if (lookedUpAlbum.isNotBlank() && !IdentifyRanking.isGenericAlbum(lookedUpAlbum)) {
                    finalAlbum = lookedUpAlbum
                }
                if (finalArtwork.isNullOrEmpty() && !fullMeta.artworkUri.isNullOrEmpty()) {
                    finalArtwork = fullMeta.artworkUri
                }
                if (fullMeta.artist.isNotBlank() && IdentifyRanking.isPlaceholderArtist(finalArtist)) {
                    finalArtist = fullMeta.artist
                }
                if (finalDurationMs <= 0 && fullMeta.durationMs > 0) {
                    finalDurationMs = fullMeta.durationMs
                }
                if (finalTrackNumber <= 0 && fullMeta.trackNumber > 0) {
                    finalTrackNumber = fullMeta.trackNumber
                }
            }
        }

        if (IdentifyRanking.isGenericAlbum(finalAlbum)) {
            finalAlbum = IdentifyRanking.fallbackAlbum(
                finalArtist,
                overwriteTarget?.album.orEmpty()
            )
        }

        val library = identityCache.getSongs()
        finalAlbum = pickPersistedAlbumName(
            library = library,
            proposedAlbum = finalAlbum,
            proposedArtist = finalArtist,
            sourceAlbum = overwriteTarget?.album.orEmpty(),
            isGeneric = IdentifyRanking::isGenericAlbum
        )
        val albumArtists = library
            .filter { albumNamesMatch(it.album, finalAlbum) }
            .map { it.artist } + listOfNotNull(overwriteTarget?.artist)
        finalArtist = pickPersistedArtistName(albumArtists, finalArtist)

        val lyrics = metadataSource.fetchLyrics(finalArtist, finalTitle)

        onProgress?.invoke(DownloadPhase.Saving)

        if (overwriteTarget != null) {
            val updated = overwriteTarget.withIdentity(
                TrackIdentity(
                    title = finalTitle,
                    artist = finalArtist.ifBlank { overwriteTarget.artist },
                    album = finalAlbum,
                    artworkUri = finalArtwork ?: overwriteTarget.artworkUri,
                    durationMs = if (finalDurationMs > 0) finalDurationMs else overwriteTarget.durationMs,
                    trackNumber = if (finalTrackNumber > 0) finalTrackNumber else overwriteTarget.trackNumber
                )
            ).copy(
                uriString = savedRef.uriString,
                lyrics = lyrics ?: overwriteTarget.lyrics,
                folderPath = savedRef.folderPath
            )
            musicDao.updateSong(updated)
            identityCache.remember(updated)
            onSongSaved(updated)
            onProgress?.invoke(DownloadPhase.Overwritten)
            return@withContext updated
        }

        val song = Song(
            uriString = savedRef.uriString,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            genre = "Music",
            durationMs = if (finalDurationMs > 0) finalDurationMs else 180000L,
            year = 0,
            trackNumber = finalTrackNumber,
            artworkUri = finalArtwork,
            lyrics = lyrics,
            folderPath = savedRef.folderPath,
            dateAdded = System.currentTimeMillis()
        )

        val insertedId = insertOrUpdateSongByUri(database, musicDao, identityCache, song)
        val savedSong = song.copy(id = insertedId)
        onSongSaved(savedSong)

        onProgress?.invoke(DownloadPhase.Completed)
        return@withContext savedSong
    }

    suspend fun resolveTrackStreamForDownload(
        track: OnlineCatalogTrack,
        forceRefresh: Boolean = true
    ): Result<YouTubeStreamResult> {
        val queryOrId = YouTubeExtractor.resolveYouTubeQueryOrId(track)
        return streamResolver.resolveQuery(
            queryOrId = queryOrId,
            forceRefresh = forceRefresh,
            expected = track.identity,
            fallbackQuery = track.youtubeSearchQuery()
        )
    }
}
