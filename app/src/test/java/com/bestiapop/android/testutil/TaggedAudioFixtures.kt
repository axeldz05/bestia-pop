package com.bestiapop.android.testutil

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioTagWriter
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

object TaggedAudioFixtures {
    fun copySilenceMp3(dest: File) {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/com/bestiapop/android/data/util/silence.mp3")
        ) { "Missing silence.mp3 test resource" }
        dest.outputStream().use { out -> stream.copyTo(out) }
    }

    fun pixelJpeg(): ByteArray {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/com/bestiapop/android/data/util/pixel.jpg")
        ) { "Missing pixel.jpg test resource" }
        return stream.use { it.readBytes() }
    }

    fun writeTaggedMp3(
        dest: File,
        title: String,
        artist: String,
        album: String,
        genre: String = "Electronica; Vocaloid",
        year: Int = 2023,
        trackNumber: Int = 2,
        lyrics: String? = "目にブラックホールがあります",
        artworkJpeg: ByteArray? = pixelJpeg()
    ): File {
        copySilenceMp3(dest)
        val song = Song(
            uriString = dest.absolutePath,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            folderPath = dest.parent.orEmpty()
        )
        val write = AudioTagWriter.write(song, dest)
        check(write is com.bestiapop.android.data.util.TagWriteResult.Success) {
            "Failed to write tags: $write"
        }
        if (lyrics != null || artworkJpeg != null) {
            val audioFile = AudioFileIO.read(dest)
            val tag = audioFile.tagOrCreateAndSetDefault
            if (lyrics != null) {
                tag.setField(FieldKey.LYRICS, lyrics)
            }
            if (artworkJpeg != null) {
                val artFile = File(dest.parentFile, dest.nameWithoutExtension + ".jpg")
                artFile.writeBytes(artworkJpeg)
                tag.deleteArtworkField()
                tag.setField(ArtworkFactory.createArtworkFromFile(artFile))
                artFile.delete()
            }
            audioFile.commit()
        }
        return dest
    }
}
