package com.bestiapop.android.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.MusicFileStore
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.atomic.AtomicBoolean

internal interface PlaybackControllerFacade {
    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        fun onPlayWhenReadyChanged(playWhenReady: Boolean) = Unit
        fun onPlayerError() = Unit
        fun onPlaybackStateChanged(playbackState: Int) = Unit
        fun onMediaItemTransition(item: PlayableItem?, reason: Int) = Unit
        fun onTimelineChanged() = Unit
        fun onPositionDiscontinuity(positionMs: Long) = Unit
        fun onRepeatModeChanged(repeatMode: Int) = Unit
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = Unit
        fun onDisconnected(controller: PlaybackControllerFacade) = Unit
    }

    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playbackState: Int
    val hasPlayerError: Boolean get() = false
    var repeatMode: Int
    var shuffleModeEnabled: Boolean

    fun addListener(listener: Listener)
    fun items(): List<PlayableItem>
    fun setMediaItems(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long)
    fun replaceMediaItem(index: Int, item: PlayableItem)
    fun addMediaItems(items: List<PlayableItem>)
    fun addMediaItems(index: Int, items: List<PlayableItem>)
    fun removeMediaItem(index: Int)
    fun removeMediaItems(fromIndex: Int, toIndex: Int)
    fun clearMediaItems() {
        if (mediaItemCount > 0) removeMediaItems(0, mediaItemCount)
    }
    fun moveMediaItem(fromIndex: Int, toIndex: Int)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(index: Int, positionMs: Long)
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun release()
}

internal interface PlaybackControllerConnection {
    fun addListener(listener: () -> Unit)
    fun get(): PlaybackControllerFacade
    fun cancel()
}

internal fun interface PlaybackControllerConnector {
    fun connect(): PlaybackControllerConnection
}

@OptIn(UnstableApi::class)
internal class MediaControllerConnection(
    context: Context,
    private val library: () -> List<Song>
) : PlaybackControllerConnection {
    private val disconnectionRelay = MediaControllerDisconnectionRelay()
    private val audioStore = MusicFileStore(context)
    private val future: ListenableFuture<MediaController>
    private var facade: PlaybackControllerFacade? = null

    init {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        future = MediaController.Builder(context, token)
            .setListener(disconnectionRelay)
            .buildAsync()
    }

    override fun addListener(listener: () -> Unit) {
        future.addListener(listener, MoreExecutors.directExecutor())
    }

    override fun get(): PlaybackControllerFacade =
        facade ?: MediaControllerFacade(
            controller = future.get(),
            audioStore = audioStore,
            library = library,
            disconnectionRelay = disconnectionRelay
        ).also { facade = it }

    override fun cancel() {
        future.cancel(true)
    }
}

internal class MediaControllerDisconnectionRelay : MediaController.Listener {
    @Volatile
    private var callback: (() -> Unit)? = null

    fun attach(callback: () -> Unit) {
        this.callback = callback
    }

    fun clear() {
        callback = null
    }

    override fun onDisconnected(controller: MediaController) {
        callback?.invoke()
    }
}

@OptIn(UnstableApi::class)
internal class MediaControllerFacade(
    private val controller: MediaController,
    private val audioStore: MusicFileStore,
    private val library: () -> List<Song>,
    private val disconnectionRelay: MediaControllerDisconnectionRelay
) : PlaybackControllerFacade {
    private val released = AtomicBoolean(false)
    private var runtimeListener: PlaybackControllerFacade.Listener? = null
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runtimeListener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            runtimeListener?.onPlayWhenReadyChanged(playWhenReady)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            runtimeListener?.onPlayerError()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runtimeListener?.onPlaybackStateChanged(playbackState)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runtimeListener?.onMediaItemTransition(
                mediaItem?.let { PlaybackMediaItemCodec.decode(it, library()) },
                reason
            )
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            runtimeListener?.onTimelineChanged()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            runtimeListener?.onPositionDiscontinuity(newPosition.positionMs)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            runtimeListener?.onRepeatModeChanged(repeatMode)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            runtimeListener?.onShuffleModeEnabledChanged(shuffleModeEnabled)
        }
    }

    override val mediaItemCount: Int get() = controller.mediaItemCount
    override val currentMediaItemIndex: Int get() = controller.currentMediaItemIndex
    override val currentPosition: Long get() = controller.currentPosition
    override val duration: Long get() = controller.duration
    override val isPlaying: Boolean get() = controller.isPlaying
    override val playWhenReady: Boolean get() = controller.playWhenReady
    override val playbackState: Int get() = controller.playbackState
    override val hasPlayerError: Boolean get() = controller.playerError != null
    override var repeatMode: Int
        get() = controller.repeatMode
        set(value) {
            controller.repeatMode = value
        }
    override var shuffleModeEnabled: Boolean
        get() = controller.shuffleModeEnabled
        set(value) {
            controller.shuffleModeEnabled = value
            val args = Bundle().apply {
                putIntArray(
                    MusicService.EXTRA_SHUFFLE_ORDER,
                    if (value) IntArray(controller.mediaItemCount) { it } else IntArray(0)
                )
            }
            controller.sendCustomCommand(
                SessionCommand(MusicService.ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY),
                args
            )
        }

    override fun addListener(listener: PlaybackControllerFacade.Listener) {
        runtimeListener = listener
        disconnectionRelay.attach { listener.onDisconnected(this) }
        controller.addListener(playerListener)
    }

    override fun items(): List<PlayableItem> {
        val lib = library()
        val byId = HashMap<Long, Song>(lib.size * 2)
        val byUri = HashMap<String, Song>(lib.size * 2)
        for (song in lib) {
            if (song.id > 0L) byId.putIfAbsent(song.id, song)
            if (song.uriString.isNotBlank()) byUri.putIfAbsent(song.uriString, song)
        }
        val lookup: (Long?, String) -> Song? = { id, uri ->
            (if (id != null && id > 0L) byId[id] else null) ?: byUri[uri]
        }
        return buildList {
            for (index in 0 until controller.mediaItemCount) {
                PlaybackMediaItemCodec.decode(controller.getMediaItemAt(index), lib, lookup)?.let(::add)
            }
        }
    }

    override fun setMediaItems(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val encoded = ArrayList<MediaItem>(items.size)
        for (i in items.indices) {
            encoded.add(encode(items[i]))
        }
        controller.setMediaItems(encoded, startIndex, startPositionMs)
    }

    override fun replaceMediaItem(index: Int, item: PlayableItem) {
        controller.replaceMediaItem(index, encode(item))
    }

    override fun addMediaItems(items: List<PlayableItem>) {
        val encoded = ArrayList<MediaItem>(items.size)
        for (i in items.indices) {
            encoded.add(encode(items[i]))
        }
        controller.addMediaItems(encoded)
    }

    override fun addMediaItems(index: Int, items: List<PlayableItem>) {
        val encoded = ArrayList<MediaItem>(items.size)
        for (i in items.indices) {
            encoded.add(encode(items[i]))
        }
        controller.addMediaItems(index, encoded)
    }

    override fun removeMediaItem(index: Int) = controller.removeMediaItem(index)
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) =
        controller.removeMediaItems(fromIndex, toIndex)
    override fun clearMediaItems() = controller.clearMediaItems()

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) =
        controller.moveMediaItem(fromIndex, toIndex)

    override fun prepare() = controller.prepare()
    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun seekTo(index: Int, positionMs: Long) = controller.seekTo(index, positionMs)
    override fun seekToNextMediaItem() = controller.seekToNextMediaItem()
    override fun seekToPreviousMediaItem() = controller.seekToPreviousMediaItem()
    override fun hasNextMediaItem(): Boolean = controller.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = controller.hasPreviousMediaItem()
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        disconnectionRelay.clear()
        runtimeListener = null
        controller.removeListener(playerListener)
        controller.release()
    }

    private fun encode(item: PlayableItem): MediaItem =
        PlaybackMediaItemCodec.encode(item) { song ->
            audioStore.playableUri(song.uriString)
        }
}
