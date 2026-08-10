package com.bestiapop.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseParserTest {

    @Test
    fun parseReleaseApi_readsVersionFromBodyAndPreferredApk() {
        val body = """
            {
              "tag_name": "v1.0-beta.5",
              "body": "BestiaPop 1.0-beta.5\n\nversionCode: 6\n\nFixes",
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
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubReleaseParser.parseReleaseApi(body)!!
        assertEquals(6, parsed.versionCode)
        assertEquals("1.0-beta.5", parsed.versionName)
        assertEquals("https://example.com/BestiaPop-1.0-beta.5.apk", parsed.apkUrl)
    }

    @Test
    fun parseReleaseApi_fallsBackToAnyApk() {
        val body = """
            {
              "tag_name": "v2.0",
              "body": "versionCode: 10",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.com/app-release.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubReleaseParser.parseReleaseApi(body)!!
        assertEquals("https://example.com/app-release.apk", parsed.apkUrl)
        assertEquals(10, parsed.versionCode)
        assertEquals("2.0", parsed.versionName)
    }

    @Test
    fun parseReleaseApi_returnsNullWithoutVersionCode() {
        val body = """
            {
              "tag_name": "v1.0",
              "body": "solo changelog",
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
    fun parseReleaseApi_returnsNullWithoutApk() {
        val body = """
            {
              "tag_name": "v1.0",
              "body": "versionCode: 1",
              "assets": []
            }
        """.trimIndent()
        assertNull(GitHubReleaseParser.parseReleaseApi(body))
    }

    @Test
    fun parseVersionCode_readsLine() {
        assertEquals(6, GitHubReleaseParser.parseVersionCode("BestiaPop\n\nversionCode: 6\n"))
        assertNull(GitHubReleaseParser.parseVersionCode("no code here"))
    }
}
