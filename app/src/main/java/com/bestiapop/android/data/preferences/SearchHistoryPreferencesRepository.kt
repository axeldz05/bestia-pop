package com.bestiapop.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "search_history"
)

private const val MAX_SEARCH_HISTORY_ITEMS = 100

class SearchHistoryPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.searchHistoryDataStore)

    private object Keys {
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches_json")
    }

    val recentSearchesFlow: Flow<List<String>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_SEARCHES] ?: return@map emptyList()
        parseJsonList(raw)
    }

    private suspend fun editRecentSearches(transform: (MutableList<String>) -> Unit) {
        dataStore.edit { prefs ->
            val current = parseJsonList(prefs[Keys.RECENT_SEARCHES] ?: "").toMutableList()
            transform(current)
            prefs[Keys.RECENT_SEARCHES] = toJsonList(current)
        }
    }

    suspend fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        editRecentSearches { current ->
            current.removeAll { it.equals(trimmed, ignoreCase = true) }
            current.add(0, trimmed)
            if (current.size > MAX_SEARCH_HISTORY_ITEMS) {
                val capped = current.take(MAX_SEARCH_HISTORY_ITEMS)
                current.clear()
                current.addAll(capped)
            }
        }
    }

    suspend fun removeSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        editRecentSearches { current ->
            current.removeAll { it.equals(trimmed, ignoreCase = true) }
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.RECENT_SEARCHES)
        }
    }

    private fun parseJsonList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(raw)
            val list = ArrayList<String>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optString(i)
                if (!item.isNullOrBlank()) {
                    list.add(item)
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toJsonList(list: List<String>): String {
        val jsonArray = JSONArray()
        for (item in list) {
            jsonArray.put(item)
        }
        return jsonArray.toString()
    }
}
