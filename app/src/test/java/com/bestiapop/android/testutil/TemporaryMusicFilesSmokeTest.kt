package com.bestiapop.android.testutil

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(SmallTest::class)
class TemporaryMusicFilesSmokeTest {
    @get:Rule
    val files = TemporaryMusicFiles()

    @Test
    fun create_writesProvidedBytesInsideOwnedRoot() {
        val bytes = byteArrayOf(0x49, 0x44, 0x33)

        val file = files.create("tiny.mp3", bytes)

        assertEquals(files.root, file.parentFile)
        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
    }
}
