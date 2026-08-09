package com.bestiapop.android.data.update

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String? = null
)

data class GitHubReleaseAssets(
    val apkUrl: String,
    val latestJsonUrl: String,
    val changelog: String? = null
)

data class AppUpdateVersion(
    val versionCode: Int,
    val versionName: String
)

object GitHubReleaseUrls {
    fun latestPageUrl(repository: String): String =
        "https://github.com/${repository.trim()}/releases/latest"

    fun apiLatestUrl(repository: String): String =
        "https://api.github.com/repos/${repository.trim()}/releases/latest"
}
