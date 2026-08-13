package com.bestiapop.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.WifiTransferItem
import com.bestiapop.android.data.model.WifiTransferState
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.UploadNameSanitizer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID

internal const val WIFI_SYNC_MAX_UPLOAD_BYTES = 512L * 1024 * 1024
private const val UPLOAD_TOO_LARGE_MESSAGE = "Archivo demasiado grande"

internal data class WifiPendingUpload(
    val stagingFile: File,
    val publish: () -> String,
    val deletePartial: (publishedPath: String?) -> Unit
)

internal data class WifiPersistedUpload(
    val identity: TrackIdentity,
    val songId: Long
)

/**
 * Small testable boundary around the WiFi HTTP contract. Android storage and Room stay behind
 * callbacks so production and localhost tests execute the exact same routes and stream limiter.
 */
internal class WifiSyncHttpBoundary(
    private val port: Int,
    private val advertisedHost: () -> String?,
    private val dashboardHtml: () -> String,
    private val listLibraryNames: suspend () -> Collection<String>,
    private val listManagedNames: () -> Collection<String>,
    private val prepareWrite: (safeName: String) -> WifiPendingUpload,
    private val persistUpload: suspend (publishedPath: String, safeName: String) -> WifiPersistedUpload,
    private val onTransfer: (WifiTransferItem) -> Unit,
    private val onFailure: (error: Throwable, phase: String, transferId: String) -> Unit,
    private val maxUploadBytes: Long = WIFI_SYNC_MAX_UPLOAD_BYTES,
    private val newTransferId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    init {
        require(port in 1..65535) { "Invalid WiFi Sync port: $port" }
        require(maxUploadBytes > 0L) { "Upload limit must be positive" }
    }

    fun install(application: Application) {
        application.routing {
            get("/") {
                call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                call.response.header("Pragma", "no-cache")
                call.response.header("Expires", "0")
                call.respondText(dashboardHtml(), ContentType.Text.Html)
            }

            get("/existing-files") {
                call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                val existing = (listManagedNames() + listLibraryNames())
                    .asSequence()
                    .map(UploadNameSanitizer::sanitize)
                    .filter(String::isNotBlank)
                    .map { it.lowercase(Locale.ROOT) }
                    .distinct()
                    .sorted()
                    .toList()
                call.respondText(
                    existing.joinToString(prefix = "[", postfix = "]") { jsonString(it) },
                    ContentType.Application.Json
                )
            }

            post("/upload-file") {
                call.response.header("Connection", "close")
                call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")

                if (!isAllowedHost(call.request.headers["Host"])) {
                    call.respondJsonError(HttpStatusCode.Forbidden, "Origen no permitido")
                    return@post
                }

                val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxUploadBytes) {
                    call.respondJsonError(HttpStatusCode.PayloadTooLarge, UPLOAD_TOO_LARGE_MESSAGE)
                    return@post
                }

                val rawName = call.request.queryParameters["name"]
                    ?: "audio_${nowMillis()}.mp3"
                val safeName = UploadNameSanitizer.sanitize(rawName)
                    .ifBlank { "audio_${nowMillis()}.mp3" }
                val transferId = newTransferId()
                var transfer = WifiTransferItem(
                    id = transferId,
                    fileName = safeName,
                    title = safeName.substringBeforeLast("."),
                    artist = "Recibiendo…",
                    state = WifiTransferState.UPLOADING,
                    progressPercent = 0
                )

                fun emit(next: WifiTransferItem) {
                    transfer = next
                    onTransfer(next)
                }

                emit(transfer)
                var pendingUpload: WifiPendingUpload? = null
                var publishedPath: String? = null
                var committed = false
                var phase = "transfer"
                try {
                    pendingUpload = prepareWrite(safeName)
                    var bytesWritten = 0L
                    val channel = call.receiveChannel()
                    pendingUpload.stagingFile.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            if (bytesWritten > maxUploadBytes - read) {
                                throw WifiUploadTooLargeException()
                            }
                            output.write(buffer, 0, read)
                            bytesWritten += read
                            if (declaredLength != null && declaredLength > 0L) {
                                val percent = ((bytesWritten * 100) / declaredLength)
                                    .toInt()
                                    .coerceIn(0, 99)
                                if (percent != transfer.progressPercent) {
                                    emit(
                                        transfer.copy(
                                            state = WifiTransferState.UPLOADING,
                                            progressPercent = percent
                                        )
                                    )
                                }
                            }
                        }
                        output.flush()
                    }

                    emit(
                        transfer.copy(
                            state = WifiTransferState.PROCESSING,
                            progressPercent = 100,
                            artist = "Procesando…"
                        )
                    )

                    phase = "save_upload"
                    publishedPath = pendingUpload.publish()
                    val persisted = persistUpload(publishedPath, safeName)
                    emit(
                        transfer.copy(
                            title = persisted.identity.title,
                            artist = persisted.identity.artist,
                            state = WifiTransferState.DONE,
                            progressPercent = 100,
                            songId = persisted.songId,
                            artworkUri = persisted.identity.artworkUri,
                            errorMessage = null
                        )
                    )
                    committed = true
                    call.respondText(
                        """{"status":"ok","filename":${jsonString(safeName)}}""",
                        ContentType.Application.Json
                    )
                } catch (_: WifiUploadTooLargeException) {
                    pendingUpload?.deletePartialSafely(publishedPath)
                    emit(
                        transfer.copy(
                            state = WifiTransferState.ERROR,
                            errorMessage = UPLOAD_TOO_LARGE_MESSAGE
                        )
                    )
                    call.respondJsonError(HttpStatusCode.PayloadTooLarge, UPLOAD_TOO_LARGE_MESSAGE)
                } catch (error: CancellationException) {
                    if (!committed) pendingUpload?.deletePartialSafely(publishedPath)
                    throw error
                } catch (error: Exception) {
                    // A client can disconnect after the file and Room row are already durable.
                    // Transporting the final JSON response must not roll that commit back.
                    if (committed) throw error
                    pendingUpload?.deletePartialSafely(publishedPath)
                    runCatching { onFailure(error, phase, transferId) }
                    val message = if (phase == "save_upload") {
                        "No se pudo guardar el archivo"
                    } else {
                        "Error de transferencia"
                    }
                    emit(
                        transfer.copy(
                            state = WifiTransferState.ERROR,
                            errorMessage = error.localizedMessage ?: message
                        )
                    )
                    call.respondJsonError(HttpStatusCode.InternalServerError, message)
                }
            }
        }
    }

    internal fun isAllowedHost(hostHeader: String?): Boolean {
        val authority = hostHeader?.trim()?.lowercase(Locale.ROOT) ?: return false
        val parsed = parseAuthority(authority) ?: return false
        if (parsed.second != port) return false
        val host = parsed.first
        val advertised = advertisedHost()
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase(Locale.ROOT)
        return host == advertised ||
            host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1"
    }

    private fun parseAuthority(authority: String): Pair<String, Int>? {
        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            val host = authority.substring(1, closeBracket)
            val portValue = authority.substring(closeBracket + 1)
                .removePrefix(":")
                .takeIf(String::isNotBlank)
                ?.toIntOrNull()
                ?: return null
            return host to portValue
        }
        if (authority.count { it == ':' } != 1) return null
        val host = authority.substringBefore(':').takeIf(String::isNotBlank) ?: return null
        val portValue = authority.substringAfter(':').toIntOrNull() ?: return null
        return host to portValue
    }
}

private class WifiUploadTooLargeException : Exception()

private fun WifiPendingUpload.deletePartialSafely(publishedPath: String?) {
    runCatching { deletePartial(publishedPath) }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    message: String
) {
    respondText(
        """{"status":"error","message":${jsonString(message)}}""",
        ContentType.Application.Json,
        status
    )
}

internal const val WIFI_TIMEOUT_MESSAGE =
    "Android detuvo el servidor por el límite de actividad en segundo plano"

internal fun markWifiTransfersTimedOut(
    transfers: List<WifiTransferItem>
): List<WifiTransferItem> = transfers.map { transfer ->
    if (transfer.state == WifiTransferState.PENDING ||
        transfer.state == WifiTransferState.UPLOADING ||
        transfer.state == WifiTransferState.PROCESSING
    ) {
        transfer.copy(
            state = WifiTransferState.ERROR,
            errorMessage = WIFI_TIMEOUT_MESSAGE
        )
    } else {
        transfer
    }
}

class WebServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var server: EmbeddedServer<*, *>? = null

    companion object {
        private const val TAG = "WebServerService"
        const val PORT = 8080
        private val _serverState = MutableStateFlow<String?>(null)
        val serverState: StateFlow<String?> = _serverState

        private val _transfers = MutableStateFlow<List<WifiTransferItem>>(emptyList())
        val transfers: StateFlow<List<WifiTransferItem>> = _transfers.asStateFlow()

        fun dismissTransfer(id: String) {
            _transfers.update { transfers -> transfers.filterNot { it.id == id } }
        }

        fun clearTransfers() {
            _transfers.update { emptyList() }
        }

        private fun upsertTransfer(item: WifiTransferItem) {
            _transfers.update { current ->
                val list = current.toMutableList()
                val index = list.indexOfFirst { it.id == item.id }
                if (index >= 0) list[index] = item else list.add(0, item)
                list
            }
        }

        fun getLocalIpAddress(@Suppress("UNUSED_PARAMETER") context: Context): String? {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        startServer()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "web_server_channel"
        val channel = NotificationChannel(
            channelId,
            "WiFi Web Server",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bestia Pop - Servidor WiFi Activo")
            .setContentText("Transferencia de música en red local habilitada")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()

        // Explicit type so it cannot silently inherit whatever the manifest declares.
        val fgsType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, 2001, notification, fgsType)
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                val repository = (application as BestiaPopApplication).musicRepository
                val audioStore = MusicFileStore(applicationContext)
                val ip = getLocalIpAddress(applicationContext) ?: "localhost"
                val boundary = WifiSyncHttpBoundary(
                    port = PORT,
                    advertisedHost = { ip },
                    dashboardHtml = ::getWebDashboardHtml,
                    listLibraryNames = {
                        repository.getAllSongsSync().mapNotNull { song ->
                            SongPathNormalizer.fileName(song.uriString, song.folderPath)
                                .takeIf(String::isNotBlank)
                        }
                    },
                    listManagedNames = audioStore::listManagedNames,
                    prepareWrite = { safeName ->
                        val pending = audioStore.prepareWrite(safeName)
                        WifiPendingUpload(
                            stagingFile = pending.stagingFile,
                            publish = pending::publish,
                            deletePartial = { publishedPath ->
                                pending.stagingFile.delete()
                                publishedPath?.let { path ->
                                    audioStore.delete(audioStore.canonicalize(path))
                                }
                            }
                        )
                    },
                    persistUpload = { path, safeName ->
                        val ref = audioStore.canonicalize(path)
                        val metadata = AudioFileMetadata.fromPath(
                            context = applicationContext,
                            path = ref.uriString,
                            fallbackTitle = safeName.substringBeforeLast("."),
                            artworkIdentifier = File(ref.uriString).name,
                            persistEmbeddedArtwork = repository::persistEmbeddedArtwork
                        )
                        val songId = repository.saveUploadedSong(
                            metadata.toSong(
                                uriString = ref.uriString,
                                folderPath = ref.folderPath
                            )
                        )
                        WifiPersistedUpload(
                            identity = metadata.identity,
                            songId = songId
                        )
                    },
                    onTransfer = ::upsertTransfer,
                    onFailure = { error, phase, transferId ->
                        Log.e(TAG, "WiFi upload failed phase=$phase", error)
                        CrashReporter.recordNonFatal(
                            error,
                            mapOf(
                                "wifi_phase" to phase,
                                "transfer_id" to transferId
                            )
                        )
                    }
                )

                server = embeddedServer(CIO, port = PORT) {
                    boundary.install(this)
                }.start(wait = false)

                _serverState.value = "$ip:$PORT"
            } catch (e: Exception) {
                e.printStackTrace()
                CrashReporter.recordNonFatal(
                    e,
                    mapOf("wifi_phase" to "server_start")
                )
                _serverState.value = null
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        CrashReporter.recordNonFatal(
            IllegalStateException("WiFi dataSync foreground timeout"),
            mapOf(
                "wifi_phase" to "foreground_timeout",
                "fgs_type" to fgsType.toString()
            )
        )
        _serverState.value = null
        _transfers.update(::markWifiTransfersTimedOut)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun getWebDashboardHtml(): String {
        val dollar = '$'
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Bestia Pop - WiFi Music Sync</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: linear-gradient(135deg, #0d0a1a, #161226, #24143e);
                        color: #f1f0f5;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        padding: 24px;
                    }
                    .card {
                        background: rgba(255, 255, 255, 0.06);
                        backdrop-filter: blur(20px);
                        border: 1px solid rgba(255, 255, 255, 0.12);
                        border-radius: 28px;
                        padding: 36px;
                        max-width: 720px;
                        width: 100%;
                        box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
                    }
                    .header { text-align: center; margin-bottom: 24px; }
                    h1 { font-size: 28px; font-weight: 800; color: #e2d9f3; margin-bottom: 6px; }
                    p { color: #a59bb5; font-size: 14px; line-height: 1.5; }
                    .drop-zone {
                        border: 2px dashed #9d4edd;
                        border-radius: 20px;
                        padding: 32px 20px;
                        background: rgba(157, 78, 221, 0.04);
                        text-align: center;
                        transition: all 0.3s ease;
                        margin-bottom: 24px;
                    }
                    .drop-zone.dragover {
                        background: rgba(157, 78, 221, 0.16);
                        border-color: #c77dff;
                        transform: scale(1.01);
                    }
                    .drop-icon { font-size: 44px; margin-bottom: 10px; display: block; }
                    .btn-group { display: flex; gap: 12px; justify-content: center; margin-top: 16px; flex-wrap: wrap; }
                    .btn {
                        background: linear-gradient(90deg, #7b2cbf, #9d4edd);
                        color: white;
                        border: none;
                        padding: 10px 22px;
                        font-size: 14px;
                        font-weight: 600;
                        border-radius: 12px;
                        cursor: pointer;
                        transition: transform 0.2s, box-shadow 0.2s;
                    }
                    .btn:hover { transform: translateY(-2px); box-shadow: 0 6px 18px rgba(157, 78, 221, 0.4); }
                    .btn-secondary {
                        background: rgba(255, 255, 255, 0.1);
                        border: 1px solid rgba(255, 255, 255, 0.2);
                    }
                    .btn-secondary:hover { background: rgba(255, 255, 255, 0.2); }
                    input[type="file"] { display: none; }
                    
                    .summary-bar {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        background: rgba(0, 0, 0, 0.3);
                        padding: 12px 18px;
                        border-radius: 14px;
                        font-size: 13px;
                        font-weight: 600;
                        margin-bottom: 16px;
                    }
                    .file-list {
                        max-height: 320px;
                        overflow-y: auto;
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                        padding-right: 4px;
                    }
                    .file-list::-webkit-scrollbar { width: 6px; }
                    .file-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 3px; }

                    .file-card {
                        background: rgba(255, 255, 255, 0.05);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 14px;
                        padding: 12px 16px;
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }
                    .file-info {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        font-size: 14px;
                    }
                    .file-name {
                        font-weight: 600;
                        color: #f1f0f5;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        max-width: 400px;
                    }
                    .file-meta { font-size: 12px; color: #a59bb5; }
                    .file-progress-bg {
                        height: 6px;
                        background: rgba(255, 255, 255, 0.1);
                        border-radius: 3px;
                        overflow: hidden;
                    }
                    .file-progress-fill {
                        height: 100%;
                        width: 0%;
                        background: linear-gradient(90deg, #9d4edd, #c77dff);
                        transition: width 0.2s ease;
                    }
                    .badge {
                        padding: 3px 8px;
                        border-radius: 8px;
                        font-size: 11px;
                        font-weight: 700;
                    }
                    .badge-pending { background: rgba(255, 255, 255, 0.15); color: #ccc; }
                    .badge-uploading { background: rgba(157, 78, 221, 0.3); color: #c77dff; }
                    .badge-success { background: rgba(112, 224, 0, 0.2); color: #70e000; }
                    .badge-skipped { background: rgba(255, 183, 3, 0.2); color: #ffb703; }
                    .badge-error { background: rgba(255, 0, 84, 0.2); color: #ff0054; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="header">
                        <h1>🎵 Bestia Pop</h1>
                        <p>Transferencia Directa Ultra-Rápida por WiFi (Streaming binario de alta velocidad)</p>
                    </div>

                    <div class="drop-zone" id="dropZone">
                        <span class="drop-icon">📁</span>
                        <div style="font-weight: 700; font-size: 16px; margin-bottom: 4px;">Arrastrá canciones o carpetas aquí</div>
                        <div style="font-size: 13px; color: #a59bb5;">Los archivos existentes se detectarán y omitirán automáticamente</div>
                        
                        <div class="btn-group">
                            <button class="btn" onclick="document.getElementById('fileInput').click()">📄 Elegir Archivos</button>
                            <button class="btn btn-secondary" onclick="document.getElementById('folderInput').click()">📁 Elegir Carpeta</button>
                        </div>
                        <input type="file" id="fileInput" multiple accept="audio/*">
                        <input type="file" id="folderInput" webkitdirectory directory multiple>
                    </div>

                    <div id="queueSection" style="display: none;">
                        <div class="summary-bar">
                            <span id="summaryText">Cargando cola...</span>
                            <span id="summaryStats" style="color: #c77dff;">0 / 0</span>
                        </div>

                        <div class="file-list" id="fileList"></div>
                    </div>
                </div>

                <script>
                    const dropZone = document.getElementById('dropZone');
                    const fileInput = document.getElementById('fileInput');
                    const folderInput = document.getElementById('folderInput');
                    const queueSection = document.getElementById('queueSection');
                    const fileList = document.getElementById('fileList');
                    const summaryText = document.getElementById('summaryText');
                    const summaryStats = document.getElementById('summaryStats');

                    const AUDIO_EXTS = ['.mp3', '.flac', '.m4a', '.wav', '.ogg', '.opus', '.aac', '.wma', '.alac'];
                    const MAX_CONCURRENT_UPLOADS = 4;
                    
                    let uploadQueue = [];
                    let activeUploads = 0;
                    let existingFilesSet = new Set();

                    let totalCount = 0;
                    let successCount = 0;
                    let skippedCount = 0;
                    let errorCount = 0;

                    function sanitizeFileName(rawName) {
                        const fileName = rawName.split('/').pop().split('\\').pop();
                        return fileName.replace(/[^a-zA-Z0-9._-]/g, '_');
                    }

                    function isAudioFile(filename) {
                        const lower = filename.toLowerCase();
                        return AUDIO_EXTS.some(ext => lower.endsWith(ext));
                    }

                    function formatBytes(bytes) {
                        if (bytes === 0) return '0 B';
                        const k = 1024;
                        const sizes = ['B', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
                    }

                    async function fetchExistingFiles() {
                        try {
                            const res = await fetch('/existing-files');
                            if (res.ok) {
                                const list = await res.json();
                                existingFilesSet = new Set(list.map(f => f.toLowerCase()));
                            }
                        } catch (e) {
                            console.error("Error fetching existing files:", e);
                        }
                    }

                    // Drag & Drop handlers
                    ['dragenter', 'dragover'].forEach(name => {
                        dropZone.addEventListener(name, (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
                    });
                    ['dragleave', 'drop'].forEach(name => {
                        dropZone.addEventListener(name, (e) => { e.preventDefault(); dropZone.classList.remove('dragover'); });
                    });

                    dropZone.addEventListener('drop', async (e) => {
                        const items = e.dataTransfer.items;
                        const collectedFiles = [];

                        if (items && items.length) {
                            for (let i = 0; i < items.length; i++) {
                                const entry = items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null;
                                if (entry) {
                                    const files = await traverseEntry(entry);
                                    collectedFiles.push(...files);
                                } else {
                                    const file = items[i].getAsFile();
                                    if (file && isAudioFile(file.name)) collectedFiles.push(file);
                                }
                            }
                        }
                        if (collectedFiles.length) addFilesToQueue(collectedFiles);
                    });

                    fileInput.addEventListener('change', () => {
                        const files = Array.from(fileInput.files).filter(f => isAudioFile(f.name));
                        if (files.length) addFilesToQueue(files);
                    });

                    folderInput.addEventListener('change', () => {
                        const files = Array.from(folderInput.files).filter(f => isAudioFile(f.name));
                        if (files.length) addFilesToQueue(files);
                    });

                    async function traverseEntry(entry, path = '') {
                        const files = [];
                        if (entry.isFile) {
                            return new Promise((resolve) => {
                                entry.file(file => {
                                    if (isAudioFile(file.name)) {
                                        file.relativePath = path + file.name;
                                        resolve([file]);
                                    } else resolve([]);
                                });
                            });
                        } else if (entry.isDirectory) {
                            const dirReader = entry.createReader();
                            const entries = await new Promise(resolve => dirReader.readEntries(resolve));
                            for (const child of entries) {
                                const sub = await traverseEntry(child, path + entry.name + '/');
                                files.push(...sub);
                            }
                        }
                        return files;
                    }

                    async function addFilesToQueue(files) {
                        queueSection.style.display = 'block';
                        await fetchExistingFiles();

                        files.forEach(file => {
                            const id = 'file_' + Math.random().toString(36).substr(2, 9);
                            const relativePath = file.relativePath || file.webkitRelativePath || file.name;
                            const safeName = sanitizeFileName(file.name);
                            const isAlreadyExists = existingFilesSet.has(safeName.toLowerCase());

                            const item = {
                                id: id,
                                file: file,
                                path: relativePath,
                                safeName: safeName,
                                status: isAlreadyExists ? 'skipped' : 'pending',
                                progress: isAlreadyExists ? 100 : 0,
                                message: isAlreadyExists ? 'Ya existe una canción con el mismo nombre' : ''
                            };

                            if (isAlreadyExists) skippedCount++;

                            uploadQueue.push(item);
                            createFileCardUI(item);
                        });

                        totalCount = uploadQueue.length;
                        updateSummaryUI();
                        triggerParallelUploads();
                    }

                    function createFileCardUI(item) {
                        const card = document.createElement('div');
                        card.className = 'file-card';
                        card.id = item.id;

                        const badgeClass = item.status === 'skipped' ? 'badge-skipped' : 'badge-pending';
                        const badgeText = item.status === 'skipped' ? '⚠️ Omitido' : '⏳ Pendiente';

                        card.innerHTML = `
                            <div class="file-info">
                                <div>
                                    <div class="file-name" title="${dollar}{item.path}">${dollar}{item.path}</div>
                                    <div class="file-meta">${dollar}{formatBytes(item.file.size)} ${dollar}{item.message ? '• ' + item.message : ''}</div>
                                </div>
                                <span class="badge ${dollar}{badgeClass}" id="badge_${dollar}{item.id}">${dollar}{badgeText}</span>
                            </div>
                            <div class="file-progress-bg">
                                <div class="file-progress-fill" id="fill_${dollar}{item.id}" style="width: ${dollar}{item.progress}%"></div>
                            </div>
                        `;
                        fileList.appendChild(card);
                    }

                    function updateSummaryUI() {
                        const processed = successCount + skippedCount + errorCount;
                        summaryText.innerText = `Archivos: ${dollar}{processed} de ${dollar}{totalCount}`;
                        summaryStats.innerText = `✅ ${dollar}{successCount} exitosos • ⚠️ ${dollar}{skippedCount} omitidos • ❌ ${dollar}{errorCount} errores`;
                    }

                    function triggerParallelUploads() {
                        while (activeUploads < MAX_CONCURRENT_UPLOADS) {
                            const nextItem = uploadQueue.find(i => i.status === 'pending');
                            if (!nextItem) break;
                            uploadSingleFile(nextItem);
                        }
                    }

                    function uploadSingleFile(item) {
                        activeUploads++;
                        item.status = 'uploading';
                        updateItemUI(item, 'uploading', 0, '🔵 Subiendo...');

                        const xhr = new XMLHttpRequest();
                        const encodedName = encodeURIComponent(item.path);
                        xhr.open('POST', `/upload-file?name=${dollar}{encodedName}`, true);
                        xhr.setRequestHeader('Content-Type', 'application/octet-stream');

                        xhr.upload.onprogress = (e) => {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                item.progress = percent;
                                const text = percent === 100 ? '⚡ Guardando...' : `🔵 ${dollar}{percent}%`;
                                updateItemUI(item, 'uploading', percent, text);
                            }
                        };

                        xhr.onload = () => {
                            activeUploads--;
                            if (xhr.status === 200) {
                                try {
                                    const resp = JSON.parse(xhr.responseText);
                                    if (resp.status === 'ok') {
                                        item.status = 'success';
                                        existingFilesSet.add(item.safeName.toLowerCase());
                                        successCount++;
                                        updateItemUI(item, 'success', 100, '✅ Completado');
                                    } else if (resp.status === 'skipped') {
                                        item.status = 'skipped';
                                        item.message = resp.message || 'Ya existe una canción con el mismo nombre';
                                        skippedCount++;
                                        updateItemUI(item, 'skipped', 100, '⚠️ Omitido');
                                    } else {
                                        item.status = 'error';
                                        item.message = resp.message || 'Error del servidor';
                                        errorCount++;
                                        updateItemUI(item, 'error', 100, `❌ ${dollar}{item.message}`);
                                    }
                                } catch (e) {
                                    item.status = 'success';
                                    successCount++;
                                    updateItemUI(item, 'success', 100, '✅ Completado');
                                }
                            } else {
                                item.status = 'error';
                                item.message = 'HTTP ' + xhr.status;
                                errorCount++;
                                updateItemUI(item, 'error', 100, `❌ ${dollar}{item.message}`);
                            }
                            updateSummaryUI();
                            triggerParallelUploads();
                        };

                        xhr.onerror = () => {
                            activeUploads--;
                            item.status = 'error';
                            item.message = 'Error de red / Conexión reiniciada';
                            errorCount++;
                            updateItemUI(item, 'error', 100, '❌ Error de red');
                            updateSummaryUI();
                            triggerParallelUploads();
                        };

                        xhr.send(item.file);
                    }

                    function updateItemUI(item, statusClass, percent, badgeText) {
                        const fill = document.getElementById('fill_' + item.id);
                        const badge = document.getElementById('badge_' + item.id);
                        if (fill) fill.style.width = percent + '%';
                        if (badge) {
                            badge.className = 'badge badge-' + statusClass;
                            badge.innerText = badgeText;
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        serviceScope.cancel()
        _serverState.value = null
        // Keep received items visible after stop; only clear in-progress ones.
        _transfers.update { transfers ->
            transfers.filter {
                it.state == WifiTransferState.DONE || it.state == WifiTransferState.ERROR
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
