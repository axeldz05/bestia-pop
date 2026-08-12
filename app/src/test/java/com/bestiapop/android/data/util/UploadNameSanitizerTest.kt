package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNameSanitizerTest {

    @Test
    fun accentsAndSpacesBecomeUnderscores() {
        assertEquals(
            "01_-_Canci_n.mp3",
            UploadNameSanitizer.sanitize("01 - Canción.mp3")
        )
    }

    @Test
    fun stripsPathSeparators_keepsSafeChars() {
        assertEquals(
            "track_name-2.flac",
            UploadNameSanitizer.sanitize("/Music/BestiaPop/track name-2.flac")
        )
        assertEquals(
            "win_track.mp3",
            UploadNameSanitizer.sanitize("C:\\Uploads\\win track.mp3")
        )
    }

    @Test
    fun matchesDashboardAndStoredForm() {
        val rawBasename = "Álbum · Tema (live).m4a"
        val sanitized = UploadNameSanitizer.sanitize(rawBasename)
        // Same transform applied to Room basename and managed folder names.
        assertEquals(sanitized, UploadNameSanitizer.sanitize(sanitized))
        assertEquals("_lbum___Tema__live_.m4a", sanitized)
    }
}
