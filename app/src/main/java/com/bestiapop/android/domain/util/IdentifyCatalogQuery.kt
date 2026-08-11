package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifySearchFilters

/**
 * Builds Deezer-style advanced search strings for identify refine
 * (`artist:"…" album:"…" [free text] [year]`).
 */
object IdentifyCatalogQuery {

    fun build(freeText: String?, filters: IdentifySearchFilters = IdentifySearchFilters()): String {
        val f = filters.normalized()
        val parts = ArrayList<String>(4)
        if (f.artist.isNotEmpty()) parts.add("artist:\"${escapeQuotes(f.artist)}\"")
        if (f.album.isNotEmpty()) parts.add("album:\"${escapeQuotes(f.album)}\"")
        val free = freeText?.trim().orEmpty()
        if (free.isNotEmpty()) parts.add(free)
        if (f.year in 1000..9999) parts.add(f.year.toString())
        return parts.joinToString(" ").trim()
    }

    private fun escapeQuotes(value: String): String = value.replace("\"", "")
}
