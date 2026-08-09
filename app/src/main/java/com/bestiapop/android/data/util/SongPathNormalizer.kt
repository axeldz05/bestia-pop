package com.bestiapop.android.data.util

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Normalizes song URI / path strings so downloads, WiFi uploads, and MediaStore DATA
 * can be compared and stored consistently (absolute filesystem paths when possible).
 */
object SongPathNormalizer {

    fun toAbsolutePath(uriOrPath: String): String? {
        val raw = uriOrPath.trim()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("content://com.android.externalstorage.documents", ignoreCase = true) ->
                safTreeDocumentToAbsolutePath(raw)
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

    /**
     * SAF tree/document URIs lose persistable grants after reinstall; the document id
     * still encodes a filesystem path (primary:Music/BestiaPop/track.mp3).
     */
    internal fun safTreeDocumentToAbsolutePath(uri: String): String? {
        val marker = "/document/"
        val idx = uri.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val encoded = uri.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
        if (encoded.isBlank()) return null
        val docId = percentDecode(encoded)
        val colon = docId.indexOf(':')
        if (colon <= 0 || colon >= docId.length - 1) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).trimStart('/')
        if (rel.isEmpty()) return null
        val root = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        return "$root/$rel"
    }

    private fun percentDecode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    /** Resolves a playable path: URI absolute path, else MediaStore DATA in [folderPath]. */
    fun resolveFilePath(uriString: String, folderPath: String = ""): String? {
        toAbsolutePath(uriString)?.let { return it }
        val data = folderPath.trim()
        if (data.startsWith("/") && !data.contains("://")) return data
        return toAbsolutePath(data)
    }

    fun fileName(uriString: String, folderPath: String = ""): String {
        val path = resolveFilePath(uriString, folderPath) ?: uriString
        return path.substringAfterLast('/').substringAfterLast('\\')
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
        if (uriString.startsWith("content://", ignoreCase = true)) {
            val resolved = toAbsolutePath(uriString)
            return resolved != null && isUnderBestiaPop(resolved)
        }
        return isUnderBestiaPop(uriString) || isUnderBestiaPop(folderPath)
    }

    /** File/http artwork is usable; null/empty and MediaStore content:// albumart stubs are not. */
    fun hasUsableArtwork(artworkUri: String?): Boolean =
        !artworkUri.isNullOrEmpty() && !artworkUri.startsWith("content://")
}
