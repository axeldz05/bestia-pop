package com.bestiapop.android.service.library

import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.FakeMusicRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryBrowseProviderTest {

    @Test
    fun snapshotIsReusedAndInvalidatedByRepositoryFlows() = runBlocking {
        val songs = MutableStateFlow(listOf(song(1L, "First")))
        val overrides = MutableStateFlow<List<AlbumOverride>>(emptyList())
        val playlists = MutableStateFlow<List<Playlist>>(emptyList())
        val syncReads = AtomicInteger(0)
        val repository = object : FakeMusicRepository() {
            override val allSongsFlow = songs
            override val albumOverridesFlow = overrides
            override val playlistsFlow = playlists

            override suspend fun getAllSongsSync(): List<Song> {
                syncReads.incrementAndGet()
                return songs.value
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = MediaLibraryBrowseProvider(repository, scope)
        try {
            assertEquals(
                listOf(MediaLibraryIds.song(1L)),
                provider.children(MediaLibraryIds.SONGS, page = 0, pageSize = 100)
                    .orEmpty()
                    .map { it.mediaId }
            )

            songs.value = songs.value + song(2L, "Second")
            val updatedIds = withTimeout(2_000L) {
                while (true) {
                    val ids = provider.children(MediaLibraryIds.SONGS, 0, 100)
                        .orEmpty()
                        .map { it.mediaId }
                    if (ids.size == 2) return@withTimeout ids
                    delay(10L)
                }
                error("unreachable")
            }

            assertEquals(
                listOf(MediaLibraryIds.song(1L), MediaLibraryIds.song(2L)),
                updatedIds
            )
            assertEquals(0, syncReads.get())
        } finally {
            scope.cancel()
        }
    }

    private fun song(id: Long, title: String): Song = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = "Artist",
        album = "Album"
    )
}
