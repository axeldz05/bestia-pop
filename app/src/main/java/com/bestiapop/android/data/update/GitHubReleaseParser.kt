package com.bestiapop.android.data.update

import com.bestiapop.android.data.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object GitHubReleaseParser {

    private val VERSION_CODE_LINE = Regex("""(?im)^\s*versionCode\s*:\s*(\d+)\s*$""")

    /**
     * One GitHub `/releases` payload → published releases, newest first as GitHub returns them.
     * Drafts and prereleases never come from `release.sh`, so they are dropped.
     */
    fun parseReleases(body: String): List<AppRelease> {
        val array = JSONArray(body)
        val releases = ArrayList<AppRelease>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) continue
            releases += parseRelease(obj) ?: continue
        }
        return releases
    }

    /**
     * [AppRelease.versionCode] comes from `versionCode: N` in the release body (written by
     * `release.sh`); [AppRelease.versionName] from `tag_name` (strip leading `v`) or `name`.
     */
    fun parseRelease(obj: JSONObject): AppRelease? {
        val versionName = versionNameFrom(obj) ?: return null
        val releaseBody = obj.optNullableString("body").orEmpty()
        return AppRelease(
            versionCode = parseVersionCode(releaseBody),
            versionName = versionName,
            tag = obj.optNullableString("tag_name")?.trim().orEmpty(),
            notes = stripVersionCodeLine(releaseBody).ifBlank { null },
            htmlUrl = obj.optNullableString("html_url"),
            publishedAtMs = parseTimestampMs(
                obj.optNullableString("published_at") ?: obj.optNullableString("created_at")
            ),
            apkUrl = findApkUrl(obj)
        )
    }

    fun parseVersionCode(releaseBody: String): Int? =
        VERSION_CODE_LINE.find(releaseBody)?.groupValues?.get(1)?.toIntOrNull()

    /** Drop machine `versionCode: N` lines before showing changelog to users. */
    fun stripVersionCodeLine(releaseBody: String): String =
        VERSION_CODE_LINE.replace(releaseBody, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun parseTimestampMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
    }

    private fun versionNameFrom(obj: JSONObject): String? {
        val tag = obj.optNullableString("tag_name")
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.trim()
            .orEmpty()
        if (tag.isNotEmpty()) return tag
        return obj.optNullableString("name")
            ?.removePrefix("BestiaPop")
            ?.trim()
            ?.trimStart('-', ' ')
            ?.ifBlank { null }
    }

    private fun findApkUrl(obj: JSONObject): String? {
        val assets = obj.optJSONArray("assets") ?: return null
        var preferredApk: String? = null
        var fallbackApk: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optNullableString("browser_download_url") ?: continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.startsWith("BestiaPop", ignoreCase = true)) {
                preferredApk = preferredApk ?: url
            } else {
                fallbackApk = fallbackApk ?: url
            }
        }
        return preferredApk ?: fallbackApk
    }
}
