package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioTagWriterTest {

    @Test
    fun supportedExtensions() {
        assertTrue(AudioTagWriter.isSupportedExtension(File("/tmp/a.mp3")))
        assertTrue(AudioTagWriter.isSupportedExtension(File("/tmp/a.m4a")))
        assertTrue(AudioTagWriter.isSupportedExtension(File("/tmp/a.FLAC")))
        assertTrue(AudioTagWriter.isSupportedExtension(File("/tmp/a.ogg")))
        assertFalse(AudioTagWriter.isSupportedExtension(File("/tmp/a.webm")))
        assertFalse(AudioTagWriter.isSupportedExtension(File("/tmp/a.wav")))
    }

    @Test
    fun localArtworkPath_acceptsFileAndAbs() {
        assertEquals("/data/cover.jpg", AudioTagWriter.localArtworkPath("file:///data/cover.jpg"))
        assertEquals("/data/cover.jpg", AudioTagWriter.localArtworkPath("/data/cover.jpg"))
        assertNull(AudioTagWriter.localArtworkPath("https://cdn.example/a.jpg"))
        assertNull(AudioTagWriter.localArtworkPath("content://media/1"))
        assertNull(AudioTagWriter.localArtworkPath(""))
        assertNull(AudioTagWriter.localArtworkPath(null))
    }

    @Test
    fun write_missingFile_isNotWritable() {
        val song = com.bestiapop.android.data.model.Song(
            uriString = "/missing/song.mp3",
            title = "T",
            artist = "A",
            album = "Alb",
            folderPath = "/missing"
        )
        val result = AudioTagWriter.write(song, File("/missing/does-not-exist.mp3"))
        assertEquals(TagWriteResult.NotWritable, result)
    }

    @Test
    fun write_unsupportedExt_isUnsupported() {
        val tmp = File.createTempFile("bestiapop-tag", ".webm")
        try {
            tmp.writeBytes(ByteArray(16))
            tmp.setWritable(true)
            val song = com.bestiapop.android.data.model.Song(
                uriString = tmp.absolutePath,
                title = "T",
                artist = "A",
                album = "Alb",
                folderPath = tmp.parent.orEmpty()
            )
            assertEquals(TagWriteResult.Unsupported, AudioTagWriter.write(song, tmp))
        } finally {
            tmp.delete()
        }
    }
}
