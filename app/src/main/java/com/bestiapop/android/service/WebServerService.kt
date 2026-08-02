package com.bestiapop.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bestiapop.android.data.db.SongEntity
import com.bestiapop.android.data.repository.MusicRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

class WebServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var server: EmbeddedServer<*, *>? = null

    companion object {
        const val PORT = 8080
        private val _serverState = MutableStateFlow<String?>(null)
        val serverState: StateFlow<String?> = _serverState

        fun getLocalIpAddress(context: Context): String? {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WiFi Web Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Music Web Server Running")
            .setContentText("Uploading music via local WiFi enabled")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()

        startForeground(2001, notification)
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                val repository = MusicRepository(applicationContext)
                val uploadDir = File(getExternalFilesDir(null), "UploadedMusic")
                if (!uploadDir.exists()) uploadDir.mkdirs()

                server = embeddedServer(CIO, port = PORT) {
                    routing {
                        get("/") {
                            call.respondText(getWebDashboardHtml(), ContentType.Text.Html)
                        }

                        post("/upload") {
                            val multipart = call.receiveMultipart()
                            var uploadedCount = 0

                            multipart.forEachPart { part ->
                                if (part is PartData.FileItem) {
                                    val fileName = part.originalFileName ?: "audio_${System.currentTimeMillis()}.mp3"
                                    val destinationFile = File(uploadDir, fileName)

                                    part.streamProvider().use { input ->
                                        destinationFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }

                                    // Extract metadata and save to DB
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(destinationFile.absolutePath)
                                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                            ?: fileName.substringBeforeLast(".")
                                        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                            ?: "Unknown Artist"
                                        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                            ?: "Unknown Album"
                                        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                                            ?: "Music"
                                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            ?.toLongOrNull() ?: 0L

                                        val songEntity = SongEntity(
                                            uriString = destinationFile.toURI().toString(),
                                            title = title,
                                            artist = artist,
                                            album = album,
                                            genre = genre,
                                            durationMs = durationMs,
                                            year = 0,
                                            trackNumber = 0,
                                            artworkUri = null,
                                            lyrics = null,
                                            folderPath = destinationFile.parent ?: "",
                                            dateAdded = System.currentTimeMillis()
                                        )

                                        repository.saveUploadedSong(songEntity)
                                        uploadedCount++
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        retriever.release()
                                    }
                                }
                                part.dispose()
                            }

                            call.respondText("SUCCESS:$uploadedCount", ContentType.Text.Plain)
                        }
                    }
                }.start(wait = false)

                val ip = getLocalIpAddress(applicationContext) ?: "localhost"
                _serverState.value = "$ip:$PORT"
            } catch (e: Exception) {
                e.printStackTrace()
                _serverState.value = null
            }
        }
    }

    private fun getWebDashboardHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Bestia Pop - Transferencia WiFi</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Segoe UI', -apple-system, Roboto, sans-serif;
                        background: linear-gradient(135deg, #0f0c1b, #1a162b, #2d1b4e);
                        color: #ffffff;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        padding: 20px;
                    }
                    .card {
                        background: rgba(255, 255, 255, 0.07);
                        backdrop-filter: blur(16px);
                        border: 1px solid rgba(255, 255, 255, 0.12);
                        border-radius: 24px;
                        padding: 40px;
                        max-width: 520px;
                        width: 100%;
                        box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
                        text-align: center;
                    }
                    h1 { font-size: 26px; font-weight: 700; margin-bottom: 8px; color: #e2d9f3; }
                    p { color: #a59bb5; font-size: 14px; margin-bottom: 28px; line-height: 1.5; }
                    .drop-zone {
                        border: 2px dashed #9d4edd;
                        border-radius: 16px;
                        padding: 40px 20px;
                        background: rgba(157, 78, 221, 0.05);
                        cursor: pointer;
                        transition: all 0.3s ease;
                    }
                    .drop-zone:hover, .drop-zone.dragover {
                        background: rgba(157, 78, 221, 0.15);
                        border-color: #c77dff;
                    }
                    .drop-icon { font-size: 48px; margin-bottom: 12px; display: block; }
                    input[type="file"] { display: none; }
                    .btn {
                        margin-top: 20px;
                        background: linear-gradient(90deg, #7b2cbf, #9d4edd);
                        color: white;
                        border: none;
                        padding: 12px 28px;
                        font-size: 15px;
                        font-weight: 600;
                        border-radius: 12px;
                        cursor: pointer;
                        transition: transform 0.2s, box-shadow 0.2s;
                    }
                    .btn:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(157, 78, 221, 0.4); }
                    .progress-container { margin-top: 20px; display: none; }
                    .progress-bar { height: 8px; background: rgba(255,255,255,0.1); border-radius: 4px; overflow: hidden; }
                    .progress-fill { height: 100%; width: 0%; background: #c77dff; transition: width 0.3s; }
                    .status { margin-top: 16px; font-weight: 600; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>🎵 Bestia Pop</h1>
                    <p>Subí tus canciones favoritas desde tu computadora o teléfono directamente a tu dispositivo móvil.</p>
                    
                    <div class="drop-zone" id="dropZone">
                        <span class="drop-icon">📁</span>
                        <div style="font-weight: 600; font-size: 16px; margin-bottom: 4px;">Arrastrá tus archivos de audio aquí</div>
                        <div style="font-size: 13px; color: #a59bb5;">o hace clic para examinar (MP3, FLAC, M4A, WAV)</div>
                        <input type="file" id="fileInput" multiple accept="audio/*">
                    </div>

                    <div class="progress-container" id="progressContainer">
                        <div class="progress-bar"><div class="progress-fill" id="progressFill"></div></div>
                        <div class="status" id="statusText">Subiendo canciones...</div>
                    </div>
                </div>

                <script>
                    const dropZone = document.getElementById('dropZone');
                    const fileInput = document.getElementById('fileInput');
                    const progressContainer = document.getElementById('progressContainer');
                    const progressFill = document.getElementById('progressFill');
                    const statusText = document.getElementById('statusText');

                    dropZone.addEventListener('click', () => fileInput.click());

                    ['dragenter', 'dragover'].forEach(eventName => {
                        dropZone.addEventListener(eventName, (e) => {
                            e.preventDefault();
                            dropZone.classList.add('dragover');
                        }, false);
                    });

                    ['dragleave', 'drop'].forEach(eventName => {
                        dropZone.addEventListener(eventName, (e) => {
                            e.preventDefault();
                            dropZone.classList.remove('dragover');
                        }, false);
                    });

                    dropZone.addEventListener('drop', (e) => {
                        const files = e.dataTransfer.files;
                        if (files.length) uploadFiles(files);
                    });

                    fileInput.addEventListener('change', () => {
                        if (fileInput.files.length) uploadFiles(fileInput.files);
                    });

                    function uploadFiles(files) {
                        const formData = new FormData();
                        for (let i = 0; i < files.length; i++) {
                            formData.append('files', files[i]);
                        }

                        progressContainer.style.display = 'block';
                        progressFill.style.width = '20%';
                        statusText.innerText = 'Enviando archivos...';

                        const xhr = new XMLHttpRequest();
                        xhr.open('POST', '/upload', true);

                        xhr.upload.onprogress = function(e) {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                progressFill.style.width = percent + '%';
                                statusText.innerText = 'Subiendo: ' + percent + '%';
                            }
                        };

                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                progressFill.style.width = '100%';
                                statusText.style.color = '#70e000';
                                statusText.innerText = '✅ ¡Canciones transferidas exitosamente!';
                            } else {
                                statusText.style.color = '#ff0054';
                                statusText.innerText = '❌ Error al subir archivos.';
                            }
                        };

                        xhr.send(formData);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        _serverState.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
