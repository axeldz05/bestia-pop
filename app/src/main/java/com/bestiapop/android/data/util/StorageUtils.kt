package com.bestiapop.android.data.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Single app-managed music folder: public [RELATIVE_MUSIC_DIR].
 * Debug and release share [applicationId], so this path is the same.
 * Writes use direct File I/O when the dir is writable; otherwise MediaStore
 * (survives FUSE owner/UID changes after debug↔release reinstall).
 */
object StorageUtils {

    const val RELATIVE_MUSIC_DIR = "Music/BestiaPop"
    private const val FOLDER_NAME = "BestiaPop"

    /** User-facing label for the app-managed save folder (Spanish Music dir name). */
    fun userVisibleMusicDirLabel(): String = "Música/BestiaPop"

    /** Human-readable byte count for download totals (B / KB / MB / GB). */
    fun formatByteCount(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun getPublicMusicDirectory(@Suppress("UNUSED_PARAMETER") context: Context): File {
        val dir = publicBestiaPopDir()
        dir.mkdirs()
        return dir
    }

    fun publicBestiaPopDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER_NAME)

    fun mimeFromFileName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".webm") -> "audio/webm"
            lower.endsWith(".wma") -> "audio/x-ms-wma"
            else -> "audio/*"
        }
    }

    fun isBestiaPopLocation(relativePath: String?, dataPath: String?): Boolean {
        val rel = relativePath.orEmpty().replace('\\', '/').trim().trimStart('/')
        if (rel.contains(RELATIVE_MUSIC_DIR, ignoreCase = true)) return true
        val data = dataPath.orEmpty().replace('\\', '/')
        return data.contains("/$RELATIVE_MUSIC_DIR", ignoreCase = true) ||
            data.endsWith(RELATIVE_MUSIC_DIR, ignoreCase = true)
    }

    /** Lowercase basenames in the single BestiaPop folder (filesystem and/or MediaStore). */
    fun listAudioFileNames(context: Context): Set<String> {
        val names = HashSet<String>()
        publicBestiaPopDir().listFiles()?.forEach { file ->
            if (file.isFile && file.name.isNotBlank()) names.add(file.name.lowercase())
        }
        runCatching {
            context.contentResolver.query(
                audioCollection(),
                audioProjection(),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val relIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val name = nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    if (name.isBlank()) continue
                    val rel = relIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    val data = dataIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    if (isBestiaPopLocation(rel, data)) names.add(name.lowercase())
                }
            }
        }
        return names
    }

    fun listManagedAudioFiles(context: Context): List<File> {
        val dir = publicBestiaPopDir()
        val fromFs = ArrayList<File>()
        fun walk(folder: File) {
            val children = folder.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) walk(child)
                else if (child.isFile) fromFs.add(child)
            }
        }
        walk(dir)
        if (fromFs.isNotEmpty()) return fromFs
        return mediaStoreBestiaPopFiles(context)
    }

    class PendingWrite internal constructor(
        val stagingFile: File,
        private val publisher: () -> String
    ) {
        fun publish(): String = publisher()
    }

    /**
     * Stage bytes locally, then land them in Music/BestiaPop.
     * [stagingFile] is the File to write/resume; [PendingWrite.publish] returns the final absolute path.
     */
    fun prepareWrite(
        context: Context,
        displayName: String,
        mime: String = mimeFromFileName(displayName)
    ): PendingWrite {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
        val dir = publicBestiaPopDir()
        if (ensureWritableDir(dir)) {
            val dest = File(dir, safeName)
            return PendingWrite(dest) { dest.absolutePath }
        }
        val staging = File(context.cacheDir, "bp_${System.currentTimeMillis()}_$safeName")
        return PendingWrite(staging) {
            try {
                publishViaMediaStore(context, staging, safeName, mime)
            } finally {
                staging.delete()
            }
        }
    }

    fun deleteManagedAudio(context: Context, path: String): Boolean {
        var deleted = false
        val file = File(path)
        if (file.exists()) deleted = file.delete() || deleted
        deleted = deleteMediaStoreEntry(context, path) || deleted
        return deleted
    }

    private fun publishViaMediaStore(
        context: Context,
        staging: File,
        displayName: String,
        mime: String
    ): String {
        if (!staging.exists() || staging.length() == 0L) {
            throw IOException("No hay audio para publicar en Music/BestiaPop")
        }
        deleteMediaStoreEntry(context, displayName)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_MUSIC_DIR)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(audioCollection(), values)
            ?: throw IOException("MediaStore no pudo crear $displayName en $RELATIVE_MUSIC_DIR")
        resolver.openOutputStream(uri)?.use { out ->
            staging.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("No se pudo escribir $displayName vía MediaStore")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return queryAbsolutePath(context, uri)
            ?: File(publicBestiaPopDir(), displayName).absolutePath
    }

    private fun deleteMediaStoreEntry(context: Context, pathOrName: String): Boolean {
        val name = pathOrName.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank()) return false
        val resolver = context.contentResolver
        var deleted = false
        runCatching {
            resolver.query(
                audioCollection(),
                audioProjection(includeId = true),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val toDelete = ArrayList<Long>()
                while (cursor.moveToNext()) {
                    val display = nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    val data = dataIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    val rel = relIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    if (!isBestiaPopLocation(rel, data) && !data.equals(pathOrName, ignoreCase = true)) continue
                    if (display.equals(name, ignoreCase = true) || data.equals(pathOrName, ignoreCase = true)) {
                        toDelete.add(cursor.getLong(idIdx))
                    }
                }
                for (id in toDelete) {
                    val uri = ContentUris.withAppendedId(audioCollection(), id)
                    if (resolver.delete(uri, null, null) > 0) deleted = true
                }
            }
        }
        return deleted
    }

    private fun mediaStoreBestiaPopFiles(context: Context): List<File> {
        val files = ArrayList<File>()
        runCatching {
            context.contentResolver.query(
                audioCollection(),
                audioProjection(),
                null,
                null,
                null
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val data = dataIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    val rel = relIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    val name = nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    if (!isBestiaPopLocation(rel, data)) continue
                    val path = data.ifBlank {
                        if (name.isNotBlank()) File(publicBestiaPopDir(), name).absolutePath else ""
                    }
                    if (path.isNotBlank()) files.add(File(path))
                }
            }
        }
        return files
    }

    private fun queryAbsolutePath(context: Context, uri: android.net.Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun audioProjection(includeId: Boolean = false): Array<String> {
        val cols = ArrayList<String>(4)
        if (includeId) cols.add(MediaStore.Audio.Media._ID)
        cols.add(MediaStore.Audio.Media.DISPLAY_NAME)
        cols.add(MediaStore.Audio.Media.DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }
        return cols.toTypedArray()
    }

    private fun audioCollection(): android.net.Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private fun ensureWritableDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val probe = File(dir, ".bp_write_ok")
            probe.outputStream().use { it.write(1) }
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
