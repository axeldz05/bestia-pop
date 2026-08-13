package com.bestiapop.android.data.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * L1 byte-copy primitive shared by large OkHttp transfers.
 * Retry, Range, validation, publication and cleanup remain with each domain owner.
 */
internal fun copyTransferToFile(
    input: InputStream,
    destination: File,
    append: Boolean = false,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    syncToDisk: Boolean = false,
    checkCancelled: () -> Unit = {},
    onBytesCopied: (Long) -> Unit = {}
): Long = FileOutputStream(destination, append).use { output ->
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        checkCancelled()
        output.write(buffer, 0, count)
        copied += count
        onBytesCopied(copied)
    }
    output.flush()
    if (syncToDisk) output.fd.sync()
    copied
}
