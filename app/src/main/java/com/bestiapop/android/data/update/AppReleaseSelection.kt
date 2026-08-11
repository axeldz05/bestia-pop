package com.bestiapop.android.data.update

/**
 * What the installed build needs out of the release list: its own notes plus every release
 * published after it (accumulated changelog when versions were skipped).
 */
data class AppReleaseSelection(
    val current: AppRelease? = null,
    val newer: List<AppRelease> = emptyList()
) {
    /** Newest release that can actually be downloaded and installed. */
    val updateTarget: AppRelease?
        get() = newer.firstOrNull { it.isInstallable }

    companion object {
        fun from(
            releases: List<AppRelease>,
            currentVersionCode: Int,
            currentVersionName: String
        ): AppReleaseSelection {
            val currentTag = "v$currentVersionName"
            val current = releases.firstOrNull { it.versionCode == currentVersionCode }
                ?: releases.firstOrNull {
                    it.tag.equals(currentTag, ignoreCase = true) ||
                        it.versionName.equals(currentVersionName, ignoreCase = true)
                }
            val newer = releases
                .filter { (it.versionCode ?: Int.MIN_VALUE) > currentVersionCode }
                .sortedByDescending { it.versionCode }
            return AppReleaseSelection(current = current, newer = newer)
        }
    }
}
