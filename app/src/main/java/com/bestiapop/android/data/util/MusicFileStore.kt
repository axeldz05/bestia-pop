package com.bestiapop.android.data.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.File

/**
 * Single I/O facade for local audio: persist identity, ExoPlayer Uri, open, write, delete.
 * Callers must not branch on content:// vs file:// vs abs path.
 */
class MusicFileStore(private val context: Context) {

    fun canonicalize(uriString: String, folderPath: String = ""): AudioPersistRef =
        AudioPersistRef.canonicalize(uriString, folderPath)

    fun playableUri(ref: AudioPersistRef): Uri {
        directFilePath(ref)?.let { return Uri.fromFile(File(it)) }
        val raw = ref.uriString.trim()
        if (raw.isEmpty()) return Uri.EMPTY
        return raw.toUri()
    }

    fun playableUri(uriString: String, folderPath: String = ""): Uri =
        playableUri(canonicalize(uriString, folderPath))

    fun openRead(ref: AudioPersistRef): ParcelFileDescriptor? {
        return try {
            val abs = directFilePath(ref)
            if (abs != null) {
                ParcelFileDescriptor.open(File(abs), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(playableUri(ref), "r")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun applyDataSource(retriever: MediaMetadataRetriever, ref: AudioPersistRef) {
        val abs = directFilePath(ref)
        if (abs != null) retriever.setDataSource(abs)
        else retriever.setDataSource(context, playableUri(ref))
    }

    fun applyDataSource(extractor: MediaExtractor, ref: AudioPersistRef) {
        val abs = directFilePath(ref)
        if (abs != null) extractor.setDataSource(abs)
        else extractor.setDataSource(context, playableUri(ref), null)
    }

    fun applyDataSource(player: MediaPlayer, ref: AudioPersistRef) {
        val abs = directFilePath(ref)
        if (abs != null) player.setDataSource(abs)
        else player.setDataSource(context, playableUri(ref))
    }

    fun prepareWrite(
        displayName: String,
        mime: String = StorageUtils.mimeFromFileName(displayName)
    ): StorageUtils.PendingWrite = StorageUtils.prepareWrite(context, displayName, mime)

    fun delete(ref: AudioPersistRef) {
        val uri = ref.uriString
        val abs = SongPathNormalizer.resolveFilePath(uri, ref.folderPath)
        val appManaged = !abs.isNullOrBlank() && SongPathNormalizer.isSafeToDeleteAppManagedFile(abs)
        // The MediaStore branch used to run before (and without) this guard, so callers that only
        // meant to drop an app row — saveUploadedSong, the "Sobrescribir" download policy — could
        // delete a foreign file the user never asked to remove.
        if (appManaged && uri.startsWith("content://media/", ignoreCase = true)) {
            runCatching { context.contentResolver.delete(uri.toUri(), null, null) }
        }
        if (appManaged) {
            StorageUtils.deleteManagedAudio(context, abs!!)
        }
        val fileName = SongPathNormalizer.fileName(uri, ref.folderPath)
        if (fileName.isNotEmpty()) {
            val oldUpload = File(context.getExternalFilesDir(null), "UploadedMusic")
            val oldDownload = File(context.getExternalFilesDir(null), "DownloadedMusic")
            if (oldUpload.exists()) File(oldUpload, fileName).takeIf { it.exists() }?.delete()
            if (oldDownload.exists()) File(oldDownload, fileName).takeIf { it.exists() }?.delete()
        }
    }

    fun listManaged(): List<File> = StorageUtils.listManagedAudioFiles(context)

    fun listManagedNames(): Set<String> = StorageUtils.listAudioFileNames(context)

    /**
     * Filesystem [File] safe for direct I/O (tag write). Null for MediaStore content://
     * or missing/non-writable paths.
     */
    fun writableFile(ref: AudioPersistRef): File? {
        val abs = directFilePath(ref) ?: return null
        val file = File(abs)
        return file.takeIf { it.isFile && it.canWrite() }
    }

    fun writableFile(uriString: String, folderPath: String = ""): File? =
        writableFile(canonicalize(uriString, folderPath))

    /**
     * Filesystem path safe for direct File I/O. Never returns MediaStore DATA for
     * content://media (Android 15 scoped storage).
     */
    private fun directFilePath(ref: AudioPersistRef): String? {
        if (ref.uriString.startsWith("content://media/", ignoreCase = true)) return null
        SongPathNormalizer.toAbsolutePath(ref.uriString)?.let { return it }
        val resolved = SongPathNormalizer.resolveFilePath(ref.uriString, ref.folderPath)
        return resolved?.takeIf { it.startsWith("/") }
    }
}
