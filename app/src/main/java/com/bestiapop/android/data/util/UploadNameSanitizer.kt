package com.bestiapop.android.data.util

/**
 * Normalizes upload / managed filenames so WiFi `/existing-files`, dashboard compare,
 * and on-disk writes share one basename form (accents/spaces → `_`).
 */
object UploadNameSanitizer {
    fun sanitize(rawName: String): String =
        rawName.substringAfterLast("/")
            .substringAfterLast("\\")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
