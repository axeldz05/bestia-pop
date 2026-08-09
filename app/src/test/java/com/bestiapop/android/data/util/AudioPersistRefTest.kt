package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPersistRefTest {

    private val safPretext =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBestiaPop/document/primary%3AMusic%2FBestiaPop%2F01_pretext.mp3"
    private val absPretext = "/storage/emulated/0/Music/BestiaPop/01_pretext.mp3"
    private val absParent = "/storage/emulated/0/Music/BestiaPop"

    @Test
    fun canonicalize_safBestiaPop_becomesAbsPath() {
        val ref = AudioPersistRef.canonicalize(safPretext, "BestiaPop")
        assertEquals(absPretext, ref.uriString)
        assertEquals(absParent, ref.folderPath)
    }

    @Test
    fun canonicalize_absWithCacheFolder_fixesFolderPath() {
        val ref = AudioPersistRef.canonicalize(
            absPretext,
            "/data/user/0/com.bestiapop.android/cache"
        )
        assertEquals(absPretext, ref.uriString)
        assertEquals(absParent, ref.folderPath)
    }

    @Test
    fun canonicalize_absWithRelativeFolder_fixesFolderPath() {
        val ref = AudioPersistRef.canonicalize(absPretext, "Music/BestiaPop")
        assertEquals(absPretext, ref.uriString)
        assertEquals(absParent, ref.folderPath)
    }

    @Test
    fun canonicalize_mediaStore_keepsContentUri() {
        val media = "content://media/external/audio/media/42"
        val data = "/storage/emulated/0/Music/Other/track.mp3"
        val ref = AudioPersistRef.canonicalize(media, data)
        assertEquals(media, ref.uriString)
        assertEquals(data, ref.folderPath)
    }

    @Test
    fun canonicalize_unresolvedSaf_kept() {
        val saf = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/"
        val ref = AudioPersistRef.canonicalize(saf, "Download")
        assertEquals(saf, ref.uriString)
        assertEquals("Download", ref.folderPath)
    }
}
