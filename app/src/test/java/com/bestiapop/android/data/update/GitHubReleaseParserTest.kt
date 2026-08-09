package com.bestiapop.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseParserTest {

    @Test
    fun parseReleaseApi_picksBestiaPopApkAndLatestJson() {
        val body = """
            {
              "tag_name": "v1.0-beta.5",
              "body": "versionCode: 6\n\nFixes",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://example.com/notes.txt"
                },
                {
                  "name": "other.apk",
                  "browser_download_url": "https://example.com/other.apk"
                },
                {
                  "name": "BestiaPop-1.0-beta.5.apk",
                  "browser_download_url": "https://example.com/BestiaPop-1.0-beta.5.apk"
                },
                {
                  "name": "latest.json",
                  "browser_download_url": "https://example.com/latest.json"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubReleaseParser.parseReleaseApi(body)!!
        assertEquals("https://example.com/BestiaPop-1.0-beta.5.apk", parsed.apkUrl)
        assertEquals("https://example.com/latest.json", parsed.latestJsonUrl)
        assertEquals("versionCode: 6\n\nFixes", parsed.changelog)
    }

    @Test
    fun parseReleaseApi_fallsBackToAnyApk() {
        val body = """
            {
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.com/app-release.apk"
                },
                {
                  "name": "latest.json",
                  "browser_download_url": "https://example.com/latest.json"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubReleaseParser.parseReleaseApi(body)!!
        assertEquals("https://example.com/app-release.apk", parsed.apkUrl)
    }

    @Test
    fun parseReleaseApi_returnsNullWithoutLatestJson() {
        val body = """
            {
              "assets": [
                {
                  "name": "BestiaPop-1.0.apk",
                  "browser_download_url": "https://example.com/BestiaPop-1.0.apk"
                }
              ]
            }
        """.trimIndent()
        assertNull(GitHubReleaseParser.parseReleaseApi(body))
    }

    @Test
    fun parseLatestJson_readsVersion() {
        val parsed = GitHubReleaseParser.parseLatestJson(
            """{"versionCode":6,"versionName":"1.0-beta.5"}"""
        )!!
        assertEquals(6, parsed.versionCode)
        assertEquals("1.0-beta.5", parsed.versionName)
    }

    @Test
    fun parseLatestJson_rejectsMissingCode() {
        assertNull(GitHubReleaseParser.parseLatestJson("""{"versionName":"1.0"}"""))
    }
}
