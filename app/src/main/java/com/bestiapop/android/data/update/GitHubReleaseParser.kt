package com.bestiapop.android.data.update

import com.bestiapop.android.data.util.optNullableString
import org.json.JSONObject

object GitHubReleaseParser {

    fun parseReleaseApi(body: String): GitHubReleaseAssets? {
        val obj = JSONObject(body)
        val assets = obj.optJSONArray("assets") ?: return null
        var preferredApk: String? = null
        var fallbackApk: String? = null
        var latestJsonUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optNullableString("browser_download_url") ?: continue
            when {
                name.equals("latest.json", ignoreCase = true) -> latestJsonUrl = url
                name.endsWith(".apk", ignoreCase = true) -> {
                    if (name.startsWith("BestiaPop", ignoreCase = true)) {
                        preferredApk = preferredApk ?: url
                    } else {
                        fallbackApk = fallbackApk ?: url
                    }
                }
            }
        }
        val apkUrl = preferredApk ?: fallbackApk ?: return null
        val jsonUrl = latestJsonUrl ?: return null
        return GitHubReleaseAssets(
            apkUrl = apkUrl,
            latestJsonUrl = jsonUrl,
            changelog = obj.optNullableString("body")
        )
    }

    fun parseLatestJson(body: String): AppUpdateVersion? {
        val obj = JSONObject(body)
        val code = obj.optInt("versionCode", -1)
        val name = obj.optNullableString("versionName") ?: return null
        if (code < 0) return null
        return AppUpdateVersion(versionCode = code, versionName = name)
    }
}
