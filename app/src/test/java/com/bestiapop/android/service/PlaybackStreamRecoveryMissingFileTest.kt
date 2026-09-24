package com.bestiapop.android.service

import androidx.media3.common.Player
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStreamRecoveryMissingFileTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun testSong(
        id: Long,
        title: String,
        uriString: String = "/path/$title.mp3",
    ): Song =
        Song(
            id = id,
            title = title,
            artist = "Artist",
            album = "Album",
            genre = "Rock",
            durationMs = 180_000L,
            artworkUri = null,
            uriString = uriString,
            folderPath = "/path",
            trackNumber = 1,
            year = 2024,
            dateAdded = 1000L,
        )

    private class TestController(
        initialQueue: List<PlayableItem>,
    ) : PlaybackControllerFacade {
        val items = initialQueue.toMutableList()
        var currentIndex = 0
        var isPlayingVal = false
        var playWhenReadyVal = true
        var playbackStateVal = Player.STATE_IDLE
        var lastSeekIndex = -1
        var lastSeekPos = -1L

        override val mediaItemCount: Int get() = items.size
        override val currentMediaItemIndex: Int get() = currentIndex
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 180_000L
        override val isPlaying: Boolean get() = isPlayingVal
        override val playWhenReady: Boolean get() = playWhenReadyVal
        override val playbackState: Int get() = playbackStateVal
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override var shuffleModeEnabled: Boolean = false

        override fun addListener(listener: PlaybackControllerFacade.Listener) = Unit

        override fun items(): List<PlayableItem> = items.toList()

        override fun setMediaItems(
            items: List<PlayableItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) {
            this.items.clear()
            this.items.addAll(items)
            this.currentIndex = startIndex
        }

        override fun replaceMediaItem(
            index: Int,
            item: PlayableItem,
        ) {
            if (index in items.indices) {
                items[index] = item
            }
        }

        override fun addMediaItems(items: List<PlayableItem>) {
            this.items.addAll(items)
        }

        override fun addMediaItems(
            index: Int,
            items: List<PlayableItem>,
        ) {
            this.items.addAll(index.coerceIn(0, this.items.size), items)
        }

        override fun removeMediaItem(index: Int) {
            if (index in this.items.indices) this.items.removeAt(index)
        }

        override fun removeMediaItems(
            fromIndex: Int,
            toIndex: Int,
        ) {
            val validFrom = fromIndex.coerceIn(0, this.items.size)
            val validTo = toIndex.coerceIn(validFrom, this.items.size)
            this.items.subList(validFrom, validTo).clear()
        }

        override fun moveMediaItem(
            fromIndex: Int,
            toIndex: Int,
        ) {
            if (fromIndex in this.items.indices && toIndex in this.items.indices) {
                val item = this.items.removeAt(fromIndex)
                this.items.add(toIndex, item)
            }
        }

        override fun seekTo(positionMs: Long) {
            lastSeekPos = positionMs
        }

        override fun seekTo(
            index: Int,
            positionMs: Long,
        ) {
            lastSeekIndex = index
            lastSeekPos = positionMs
            currentIndex = index
        }

        override fun seekToNextMediaItem() {
            if (hasNextMediaItem()) currentIndex++
        }

        override fun seekToPreviousMediaItem() {
            if (hasPreviousMediaItem()) currentIndex--
        }

        override fun hasNextMediaItem(): Boolean = currentIndex < items.size - 1

        override fun hasPreviousMediaItem(): Boolean = currentIndex > 0

        override fun play() {
            isPlayingVal = true
        }

        override fun pause() {
            isPlayingVal = false
        }

        override fun prepare() {
            playbackStateVal = Player.STATE_READY
        }

        override fun release() = Unit
    }

    @Test
    fun handlePlayerError_whenLocalMissingAndOffline_deletesSongAndEmitsMissingAlert() =
        testScope.runTest {
            val song1 = testSong(1L, "Song 1")
            val song2 = testSong(2L, "Song 2")
            val local1 = PlayableItem.Local(song1, queueEntryId = "entry-1")
            val local2 = PlayableItem.Local(song2, queueEntryId = "entry-2")

            var queue = listOf<PlayableItem>(local1, local2)
            val controller = TestController(queue)
            val deletedSongs = mutableListOf<Song>()
            val emittedEvents = mutableListOf<String>()

            val streamAccess =
                object : PlaybackRuntimeStreamAccess {
                    override fun needsResolve(item: PlayableItem.Remote): Boolean = false

                    override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote? = item

                    override suspend fun invalidate(item: PlayableItem.Remote) = Unit
                }

            val dependencies =
                PlaybackRuntimeDependencies(
                    scope = this,
                    streamAccess = streamAccess,
                    isOnline = { false },
                    hasPhysicalFile = { song -> song.id != 1L },
                    deleteMissingLocalSong = { deletedSongs.add(it) },
                    ioDispatcher = testDispatcher,
                )

            var currentItem: PlayableItem? = local1

            val coordinator =
                PlaybackStreamRecoveryCoordinator(
                    scope = this,
                    dependencies = dependencies,
                    getQueue = { queue },
                    onUpdateQueue = { queue = it },
                    getCurrentItem = { currentItem },
                    onSetCurrentItem = { item, _, _ -> currentItem = item },
                    getPlaybackPositionMs = { 0L },
                    onSetPlaybackPositionMs = {},
                    onSetIsPlaying = { controller.isPlayingVal = it },
                    isPlayWhenReadyIntent = { true },
                    onSetPlayWhenReadyIntent = {},
                    getController = { controller },
                    setLastMediaItemIndex = { controller.currentIndex = it },
                    onEmitEvent = { emittedEvents.add(it) },
                    onCancelPendingPlayIntent = {},
                    ensurePreparedForPlayback = { controller.prepare() },
                    getPlaybackGeneration = { 1L },
                )

            coordinator.handlePlayerError()

            assertEquals(listOf(song1), deletedSongs)
            assertTrue(emittedEvents.any { it.contains("No se encontró «Song 1» en el dispositivo") })
        }

    @Test
    fun handlePlayerError_whenLocalMissingAndOnline_fallsBackToStreamingAndDeletesSong() =
        testScope.runTest {
            val song1 = testSong(1L, "Online Song")
            val local1 = PlayableItem.Local(song1, queueEntryId = "entry-1")

            var queue = listOf<PlayableItem>(local1)
            val controller = TestController(queue)
            val deletedSongs = mutableListOf<Song>()
            val emittedEvents = mutableListOf<String>()

            val streamAccess =
                object : PlaybackRuntimeStreamAccess {
                    override fun needsResolve(item: PlayableItem.Remote): Boolean = item.resolved == null

                    override suspend fun resolve(item: PlayableItem.Remote): PlayableItem.Remote =
                        item.copy(
                            resolved =
                                ResolvedStream(
                                    audioUrl = "https://stream.example.com/audio.opus",
                                    userAgent = "BestiaPop",
                                    videoId = "testVideoId",
                                    resolvedAtEpochMs = 1000L,
                                ),
                        )

                    override suspend fun invalidate(item: PlayableItem.Remote) = Unit
                }

            val dependencies =
                PlaybackRuntimeDependencies(
                    scope = this,
                    streamAccess = streamAccess,
                    isOnline = { true },
                    hasPhysicalFile = { false },
                    deleteMissingLocalSong = { deletedSongs.add(it) },
                    ioDispatcher = testDispatcher,
                )

            var currentItem: PlayableItem? = local1

            val coordinator =
                PlaybackStreamRecoveryCoordinator(
                    scope = this,
                    dependencies = dependencies,
                    getQueue = { queue },
                    onUpdateQueue = { queue = it },
                    getCurrentItem = { currentItem },
                    onSetCurrentItem = { item, _, _ -> currentItem = item },
                    getPlaybackPositionMs = { 0L },
                    onSetPlaybackPositionMs = {},
                    onSetIsPlaying = { controller.isPlayingVal = it },
                    isPlayWhenReadyIntent = { true },
                    onSetPlayWhenReadyIntent = {},
                    getController = { controller },
                    setLastMediaItemIndex = { controller.currentIndex = it },
                    onEmitEvent = { emittedEvents.add(it) },
                    onCancelPendingPlayIntent = {},
                    ensurePreparedForPlayback = { controller.prepare() },
                    getPlaybackGeneration = { 1L },
                )

            coordinator.handlePlayerError()

            assertEquals(listOf(song1), deletedSongs)
            assertTrue(emittedEvents.any { it.contains("Buscando en streaming…") })
            val replaced = queue.first()
            assertTrue(replaced is PlayableItem.Remote)
            assertEquals("https://stream.example.com/audio.opus", (replaced as PlayableItem.Remote).resolved?.audioUrl)
            assertTrue(controller.isPlayingVal)
        }
}
