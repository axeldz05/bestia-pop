package com.bestiapop.android.data.util

import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaDemuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractAudioTrack_returnsFalse_whenSourceDoesNotExist() {
        val nonExistent = File(tempFolder.root, "does_not_exist.mp4")
        val dest = File(tempFolder.root, "output.m4a")

        val result = MediaDemuxer.extractAudioTrack(nonExistent, dest)

        assertFalse(result)
        assertFalse(dest.exists())
    }

    @Test
    fun extractAudioTrack_returnsFalse_whenSourceIsEmpty() {
        val emptySource = tempFolder.newFile("empty.mp4")
        val dest = File(tempFolder.root, "output.m4a")

        val result = MediaDemuxer.extractAudioTrack(emptySource, dest)

        assertFalse(result)
        assertFalse(dest.exists())
    }
}
