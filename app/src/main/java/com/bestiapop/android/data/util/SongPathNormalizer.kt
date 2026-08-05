package com.bestiapop.android.data.util

import java.io.File

/**
 * Normalizes song URI / path strings so downloads, WiFi uploads, and MediaStore DATA
 * can be compared and stored consistently (absolute filesystem paths when possible).
 */
object SongPathNormalizer {

    fun toAbsolutePath(uriOrPath: String): String? {
        val raw = uriOrPath.trim()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("content://", ignoreCase = true) -> null
            raw.startsWith("file://", ignoreCase = true) -> {
                // file:///storage/... or file://localhost/storage/...
                var path = raw.removePrefix("file://")
                if (path.startsWith("localhost", ignoreCase = true)) {
                    path = path.removePrefix("localhost").removePrefix("LOCALHOST")
                }
                path.trimStart('/').let { "/$it" }.takeIf { it.length > 1 }
            }
            raw.startsWith("file:", ignoreCase = true) -> {
                // file:/storage/... (Java URI single-slash form)
                val path = raw.removePrefix("file:")
                if (path.startsWith("/")) path else "/$path"
            }
            raw.startsWith("/") -> raw
            else -> null
        }
    }

    /** Resolves a playable path: URI absolute path, else MediaStore DATA in [folderPath]. */
    fun resolveFilePath(uriString: String, folderPath: String = ""): String? {
        toAbsolutePath(uriString)?.let { return it }
        val data = folderPath.trim()
        if (data.startsWith("/") && !data.contains("://")) return data
        return toAbsolutePath(data)
    }

    fun isUnderBestiaPop(pathOrUri: String): Boolean {
        val lower = pathOrUri.lowercase().replace('\\', '/')
        return lower.contains("/music/bestiapop") ||
            lower.endsWith("music/bestiapop") ||
            lower == "music/bestiapop"
    }

    fun isSafeToDeleteAppManagedFile(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        return isUnderBestiaPop(lower) ||
            lower.contains("/download/") ||
            lower.contains("/downloadedmusic") ||
            lower.contains("/uploadedmusic")
    }

    fun pathsReferToSameFile(a: String, b: String): Boolean {
        val pa = toAbsolutePath(a) ?: a.takeIf { it.startsWith("/") }
        val pb = toAbsolutePath(b) ?: b.takeIf { it.startsWith("/") }
        if (pa.isNullOrBlank() || pb.isNullOrBlank()) return false
        return try {
            File(pa).canonicalPath == File(pb).canonicalPath
        } catch (_: Exception) {
            pa == pb
        }
    }

    fun isAppOwnedUri(uriString: String, folderPath: String = ""): Boolean {
        if (uriString.startsWith("content://", ignoreCase = true)) return false
        return isUnderBestiaPop(uriString) || isUnderBestiaPop(folderPath)
    }

    /** File/http artwork is usable; null/empty and MediaStore content:// albumart stubs are not. */
    fun hasUsableArtwork(artworkUri: String?): Boolean =
        !artworkUri.isNullOrEmpty() && !artworkUri.startsWith("content://")
}
