package com.bestiapop.android.service.library

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.service.MusicService
import com.bestiapop.android.service.PlaybackMediaItemCodec
import com.bestiapop.android.service.playbackResumptionMetadataItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val UNTRUSTED_TRANSPORT_PLAYER_COMMANDS = setOf(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_PREPARE,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT
)

@OptIn(UnstableApi::class)
internal fun untrustedTransportPlayerCommands(): Player.Commands =
    MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS
        .buildUpon()
        .apply { UNTRUSTED_TRANSPORT_PLAYER_COMMANDS.forEach(::add) }
        .build()

@OptIn(UnstableApi::class)
internal class BestiaPopMediaLibraryCallback(
    private val scope: CoroutineScope,
    private val application: BestiaPopApplication,
    private val audioStore: MusicFileStore,
    private val browseProvider: MediaLibraryBrowseProvider,
    private val publishShuffleExtras: () -> Unit,
    private val applyShuffleOrder: (IntArray?) -> Unit
) : MediaLibrarySession.Callback {

    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.ConnectionResult> {
        publishShuffleExtras()
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        if (controller.isTrusted) {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(SessionCommand(MusicService.ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY))
                    .build()
            builder.setAvailableSessionCommands(sessionCommands)
        } else {
            builder.setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            )
            builder.setAvailablePlayerCommands(untrustedTransportPlayerCommands())
        }
        return Futures.immediateFuture(builder.build())
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!browser.isTrusted) return permissionDenied()
        return serviceFuture { LibraryResult.ofItem(browseProvider.root(), params) }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!browser.isTrusted) return permissionDenied()
        return serviceFuture {
            val item = browseProvider.item(mediaId)
            if (item != null) {
                LibraryResult.ofItem(item, null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!browser.isTrusted) return permissionDenied()
        return serviceFuture {
            val children = browseProvider.children(parentId, page, pageSize)
            if (children != null) {
                LibraryResult.ofItemList(children, params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        if (!browser.isTrusted) return permissionDenied()
        return serviceFuture {
            val count = browseProvider.searchCount(query)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!browser.isTrusted) return permissionDenied()
        return serviceFuture {
            LibraryResult.ofItemList(browseProvider.search(query, page, pageSize), params)
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val hasLibraryItem = mediaItems.any { MediaLibraryIds.parse(it.mediaId) != null }
        val searchQuery = mediaItems.singleOrNull()
            ?.requestMetadata
            ?.searchQuery
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (!hasLibraryItem && searchQuery == null) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }
        if (!controller.isTrusted) {
            return Futures.immediateFailedFuture(
                SecurityException("Untrusted controller cannot play library items")
            )
        }
        return serviceFuture {
            val selection = if (hasLibraryItem) {
                browseProvider.resolvePlayback(
                    requested = mediaItems,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs
                )
            } else {
                browseProvider.resolveSearchPlayback(
                    query = checkNotNull(searchQuery),
                    startPositionMs = startPositionMs
                )
            } ?: throw IllegalArgumentException("Unknown BestiaPop media item")
            val staged = application.playbackRuntime.stageExternalPlayableCollection(
                items = selection.songs.map(Song::toPlayable),
                startIndex = selection.startIndex,
                startPositionMs = selection.startPositionMs
            ) ?: throw IllegalArgumentException("Empty BestiaPop playback selection")
            MediaSession.MediaItemsWithStartPosition(
                staged.items.map(::encodePlayable),
                staged.currentIndex,
                staged.positionMs
            )
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (!controller.isTrusted ||
            customCommand.customAction != MusicService.ACTION_SET_SHUFFLE_ORDER
        ) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        val indices = args.getIntArray(MusicService.EXTRA_SHUFFLE_ORDER)
            ?: customCommand.customExtras.getIntArray(MusicService.EXTRA_SHUFFLE_ORDER)
        applyShuffleOrder(indices)
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceFuture {
        val snapshot = if (isForPlayback) {
            application.playbackRuntime.restoreSystemPlaybackSnapshot()
        } else {
            application.playbackRuntime.systemResumptionMetadataSnapshot()
        } ?: throw UnsupportedOperationException("No playback session to resume")
        val mediaItems = if (isForPlayback) {
            snapshot.items.map(::encodePlayable)
        } else {
            listOf(
                playbackResumptionMetadataItem(
                    item = snapshot.currentItem,
                    positionMs = snapshot.positionMs
                )
            )
        }
        MediaSession.MediaItemsWithStartPosition(
            mediaItems,
            if (isForPlayback) snapshot.currentIndex else 0,
            snapshot.positionMs
        )
    }

    private fun encodePlayable(item: PlayableItem): MediaItem =
        PlaybackMediaItemCodec.encode(item, ::playableUri)

    private fun playableUri(song: Song) =
        audioStore.playableUri(song.uriString, song.folderPath)

    private fun <T> serviceFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (error: Throwable) {
                future.setException(error)
            }
        }
        return future
    }

    private fun <T : Any> permissionDenied(): ListenableFuture<LibraryResult<T>> =
        Futures.immediateFuture(
            LibraryResult.ofError<T>(SessionError.ERROR_PERMISSION_DENIED)
        )
}
