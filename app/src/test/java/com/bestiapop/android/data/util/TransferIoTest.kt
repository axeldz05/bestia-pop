package com.bestiapop.android.data.util

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferIoTest {

    @Test
    fun copy_reportsCumulativeBytesAndSupportsAppend() {
        val dir = createTempDirectory("transfer-io-").toFile()
        val destination = File(dir, "payload.bin")
        val progress = mutableListOf<Long>()
        try {
            val first = copyTransferToFile(
                input = ByteArrayInputStream("abcd".toByteArray()),
                destination = destination,
                bufferSize = 2,
                onBytesCopied = progress::add
            )
            val second = copyTransferToFile(
                input = ByteArrayInputStream("ef".toByteArray()),
                destination = destination,
                append = true
            )

            assertEquals(4L, first)
            assertEquals(2L, second)
            assertEquals(listOf(2L, 4L), progress)
            assertArrayEquals("abcdef".toByteArray(), destination.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
