package com.bestiapop.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubReleaseUrlsTest {

    @Test
    fun latestApkDownloadUrl_formatsDirectDownloadPath() {
        val url = GitHubReleaseUrls.latestApkDownloadUrl("axeldz05/bestia-pop")
        assertEquals("https://github.com/axeldz05/bestia-pop/releases/latest/download/BestiaPop.apk", url)
    }

    @Test
    fun latestPageUrl_formatsReleasesPagePath() {
        val url = GitHubReleaseUrls.latestPageUrl("axeldz05/bestia-pop")
        assertEquals("https://github.com/axeldz05/bestia-pop/releases/latest", url)
    }

    @Test
    fun repoUrl_trimsWhitespace() {
        val url = GitHubReleaseUrls.repoUrl("  axeldz05/bestia-pop  ")
        assertEquals("https://github.com/axeldz05/bestia-pop", url)
    }
}
