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
    fun toAbsolutePath_decodesSafTreeDocumentUris() {
        assertEquals(
            "/storage/emulated/0/Music/BestiaPop/14_trackerplatz.mp3",
            SongPathNormalizer.toAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBestiaPop/document/primary%3AMusic%2FBestiaPop%2F14_trackerplatz.mp3"
            )
        )
        assertEquals(
            "/storage/ABCD-1234/Music/BestiaPop/a.mp3",
            SongPathNormalizer.toAbsolutePath(
                "content://com.android.externalstorage.documents/tree/ABCD-1234%3AMusic%2FBestiaPop/document/ABCD-1234%3AMusic%2FBestiaPop%2Fa.mp3"
            )
        )
    }

    @Test
    fun fileName_usesBasename() {
        assertEquals(
            "a.mp3",
            SongPathNormalizer.fileName("/storage/emulated/0/Music/BestiaPop/a.mp3")
        )
    }

    @Test
    fun isUnderBestiaPop_detectsAppFolder() {
        assertTrue(SongPathNormalizer.isUnderBestiaPop("/storage/emulated/0/Music/BestiaPop/x.mp3"))
        assertTrue(SongPathNormalizer.isUnderBestiaPop("Music/BestiaPop"))
        assertTrue(
            SongPathNormalizer.isUnderBestiaPop(
                "/storage/emulated/0/Android/data/com.bestiapop.android/files/Music/BestiaPop/x.mp3"
            )
        )
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
        assertTrue(
            SongPathNormalizer.isAppOwnedUri(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBestiaPop/document/primary%3AMusic%2FBestiaPop%2Fx.mp3"
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
