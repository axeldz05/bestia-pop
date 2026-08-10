package com.bestiapop.android.data.update

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String? = null
)

object GitHubReleaseUrls {
    fun latestPageUrl(repository: String): String =
        "https://github.com/${repository.trim()}/releases/latest"

    fun apiLatestUrl(repository: String): String =
        "https://api.github.com/repos/${repository.trim()}/releases/latest"
}
