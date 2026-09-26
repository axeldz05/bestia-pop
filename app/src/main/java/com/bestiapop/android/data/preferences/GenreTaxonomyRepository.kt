package com.bestiapop.android.data.preferences

import android.content.Context
import com.bestiapop.android.data.network.GenreTranslationClient
import com.bestiapop.android.domain.util.GenreTaxonomy
import com.bestiapop.android.domain.util.TrackMatchKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Repository responsible for loading the bundled genre taxonomy asset,
 * reading/writing persisted dynamic translations from filesDir,
 * and auto-resolving unknown music genre tags in background.
 */
class GenreTaxonomyRepository(
    private val context: Context,
) {
    private val cacheFile = File(context.filesDir, "genre_taxonomy_cache.json")
    private val writeLock = Any()

    /**
     * Initializes GenreTaxonomy by loading the bundled asset and any persisted dynamic translations.
     */
    suspend fun initialize() =
        withContext(Dispatchers.IO) {
            loadBundledAsset()
            loadPersistedCache()
        }

    fun loadBundledAsset() {
        try {
            val jsonString =
                context.assets
                    .open("genre_taxonomy.json")
                    .bufferedReader()
                    .use { it.readText() }
            val root = JSONObject(jsonString)
            val enToEs = root.optJSONObject("en_to_es")
            if (enToEs != null) {
                for (key in enToEs.keys()) {
                    val arr = enToEs.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        GenreTaxonomy.registerDynamicTranslation(key, arr.getString(i))
                    }
                }
            }
            val esToEn = root.optJSONObject("es_to_en")
            if (esToEn != null) {
                for (key in esToEn.keys()) {
                    val arr = esToEn.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        GenreTaxonomy.registerDynamicTranslation(key, arr.getString(i))
                    }
                }
            }
        } catch (_: Exception) {
            // Asset load fallback to hardcoded baseline
        }
    }

    fun loadPersistedCache() {
        if (!cacheFile.exists()) return
        try {
            val jsonString = cacheFile.readText()
            val root = JSONObject(jsonString)
            for (key in root.keys()) {
                val arr = root.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    GenreTaxonomy.registerDynamicTranslation(key, arr.getString(i))
                }
            }
        } catch (_: Exception) {
            // Corrupt file or read failure: ignore
        }
    }

    /**
     * Asynchronously discovers and translates any unknown genres from a list of genre names.
     */
    suspend fun resolveUnknownGenres(genres: Collection<String>) =
        withContext(Dispatchers.IO) {
            val unmapped =
                genres
                    .mapNotNull { raw ->
                        val clean = raw.trim()
                        if (clean.isEmpty() || GenreTaxonomy.hasMapping(clean)) null else clean
                    }.distinct()

            if (unmapped.isEmpty()) return@withContext

            val newTranslations = mutableMapOf<String, String>()
            for (genre in unmapped) {
                // Try translate English -> Spanish first, then Spanish -> English
                val esTranslation = GenreTranslationClient.translateGenre(genre, targetLang = "es", sourceLang = "en")
                if (esTranslation != null) {
                    newTranslations[genre] = esTranslation
                    GenreTaxonomy.registerDynamicTranslation(genre, esTranslation)
                } else {
                    val enTranslation = GenreTranslationClient.translateGenre(genre, targetLang = "en", sourceLang = "es")
                    if (enTranslation != null) {
                        newTranslations[genre] = enTranslation
                        GenreTaxonomy.registerDynamicTranslation(genre, enTranslation)
                    }
                }
            }

            if (newTranslations.isNotEmpty()) {
                persistTranslations(newTranslations)
            }
        }

    private fun persistTranslations(newEntries: Map<String, String>) {
        synchronized(writeLock) {
            try {
                val current =
                    if (cacheFile.exists()) {
                        try {
                            JSONObject(cacheFile.readText())
                        } catch (_: Exception) {
                            JSONObject()
                        }
                    } else {
                        JSONObject()
                    }

                for ((src, dest) in newEntries) {
                    val normSrc = TrackMatchKeys.normalize(src)
                    val normDest = TrackMatchKeys.normalize(dest)
                    val existing = current.optJSONArray(normSrc) ?: JSONArray()
                    var alreadyPresent = false
                    for (i in 0 until existing.length()) {
                        if (existing.optString(i) == normDest) {
                            alreadyPresent = true
                            break
                        }
                    }
                    if (!alreadyPresent) {
                        existing.put(normDest)
                        current.put(normSrc, existing)
                    }

                    // Bidirectional
                    val reverseExisting = current.optJSONArray(normDest) ?: JSONArray()
                    var reverseAlreadyPresent = false
                    for (i in 0 until reverseExisting.length()) {
                        if (reverseExisting.optString(i) == normSrc) {
                            reverseAlreadyPresent = true
                            break
                        }
                    }
                    if (!reverseAlreadyPresent) {
                        reverseExisting.put(normSrc)
                        current.put(normDest, reverseExisting)
                    }
                }

                val tempFile = File(context.filesDir, "genre_taxonomy_cache.json.tmp")
                tempFile.writeText(current.toString())
                tempFile.renameTo(cacheFile)
            } catch (_: Exception) {
                // Disk write failure
            }
        }
    }
}
