package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadNameSanitizerTest {

    @Test
    fun accentsAndSpaces_keepLettersReplaceSpaces() {
        assertEquals(
            "01_-_Canción.mp3",
            UploadNameSanitizer.sanitize("01 - Canción.mp3")
        )
        assertEquals(
            "01_-_Canci_n.mp3",
            UploadNameSanitizer.asciiLegacy("01 - Canción.mp3")
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
        assertEquals(sanitized, UploadNameSanitizer.sanitize(sanitized))
        assertEquals("Álbum_·_Tema_(live).m4a", sanitized)
        assertEquals("_lbum___Tema__live_.m4a", UploadNameSanitizer.asciiLegacy(rawBasename))
    }

    @Test
    fun keepsCjk_andUnionsLegacyAscii() {
        assertEquals(
            "ブラックホール.mp3",
            UploadNameSanitizer.sanitize("ブラックホール.mp3")
        )
        val names = UploadNameSanitizer.matchingBasenames("ブラックホール.mp3")
        assertTrue(names.contains("ブラックホール.mp3"))
        assertTrue(names.contains(UploadNameSanitizer.asciiLegacy("ブラックホール.mp3")))
        assertEquals(
            "a_b.mp3",
            UploadNameSanitizer.sanitize("a:b.mp3")
        )
    }

    @Test
    fun dotOnlyNames_fallbackToSafeAudioTimestamp() {
        val dotSanitized = UploadNameSanitizer.sanitize("..")
        assertTrue(dotSanitized.startsWith("audio_") && dotSanitized.endsWith(".mp3"))
        val singleDotSanitized = UploadNameSanitizer.sanitize(".")
        assertTrue(singleDotSanitized.startsWith("audio_") && singleDotSanitized.endsWith(".mp3"))
    }
}
