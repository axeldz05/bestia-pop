package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUtilsTest {

    @Test
    fun mimeFromFileName_mapsCommonAudioExts() {
        assertEquals("audio/mpeg", StorageUtils.mimeFromFileName("a.mp3"))
        assertEquals("audio/flac", StorageUtils.mimeFromFileName("A.FLAC"))
        assertEquals("audio/mp4", StorageUtils.mimeFromFileName("x.m4a"))
        assertEquals("audio/*", StorageUtils.mimeFromFileName("x.bin"))
    }

    @Test
    fun isBestiaPopLocation_matchesRelativeAndDataPaths() {
        assertTrue(StorageUtils.isBestiaPopLocation("Music/BestiaPop/", null))
        assertTrue(StorageUtils.isBestiaPopLocation("Music/BestiaPop", "/storage/emulated/0/Music/BestiaPop/a.mp3"))
        assertTrue(
            StorageUtils.isBestiaPopLocation(
                null,
                "/storage/emulated/0/Music/BestiaPop/Radiohead_Creep.mp3"
            )
        )
        assertFalse(StorageUtils.isBestiaPopLocation("Music/Other/", "/storage/emulated/0/Download/a.mp3"))
        assertFalse(StorageUtils.isBestiaPopLocation(null, "/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun userVisibleMusicDirLabel_isSpanishMusicFolder() {
        assertEquals("Música/BestiaPop", StorageUtils.userVisibleMusicDirLabel())
    }
}
