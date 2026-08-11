package com.bestiapop.android.data.update

/**
 * One published GitHub release of the app. `versionCode` / `apkUrl` are null for releases that
 * predate the `versionCode: N` convention or ship no APK: those still show notes but can't install.
 */
data class AppRelease(
    val versionCode: Int?,
    val versionName: String,
    val tag: String,
    val notes: String?,
    val htmlUrl: String?,
    val publishedAtMs: Long?,
    val apkUrl: String?
) {
    val isInstallable: Boolean
        get() = versionCode != null && !apkUrl.isNullOrBlank()
}

object GitHubReleaseUrls {
    fun repoUrl(repository: String): String =
        "https://github.com/${repository.trim()}"

    fun latestPageUrl(repository: String): String =
        "${repoUrl(repository)}/releases/latest"

    fun apiReleasesUrl(repository: String): String =
        "https://api.github.com/repos/${repository.trim()}/releases?per_page=$RELEASES_PAGE_SIZE"

    private const val RELEASES_PAGE_SIZE = 30
}
