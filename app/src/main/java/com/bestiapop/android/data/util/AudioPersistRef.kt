package com.bestiapop.android.data.util

import java.io.File

/**
 * Canonical Room identity for a local audio file.
 * BestiaPop-managed files use an absolute path; foreign MediaStore keeps content://media.
 */
data class AudioPersistRef(
    val uriString: String,
    val folderPath: String
) {
    companion object {
        fun canonicalize(uriString: String, folderPath: String = ""): AudioPersistRef {
            val uri = uriString.trim()
            val folder = folderPath.trim()
            val resolved = SongPathNormalizer.resolveFilePath(uri, folder)

            if (resolved != null && SongPathNormalizer.isUnderBestiaPop(resolved)) {
                return AudioPersistRef(
                    uriString = resolved,
                    folderPath = File(resolved).parent.orEmpty()
                )
            }

            if (uri.startsWith("content://media/", ignoreCase = true)) {
                val data = folder.takeIf { it.startsWith("/") && !it.contains("://") } ?: folder
                return AudioPersistRef(uriString = uri, folderPath = data)
            }

            if (uri.startsWith("content://", ignoreCase = true)) {
                return AudioPersistRef(uriString = uri, folderPath = folder)
            }

            if (resolved != null && resolved.startsWith("/")) {
                val parent = File(resolved).parent.orEmpty()
                val folderOk = folder.startsWith("/") &&
                    !folder.contains("://") &&
                    !looksLikeCacheDir(folder)
                return AudioPersistRef(
                    uriString = resolved,
                    folderPath = if (folderOk) folder else parent
                )
            }

            return AudioPersistRef(uriString = uri, folderPath = folder)
        }
    }
}

internal fun looksLikeCacheDir(path: String): Boolean {
    val lower = path.lowercase().replace('\\', '/')
    return lower.contains("/cache") || lower.endsWith("/cache")
}
