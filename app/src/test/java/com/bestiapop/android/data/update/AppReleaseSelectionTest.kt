package com.bestiapop.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseSelectionTest {

    private fun release(
        versionName: String,
        versionCode: Int?,
        apkUrl: String? = "https://example.com/BestiaPop-$versionName.apk"
    ) = AppRelease(
        versionCode = versionCode,
        versionName = versionName,
        tag = "v$versionName",
        notes = "notas $versionName",
        htmlUrl = "https://example.com/$versionName",
        publishedAtMs = null,
        apkUrl = apkUrl
    )

    @Test
    fun from_matchesCurrentByVersionCode() {
        val selection = AppReleaseSelection.from(
            releases = listOf(release("1.0-beta.7", 8), release("1.0-beta.5", 6)),
            currentVersionCode = 6,
            currentVersionName = "renombrada-a-mano"
        )

        assertEquals("notas 1.0-beta.5", selection.current?.notes)
    }

    @Test
    fun from_fallsBackToTagWhenVersionCodeMissing() {
        val selection = AppReleaseSelection.from(
            releases = listOf(release("1.0-beta.5", versionCode = null)),
            currentVersionCode = 6,
            currentVersionName = "1.0-beta.5"
        )

        assertEquals("notas 1.0-beta.5", selection.current?.notes)
        assertTrue(selection.newer.isEmpty())
    }

    @Test
    fun from_accumulatesNewerReleasesDescending() {
        val selection = AppReleaseSelection.from(
            releases = listOf(
                release("1.0-beta.6", 7),
                release("1.0-beta.8", 9),
                release("1.0-beta.7", 8),
                release("1.0-beta.5", 6)
            ),
            currentVersionCode = 6,
            currentVersionName = "1.0-beta.5"
        )

        assertEquals(
            listOf("1.0-beta.8", "1.0-beta.7", "1.0-beta.6"),
            selection.newer.map { it.versionName }
        )
        assertEquals("1.0-beta.8", selection.updateTarget?.versionName)
    }

    @Test
    fun updateTarget_skipsNewestReleaseWithoutApk() {
        val selection = AppReleaseSelection.from(
            releases = listOf(
                release("1.0-beta.8", 9, apkUrl = null),
                release("1.0-beta.7", 8)
            ),
            currentVersionCode = 6,
            currentVersionName = "1.0-beta.5"
        )

        assertEquals("1.0-beta.8", selection.newer.first().versionName)
        assertEquals("1.0-beta.7", selection.updateTarget?.versionName)
    }

    @Test
    fun from_hasNoNewerWhenUpToDate() {
        val selection = AppReleaseSelection.from(
            releases = listOf(release("1.0-beta.5", 6), release("1.0-beta.4", 5)),
            currentVersionCode = 6,
            currentVersionName = "1.0-beta.5"
        )

        assertTrue(selection.newer.isEmpty())
        assertNull(selection.updateTarget)
    }
}
