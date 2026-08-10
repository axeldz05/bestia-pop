package com.bestiapop.android.data.update

import com.bestiapop.android.data.util.optNullableString
import org.json.JSONObject

object GitHubReleaseParser {

    private val VERSION_CODE_LINE = Regex("""(?im)^\s*versionCode\s*:\s*(\d+)\s*$""")

    /**
     * One GitHub `/releases/latest` payload → [AppUpdateInfo].
     * Requires an `.apk` asset and `versionCode: N` in the release body (written by `release.sh`).
     * [versionName] comes from `tag_name` (strip leading `v`) or `name`.
     */
    fun parseReleaseApi(body: String): AppUpdateInfo? {
        val obj = JSONObject(body)
        val releaseBody = obj.optNullableString("body").orEmpty()
        val versionCode = parseVersionCode(releaseBody) ?: return null
        val versionName = versionNameFrom(obj) ?: return null
        val apkUrl = findApkUrl(obj) ?: return null
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            changelog = stripVersionCodeLine(releaseBody).ifBlank { null }
        )
    }

    fun parseVersionCode(releaseBody: String): Int? =
        VERSION_CODE_LINE.find(releaseBody)?.groupValues?.get(1)?.toIntOrNull()

    /** Drop machine `versionCode: N` lines before showing changelog to users. */
    fun stripVersionCodeLine(releaseBody: String): String =
        VERSION_CODE_LINE.replace(releaseBody, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

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
