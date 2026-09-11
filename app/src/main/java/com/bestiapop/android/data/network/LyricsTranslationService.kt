package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class LyricsTranslationResult(
    val lines: List<String>,
    val sourceName: String,
    val sourceUrl: String?
)

data class LyricsTranslationSource(
    val name: String,
    val url: String?
)

data class LyricsRomanizationResult(
    val lines: List<String>
)

object LyricsTranslationService {

    private val client = HttpClients.api.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val DELIMITER = " \n~~~ \n"
    private val DELIMITER_REGEX = Regex("""\s*~~~\s*""")

    /**
     * Intenta buscar una traducción comunitaria existente en sitios como Musixmatch.
     * Retorna null si no se encuentra para que la UI pueda consultar al usuario.
     */
    suspend fun fetchCommunityTranslation(
        artist: String,
        title: String
    ): LyricsTranslationResult? = withContext(Dispatchers.IO) {
        try {
            val cleanArtist = artist.trim().replace(" ", "-").replace("/", "-")
            val cleanTitle = title.trim().replace(" ", "-").replace("/", "-")
            val candidateUrl = "https://www.musixmatch.com/lyrics/$cleanArtist/$cleanTitle/translation/spanish"

            val req = Request.Builder()
                .url(candidateUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val lines = extractLinesFromMusixmatchHtml(html)
                    if (lines.isNotEmpty()) {
                        return@withContext LyricsTranslationResult(
                            lines = lines,
                            sourceName = "Musixmatch",
                            sourceUrl = candidateUrl
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignorar fallos para permitir fallback al prompt del usuario
        }
        null
    }

    /**
     * Traduce las líneas proporcionadas a través de Google Traductor en lotes para conservar la sincronización.
     */
    suspend fun translateWithGoogle(
        lines: List<String>,
        targetLanguage: String = "es"
    ): LyricsTranslationResult? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext null
        try {
            val translatedLines = mutableListOf<String>()
            val chunkSize = 15
            val chunks = lines.chunked(chunkSize)

            for (chunk in chunks) {
                val joined = chunk.joinToString(DELIMITER)
                val encoded = URLEncoder.encode(joined, StandardCharsets.UTF_8.name())
                val url =
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLanguage&dt=t&q=$encoded"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AndroidTranslate/6.20.0 (Linux; U; Android 10; Pixel 4)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONArray(body)
                    val sentences = json.optJSONArray(0) ?: return@withContext null

                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        val entry = sentences.optJSONArray(i) ?: continue
                        val part = entry.optString(0, "")
                        sb.append(part)
                    }

                    val split = sb.toString().split(DELIMITER_REGEX)
                    for (idx in chunk.indices) {
                        val text = split.getOrNull(idx)?.trim().orEmpty()
                        translatedLines.add(text)
                    }
                }
            }

            val sourceQuery = URLEncoder.encode(lines.take(3).joinToString(" "), StandardCharsets.UTF_8.name())
            val sourceUrl = "https://translate.google.com/?sl=auto&tl=$targetLanguage&text=$sourceQuery"

            LyricsTranslationResult(
                lines = translatedLines,
                sourceName = "Google Traductor",
                sourceUrl = sourceUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Obtiene la romanización (fonética) línea a línea para alfabetos no latinos.
     */
    suspend fun fetchRomanization(
        lines: List<String>
    ): LyricsRomanizationResult? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext null
        try {
            val romanizedLines = mutableListOf<String>()
            val chunkSize = 15
            val chunks = lines.chunked(chunkSize)

            for (chunk in chunks) {
                val joined = chunk.joinToString(" ~~~ ")
                val encoded = URLEncoder.encode(joined, StandardCharsets.UTF_8.name())
                val url =
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=rm&q=$encoded"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AndroidTranslate/6.20.0 (Linux; U; Android 10; Pixel 4)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONArray(body)
                    val sentences = json.optJSONArray(0) ?: return@withContext null

                    var fullRom: String? = null
                    for (i in 0 until sentences.length()) {
                        val entry = sentences.optJSONArray(i) ?: continue
                        if (entry.length() > 3 && !entry.isNull(3)) {
                            fullRom = entry.optString(3, null)
                            break
                        }
                    }

                    if (fullRom != null) {
                        val split = fullRom.split("~~~")
                        for (idx in chunk.indices) {
                            val text = split.getOrNull(idx)?.trim().orEmpty()
                            romanizedLines.add(text)
                        }
                    } else {
                        for (idx in chunk.indices) {
                            romanizedLines.add("")
                        }
                    }
                }
            }

            LyricsRomanizationResult(lines = romanizedLines)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLinesFromMusixmatchHtml(html: String): List<String> {
        val matches =
            Regex("""<p class="mxm-lyrics__content[^"]*">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        val list = mutableListOf<String>()
        for (m in matches) {
            val block = m.groupValues[1]
                .replace("<br>", "\n")
                .replace("<br/>", "\n")
                .replace("<br />", "\n")
                .replace(Regex("<[^>]*>"), "")
            block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { list.add(it) }
        }
        return list
    }
}
