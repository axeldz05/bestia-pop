package com.bestiapop.android.data.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {

    private fun parse(json: String): AppRelease? =
        GitHubReleaseParser.parseRelease(JSONObject(json))

    @Test
    fun parseRelease_readsVersionFromBodyAndPreferredApk() {
        val parsed = parse(
            """
            {
              "tag_name": "v1.0-beta.5",
              "html_url": "https://github.com/o/r/releases/tag/v1.0-beta.5",
              "published_at": "2026-08-10T14:23:15Z",
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
        )!!

        assertEquals(6, parsed.versionCode)
        assertEquals("1.0-beta.5", parsed.versionName)
        assertEquals("v1.0-beta.5", parsed.tag)
        assertEquals("https://example.com/BestiaPop-1.0-beta.5.apk", parsed.apkUrl)
        assertEquals("BestiaPop 1.0-beta.5\n\nFixes", parsed.notes)
        assertEquals("https://github.com/o/r/releases/tag/v1.0-beta.5", parsed.htmlUrl)
        assertEquals(1786371795000L, parsed.publishedAtMs)
        assertTrue(parsed.isInstallable)
    }

    @Test
    fun parseRelease_fallsBackToAnyApk() {
        val parsed = parse(
            """
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
        )!!

        assertEquals("https://example.com/app-release.apk", parsed.apkUrl)
        assertEquals(10, parsed.versionCode)
        assertEquals("2.0", parsed.versionName)
        assertNull(parsed.notes)
        assertNull(parsed.publishedAtMs)
    }

    @Test
    fun parseRelease_keepsNotesWithoutVersionCodeOrApk() {
        val parsed = parse(
            """
            {
              "tag_name": "v0.9",
              "body": "solo changelog",
              "assets": []
            }
            """.trimIndent()
        )!!

        assertNull(parsed.versionCode)
        assertNull(parsed.apkUrl)
        assertEquals("solo changelog", parsed.notes)
        assertFalse(parsed.isInstallable)
    }

    @Test
    fun parseReleases_dropsDraftsAndPrereleases() {
        val releases = GitHubReleaseParser.parseReleases(
            """
            [
              {
                "tag_name": "v1.2",
                "body": "versionCode: 12",
                "assets": [
                  {
                    "name": "BestiaPop-1.2.apk",
                    "browser_download_url": "https://example.com/BestiaPop-1.2.apk"
                  }
                ]
              },
              {
                "tag_name": "v1.3",
                "draft": true,
                "body": "versionCode: 13",
                "assets": []
              },
              {
                "tag_name": "v1.4",
                "prerelease": true,
                "body": "versionCode: 14",
                "assets": []
              },
              {
                "tag_name": "v1.1",
                "body": "versionCode: 11",
                "assets": []
              }
            ]
            """.trimIndent()
        )

        assertEquals(listOf("1.2", "1.1"), releases.map { it.versionName })
    }

    @Test
    fun parseVersionCode_readsLine() {
        assertEquals(6, GitHubReleaseParser.parseVersionCode("BestiaPop\n\nversionCode: 6\n"))
        assertNull(GitHubReleaseParser.parseVersionCode("no code here"))
    }

    @Test
    fun stripVersionCodeLine_removesMachineLine() {
        assertEquals(
            "BestiaPop 1.0\n\nFixes",
            GitHubReleaseParser.stripVersionCodeLine("BestiaPop 1.0\n\nversionCode: 6\n\nFixes")
        )
        assertEquals("", GitHubReleaseParser.stripVersionCodeLine("versionCode: 10"))
    }
}
