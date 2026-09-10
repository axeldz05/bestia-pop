package com.bestiapop.android.data.util

/**
 * Normalizes upload / managed filenames so WiFi `/existing-files`, dashboard compare,
 * and on-disk writes share one basename form. Letters and spaces stay intact; only
 * path-illegal characters and control codes become `_`. [asciiLegacy] and
 * [underscoreSpacesLegacy] retain compatibility with previous versions.
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

    /** Legacy sanitizer that converted spaces to underscores. */
    fun underscoreSpacesLegacy(rawName: String): String {
        val base = fileNameOnly(rawName)
        val sb = StringBuilder(base.length)
        for (ch in base) {
            if (isPathUnsafe(ch) || ch.isWhitespace()) sb.append('_') else sb.append(ch)
        }
        val result = sb.toString().trim()
        return if (result.isEmpty() || result.all { it == '.' }) "audio_${System.currentTimeMillis()}.mp3" else result
    }

    fun matchingBasenames(rawName: String): List<String> {
        val current = sanitize(rawName)
        val legacyAscii = asciiLegacy(rawName)
        val legacyUnderscores = underscoreSpacesLegacy(rawName)
        return listOf(current, legacyAscii, legacyUnderscores).distinct()
    }

    private fun isPathUnsafe(ch: Char): Boolean = when (ch) {
        '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000' -> true
        else -> ch < ' ' && ch != '\t'
    }

    private fun fileNameOnly(rawName: String): String =
        rawName.substringAfterLast("/").substringAfterLast("\\")
}
