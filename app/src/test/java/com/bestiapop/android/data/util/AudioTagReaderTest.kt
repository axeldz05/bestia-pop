package com.bestiapop.android.data.util

import com.bestiapop.android.testutil.TaggedAudioFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioTagReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parseTagYear_readsFourDigitFromDate() {
        assertEquals(2023, parseTagYear("2023"))
        assertEquals(2023, parseTagYear("2023-05-01"))
        assertEquals(0, parseTagYear(""))
        assertEquals(0, parseTagYear(null))
    }

    @Test
    fun coalesce_prefersPrimaryNonBlank() {
        val primary = RawAudioTags(artist = "namitape; Kaai Yuki", album = null, title = "A")
        val fallback = RawAudioTags(artist = "Other", album = "Flitter", title = "B")
        val merged = coalesceRawTags(primary, fallback)
        assertEquals("namitape; Kaai Yuki", merged.artist)
        assertEquals("Flitter", merged.album)
        assertEquals("A", merged.title)
    }

    @Test
    fun read_roundTripsWriterTags() {
        val file = folder.newFile("tagged.mp3")
        TaggedAudioFixtures.writeTaggedMp3(
            dest = file,
            title = "ブラックホール / Black Hole",
            artist = "namitape; Kaai Yuki",
            album = "Flitter"
        )
        val tags = checkNotNull(AudioTagReader.read(file))
        assertEquals("ブラックホール / Black Hole", tags.title)
        assertEquals("namitape; Kaai Yuki", tags.artist)
        assertEquals("Flitter", tags.album)
        assertEquals("Electronica; Vocaloid", tags.genre)
        assertEquals(2023, tags.year)
        assertEquals(2, tags.trackNumber)
        assertEquals("目にブラックホールがあります", tags.lyrics)
        assertTrue(tags.artworkBytes != null && tags.artworkBytes!!.isNotEmpty())
        assertTrue(tags.durationMs > 0L)
    }

    @Test
    fun fromRawTags_keepsId3OverStrippedFilename() {
        val raw = RawAudioTags(
            title = "ブラックホール / Black Hole",
            artist = "namitape; Kaai Yuki",
            album = "Flitter",
            genre = "Electronica; Vocaloid",
            year = 2023,
            trackNumber = 2
        )
        val meta = AudioFileMetadata.fromRawTags(raw, "02__________Black_Hole_")
        assertEquals("namitape; Kaai Yuki", meta.artist)
        assertEquals("Flitter", meta.album)
        assertEquals("ブラックホール / Black Hole", meta.title)
        assertEquals("Electronica; Vocaloid", meta.genre)
        assertEquals(2023, meta.year)
        val song = meta.toSong("/tmp/x.mp3", "/tmp")
        assertEquals(2023, song.year)
        assertNull(song.lyrics)
    }

    @Test
    fun fromRawTags_filenameOnlyWhenReadersEmpty() {
        val meta = AudioFileMetadata.fromRawTags(RawAudioTags(), "02__________Black_Hole_")
        assertEquals("Unknown Artist", meta.artist)
        assertEquals("Unknown Album", meta.album)
        assertEquals("Black Hole", meta.title)
        assertEquals("Music", meta.genre)
        assertFalse(meta.year > 0)
    }
}
