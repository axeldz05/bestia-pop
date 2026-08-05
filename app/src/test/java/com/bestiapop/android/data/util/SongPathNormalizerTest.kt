package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPathNormalizerTest {

    @Test
    fun toAbsolutePath_handlesFileUriVariants() {
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/a.mp3",
            SongPathNormalizer.toAbsolutePath("file:/storage/emulated/0/Music/BestiaPop/a.mp3")
        )
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/a.mp3",
            SongPathNormalizer.toAbsolutePath("file:///storage/emulated/0/Music/BestiaPop/a.mp3")
        )
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/a.mp3",
            SongPathNormalizer.toAbsolutePath("/storage/emulated/0/Music/BestiaPop/a.mp3")
        )
        assertNull(SongPathNormalizer.toAbsolutePath("content://media/external/audio/media/1"))
    }

    @Test
    fun isUnderBestiaPop_detectsAppFolder() {
        assertTrue(SongPathNormalizer.isUnderBestiaPop("/storage/emulated/0/Music/BestiaPop/x.mp3"))
        assertTrue(SongPathNormalizer.isUnderBestiaPop("Music/BestiaPop"))
        assertFalse(SongPathNormalizer.isUnderBestiaPop("/storage/emulated/0/Download/x.mp3"))
    }

    @Test
    fun isAppOwnedUri_rejectsMediaStore() {
        assertFalse(
            SongPathNormalizer.isAppOwnedUri(
                "content://media/external/audio/media/1",
                "/storage/emulated/0/Music/BestiaPop/x.mp3"
            )
        )
        assertTrue(
            SongPathNormalizer.isAppOwnedUri(
                "/storage/emulated/0/Music/BestiaPop/x.mp3",
                "Music/BestiaPop"
            )
        )
    }

    @Test
    fun resolveFilePath_usesFolderPathForMediaStore() {
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/x.mp3",
            SongPathNormalizer.resolveFilePath(
                "content://media/external/audio/media/1",
                "/storage/emulated/0/Music/BestiaPop/x.mp3"
            )
        )
    }

    @Test
    fun isSafeToDeleteAppManagedFile() {
        assertTrue(SongPathNormalizer.isSafeToDeleteAppManagedFile("/storage/emulated/0/Music/BestiaPop/a.mp3"))
        assertTrue(SongPathNormalizer.isSafeToDeleteAppManagedFile("/storage/emulated/0/Download/a.flac"))
        assertFalse(SongPathNormalizer.isSafeToDeleteAppManagedFile("/storage/emulated/0/DCIM/a.mp3"))
    }
}
