package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Lightweight client for dynamic genre translation using Google GTX endpoint.
 * Runs non-blockingly on background IO to populate local taxonomy cache.
 */
object GenreTranslationClient {
    private const val TRANSLATE_URL =
        "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t"

    /**
     * Translates a music genre term to [targetLang] (default "es").
     * Returns null if offline, blocked, or network error.
     */
    suspend fun translateGenre(
        term: String,
        targetLang: String = "es",
        sourceLang: String = "auto",
    ): String? =
        withContext(Dispatchers.IO) {
            val clean = term.trim()
            if (clean.isEmpty()) return@withContext null
            try {
                val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
                val url = "$TRANSLATE_URL&sl=$sourceLang&tl=$targetLang&q=$encoded"
                val req =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)")
                        .build()

                HttpClients.api.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONArray(body)
                    val sentences = json.optJSONArray(0) ?: return@withContext null
                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        val sentence = sentences.optJSONArray(i) ?: continue
                        val translatedChunk = sentence.optString(0, "")
                        sb.append(translatedChunk)
                    }
                    val result = sb.toString().trim()
                    if (result.isNotEmpty() && !result.equals(clean, ignoreCase = true)) {
                        result
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
}
