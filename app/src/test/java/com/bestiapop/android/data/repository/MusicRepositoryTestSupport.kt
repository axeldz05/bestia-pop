package com.bestiapop.android.data.repository

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.util.AudioPersistRef
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import java.io.File

internal class TemporaryRepositoryFileStore(
    private val root: File
) : RepositoryFileStore {
    override fun canonicalize(uriString: String, folderPath: String): AudioPersistRef =
        AudioPersistRef.canonicalize(uriString, folderPath)

    override fun applyDataSource(retriever: MediaMetadataRetriever, ref: AudioPersistRef) {
        retriever.setDataSource(requireFile(ref).absolutePath)
    }

    override fun applyDataSource(extractor: MediaExtractor, ref: AudioPersistRef) {
        extractor.setDataSource(requireFile(ref).absolutePath)
    }

    override fun applyDataSource(player: MediaPlayer, ref: AudioPersistRef) {
        player.setDataSource(requireFile(ref).absolutePath)
    }

    override fun prepareWrite(displayName: String): StorageUtils.PendingWrite {
        val destination = File(root, displayName)
        return StorageUtils.PendingWrite(destination) { destination.absolutePath }
    }

    override fun delete(ref: AudioPersistRef) {
        resolveFile(ref)?.delete()
    }

    override fun listManaged(): List<File> = root.listFiles()?.filter(File::isFile).orEmpty()

    override fun writableFile(uriString: String, folderPath: String): File? {
        val file = resolveFile(canonicalize(uriString, folderPath)) ?: return null
        return file.takeIf { it.isFile && it.canWrite() }
    }

    private fun requireFile(ref: AudioPersistRef): File =
        checkNotNull(resolveFile(ref)) { "Not a local test file: ${ref.uriString}" }

    private fun resolveFile(ref: AudioPersistRef): File? =
        SongPathNormalizer.resolveFilePath(ref.uriString, ref.folderPath)?.let(::File)
}

internal object NoNetworkRepositoryMetadata : RepositoryMetadataSource {
    override suspend fun fetchAlbumArtUrl(artist: String, titleOrAlbum: String): String? = null

    override suspend fun fetchLyrics(artist: String, title: String): String? = null

    override suspend fun fetchTrackDurationMs(artist: String, title: String): Long = 0L

    override suspend fun fetchFullTrackMetadata(artist: String, title: String): TrackIdentity? = null

    override suspend fun searchOnlineCatalog(
        query: String,
        limit: Int,
        index: Int
    ): List<OnlineCatalogTrack> = emptyList()
}
