package com.bestiapop.android.testutil

import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-test files for storage/download scenarios.
 *
 * This helper only owns filesystem lifecycle. Tests provide their own bytes so no fixture can
 * accidentally depend on personal media or the network.
 */
class TemporaryMusicFiles : ExternalResource() {
    private val folder = TemporaryFolder()

    val root: File
        get() = folder.root

    override fun before() {
        folder.create()
    }

    override fun after() {
        folder.delete()
    }

    fun create(name: String, bytes: ByteArray = ByteArray(0)): File {
        require(name.isNotBlank()) { "Temporary music file name must not be blank" }
        require('/' !in name && '\\' !in name) {
            "Temporary music file name must not contain path separators: $name"
        }
        return folder.newFile(name).apply { writeBytes(bytes) }
    }
}
