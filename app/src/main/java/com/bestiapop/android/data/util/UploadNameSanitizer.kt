package com.bestiapop.android.data.util

/**
 * Normalizes upload / managed filenames so WiFi `/existing-files`, dashboard compare,
 * and on-disk writes share one basename form. Letters of any script stay; only
 * path-illegal characters and whitespace become `_`. [asciiLegacy] is the old
 * ASCII-only form so existing files still match.
 */
object UploadNameSanitizer {
    private val ASCII_ONLY = Regex("[^a-zA-Z0-9._-]")

    fun sanitize(rawName: String): String {
        val base = fileNameOnly(rawName)
        val sb = StringBuilder(base.length)
        for (ch in base) {
            if (isPathUnsafe(ch)) sb.append('_') else sb.append(ch)
        }
        val result = sb.toString().trim()
        return if (result.isEmpty() || result.all { it == '.' }) "audio_${System.currentTimeMillis()}.mp3" else result
    }

    /** Pre-unicode sanitizer: non-ASCII letters became `_`. */
    fun asciiLegacy(rawName: String): String {
        val legacy = fileNameOnly(rawName).replace(ASCII_ONLY, "_").trim()
        return if (legacy.isEmpty() || legacy.all { it == '.' }) "audio_${System.currentTimeMillis()}.mp3" else legacy
    }

    fun matchingBasenames(rawName: String): List<String> {
        val current = sanitize(rawName)
        val legacy = asciiLegacy(rawName)
        return if (current.equals(legacy, ignoreCase = true)) {
            listOf(current)
        } else {
            listOf(current, legacy)
        }
    }

    private fun isPathUnsafe(ch: Char): Boolean = when (ch) {
        '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> true
        else -> ch.isWhitespace()
    }

    private fun fileNameOnly(rawName: String): String =
        rawName.substringAfterLast("/").substringAfterLast("\\")
}
