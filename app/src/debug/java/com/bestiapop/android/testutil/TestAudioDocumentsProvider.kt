package com.bestiapop.android.testutil

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only SAF boundary for instrumented tests.
 *
 * It lives in the target APK so the app owns the MANAGE_DOCUMENTS-protected provider; release builds
 * never package it. Each test gets a UUID namespace and locally generated WAV/PNG bytes.
 */
class TestAudioDocumentsProvider : DocumentsProvider() {
    private val invalidNamespaces = mutableSetOf<String>()

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            addRow(columnNames.map { column ->
                when (column) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_TITLE -> "BestiaPop test audio"
                    DocumentsContract.Root.COLUMN_FLAGS -> 0
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> "$AUDIO_MIME\n$IMAGE_MIME"
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> AVAILABLE_BYTES
                    else -> null
                }
            })
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocumentRow(documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (parentDocumentId == ROOT_ID) return MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val namespace = requireNotNull(rootNamespace(parentDocumentId)) {
            "Unknown parent document: $parentDocumentId"
        }
        childQueryCounts.getOrPut(namespace) { AtomicInteger() }.incrementAndGet()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            addDocumentRow(audioDocumentId(parentDocumentId))
            addDocumentRow(imageDocumentId(parentDocumentId))
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only test provider")
        val namespace = documentNamespace(documentId)
            ?: throw FileNotFoundException("Unknown document: $documentId")
        synchronized(invalidNamespaces) {
            if (namespace in invalidNamespaces) {
                throw FileNotFoundException("Provider namespace invalidated: $namespace")
            }
        }
        if (documentId.endsWith(AUDIO_SUFFIX)) {
            audioOpenCounts.getOrPut(namespace) { AtomicInteger() }.incrementAndGet()
        }
        return ParcelFileDescriptor.open(
            fixtureFile(namespace, documentId),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == audioDocumentId(parentDocumentId) ||
            documentId == imageDocumentId(parentDocumentId)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val namespace = requireNotNull(arg) { "Namespace is required for $method" }
        when (method) {
            METHOD_ACTIVATE -> {
                synchronized(invalidNamespaces) { invalidNamespaces.remove(namespace) }
                childQueryCounts[namespace] = AtomicInteger(0)
                audioOpenCounts[namespace] = AtomicInteger(0)
            }
            METHOD_INVALIDATE -> synchronized(invalidNamespaces) { invalidNamespaces.add(namespace) }
            METHOD_DELETE -> {
                synchronized(invalidNamespaces) { invalidNamespaces.remove(namespace) }
                childQueryCounts.remove(namespace)
                audioOpenCounts.remove(namespace)
                val dir = namespaceDir(namespace)
                check(!dir.exists() || dir.deleteRecursively()) {
                    "Could not delete provider namespace ${dir.absolutePath}"
                }
            }
            else -> return super.call(method, arg, extras) ?: Bundle.EMPTY
        }
        return Bundle.EMPTY
    }

    private fun MatrixCursor.addDocumentRow(documentId: String) {
        val namespace = documentNamespace(documentId)
        if (documentId != ROOT_ID && namespace == null) {
            throw FileNotFoundException("Unknown document: $documentId")
        }
        addRow(columnNames.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> documentDisplayName(documentId)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> documentMime(documentId)
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                DocumentsContract.Document.COLUMN_SIZE ->
                    if (documentId.endsWith(AUDIO_SUFFIX)) AUDIO_SIZE_BYTES else null
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> FIXTURE_TIMESTAMP_MS
                else -> null
            }
        })
    }

    private fun fixtureFile(namespace: String, documentId: String): File {
        val dir = namespaceDir(namespace)
        check(dir.exists() || dir.mkdirs()) { "Could not create ${dir.absolutePath}" }
        return when (documentId) {
            audioDocumentId(rootDocumentId(namespace)) -> File(dir, AUDIO_NAME).apply {
                if (!isFile) writeBytes(generateSilentWav())
            }
            imageDocumentId(rootDocumentId(namespace)) -> File(dir, IMAGE_NAME).apply {
                if (!isFile) {
                    outputStream().use { output ->
                        val bitmap = Bitmap.createBitmap(
                            intArrayOf(0xff1b4965.toInt(), 0xffcae9ff.toInt()),
                            2,
                            1,
                            Bitmap.Config.ARGB_8888
                        )
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        bitmap.recycle()
                    }
                }
            }
            else -> throw FileNotFoundException("Document has no bytes: $documentId")
        }
    }

    private fun generateSilentWav(): ByteArray {
        val dataSize = AUDIO_SIZE_BYTES.toInt() - WAV_HEADER_BYTES
        return ByteArrayOutputStream(AUDIO_SIZE_BYTES.toInt()).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(36 + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianInt(WAV_SAMPLE_RATE_HZ.toInt())
            output.writeLittleEndianInt(WAV_SAMPLE_RATE_HZ.toInt() * WAV_BYTES_PER_SAMPLE.toInt())
            output.writeLittleEndianShort(WAV_BYTES_PER_SAMPLE.toInt())
            output.writeLittleEndianShort(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(dataSize)
            output.write(ByteArray(dataSize))
            output.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        repeat(Int.SIZE_BYTES) { write(value ushr (it * Byte.SIZE_BITS) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        repeat(Short.SIZE_BYTES) { write(value ushr (it * Byte.SIZE_BITS) and 0xff) }
    }

    private fun namespaceDir(namespace: String): File =
        File(requireNotNull(context).cacheDir, "$CACHE_PREFIX$namespace")

    private fun documentDisplayName(documentId: String): String = when {
        documentId == ROOT_ID || rootNamespace(documentId) != null -> "Fixture tree"
        documentId.endsWith(AUDIO_SUFFIX) -> AUDIO_NAME
        documentId.endsWith(IMAGE_SUFFIX) -> IMAGE_NAME
        else -> throw FileNotFoundException("Unknown document: $documentId")
    }

    private fun documentMime(documentId: String): String = when {
        documentId == ROOT_ID || rootNamespace(documentId) != null ->
            DocumentsContract.Document.MIME_TYPE_DIR
        documentId.endsWith(AUDIO_SUFFIX) -> AUDIO_MIME
        documentId.endsWith(IMAGE_SUFFIX) -> IMAGE_MIME
        else -> throw FileNotFoundException("Unknown document: $documentId")
    }

    private fun documentNamespace(documentId: String): String? {
        rootNamespace(documentId)?.let { return it }
        return when {
            documentId.endsWith(AUDIO_SUFFIX) ->
                rootNamespace(documentId.removeSuffix(AUDIO_SUFFIX))
            documentId.endsWith(IMAGE_SUFFIX) ->
                rootNamespace(documentId.removeSuffix(IMAGE_SUFFIX))
            else -> null
        }
    }

    private fun rootNamespace(documentId: String): String? {
        if (!documentId.startsWith(ROOT_PREFIX)) return null
        return documentId.removePrefix(ROOT_PREFIX)
            .takeIf { runCatching { UUID.fromString(it) }.isSuccess }
    }

    companion object {
        const val AUTHORITY = "com.bestiapop.android.test.audio.documents"
        const val AUDIO_DURATION_MS = 31_500

        private const val ROOT_ID = "root"
        private const val ROOT_PREFIX = "root:"
        private const val AUDIO_SUFFIX = ":audio"
        private const val IMAGE_SUFFIX = ":image"
        private const val AUDIO_NAME = "BestiaPop SAF fixture.wav"
        private const val IMAGE_NAME = "BestiaPop SAF cover.png"
        private const val AUDIO_MIME = "audio/wav"
        private const val IMAGE_MIME = "image/png"
        private const val CACHE_PREFIX = "saf-provider-"
        private const val METHOD_ACTIVATE = "activate"
        private const val METHOD_INVALIDATE = "invalidate"
        private const val METHOD_DELETE = "delete"
        private const val FIXTURE_TIMESTAMP_MS = 1_700_000_000_000L
        private const val AVAILABLE_BYTES = 4L * 1024L * 1024L
        private const val WAV_HEADER_BYTES = 44
        private const val WAV_SAMPLE_RATE_HZ = 16_000L
        private const val WAV_BYTES_PER_SAMPLE = 2L
        private val AUDIO_SIZE_BYTES =
            WAV_HEADER_BYTES +
                WAV_SAMPLE_RATE_HZ * AUDIO_DURATION_MS / 1_000L * WAV_BYTES_PER_SAMPLE
        private val childQueryCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val audioOpenCounts = ConcurrentHashMap<String, AtomicInteger>()

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        fun treeUri(namespace: UUID): Uri = DocumentsContract.buildTreeDocumentUri(
            AUTHORITY,
            rootDocumentId(namespace.toString())
        )

        fun audioUri(namespace: UUID): Uri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri(namespace),
            audioDocumentId(rootDocumentId(namespace.toString()))
        )

        fun imageUri(namespace: UUID): Uri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri(namespace),
            imageDocumentId(rootDocumentId(namespace.toString()))
        )

        fun activate(context: Context, namespace: UUID) =
            call(context.contentResolver, namespace, METHOD_ACTIVATE)

        fun invalidate(context: Context, namespace: UUID) =
            call(context.contentResolver, namespace, METHOD_INVALIDATE)

        fun delete(context: Context, namespace: UUID) =
            call(context.contentResolver, namespace, METHOD_DELETE)

        fun childQueryCount(namespace: UUID): Int =
            childQueryCounts[namespace.toString()]?.get() ?: 0

        fun audioOpenCount(namespace: UUID): Int =
            audioOpenCounts[namespace.toString()]?.get() ?: 0

        private fun call(resolver: ContentResolver, namespace: UUID, method: String) {
            requireNotNull(
                resolver.call(
                    Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                        .authority(AUTHORITY)
                        .build(),
                    method,
                    namespace.toString(),
                    null
                )
            )
        }

        private fun rootDocumentId(namespace: String) = "$ROOT_PREFIX$namespace"
        private fun audioDocumentId(rootDocumentId: String) = "$rootDocumentId$AUDIO_SUFFIX"
        private fun imageDocumentId(rootDocumentId: String) = "$rootDocumentId$IMAGE_SUFFIX"
    }
}
