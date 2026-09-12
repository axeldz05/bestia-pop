package com.bestiapop.android.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.ActiveDownload
import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.components.ArtworkThumbnail
import com.bestiapop.android.ui.components.CircleActionBox
import com.bestiapop.android.ui.components.DownloadStateTrailing
import com.bestiapop.android.ui.components.HeaderActionIcon
import com.bestiapop.android.ui.components.ItemSwipeBox
import com.bestiapop.android.ui.components.LocalSubmenuGestureSettings
import com.bestiapop.android.ui.components.MediaCardDownloadSpinner
import com.bestiapop.android.ui.components.PlayIconButton
import com.bestiapop.android.ui.components.TrackMetaRow
import com.bestiapop.android.ui.components.isAlbumDownloading
import com.bestiapop.android.ui.state.ItemLibraryStatus

/**
 * Contextual swipe actions for Discover screen items.
 */
@Immutable
data class DiscoverSwipeActions(
    val onSwipeTrack: ((TrackMeta) -> Unit)? = null,
    val onSwipeAlbum: ((CatalogAlbum) -> Unit)? = null,
    val onSwipeArtist: ((String) -> Unit)? = null
)

/**
 * Contextual single source of truth for Discover screen interactions:
 * - Swipe gestures (tracks, albums, artists)
 * - Library presence lookups (track and album)
 * - Active downloads tracking
 * - Status feedback toasts
 */
@Immutable
data class DiscoverContext(
    val swipeActions: DiscoverSwipeActions = DiscoverSwipeActions(),
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val getTrackStatus: (TrackMeta) -> ItemLibraryStatus = { ItemLibraryStatus.NOT_IN_LIBRARY },
    val getAlbumStatus: (String, String) -> ItemLibraryStatus = { _, _ -> ItemLibraryStatus.NOT_IN_LIBRARY },
    val onNotifyStatus: ((String) -> Unit)? = null
) {
    fun getAlbumStatus(album: CatalogAlbum): ItemLibraryStatus = getAlbumStatus(album.title, album.artist)
    fun withoutSwipeActions(): DiscoverContext = copy(swipeActions = DiscoverSwipeActions())
}

val LocalDiscoverContext = staticCompositionLocalOf { DiscoverContext() }

/** Level 1: Reusable icon representation of an item's library status. */
@Composable
fun ItemLibraryStatusIcon(
    status: ItemLibraryStatus,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "En la biblioteca",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(size)
            )
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            Icon(
                imageVector = Icons.Default.BookmarkAdded,
                contentDescription = "Guardada en biblioteca",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier.size(size)
            )
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> Unit
    }
}

@Composable
internal fun DiscoverActionIcon(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 20.dp,
    boxSize: Dp = 36.dp
) {
    HeaderActionIcon(
        onClick = onClick,
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
        iconSize = iconSize,
        boxSize = boxSize
    )
}

@Composable
internal fun DiscoverStatusActionIcon(
    onClick: () -> Unit,
    status: ItemLibraryStatus,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    boxSize: Dp = 36.dp
) {
    CircleActionBox(
        onClick = onClick,
        modifier = modifier,
        boxSize = boxSize
    ) {
        ItemLibraryStatusIcon(status = status, size = iconSize)
    }
}

/** Level 2: Track library action buttons (Downloaded, Saved Remote, Download, In-Flight progress). */
@Composable
fun TrackLibraryActionButtons(
    status: ItemLibraryStatus,
    onDownload: () -> Unit,
    activeDownload: ActiveDownload? = null,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    modifier: Modifier = Modifier
) {
    if (activeDownload != null) {
        DownloadStateTrailing(
            state = activeDownload.state,
            percent = activeDownload.progressPercent,
            onRetry = onDownload,
            onDownload = onDownload,
            successContent = {
                DiscoverStatusActionIcon(
                    onClick = onAlreadyInLibrary,
                    status = ItemLibraryStatus.DOWNLOADED,
                    iconSize = 20.dp,
                    boxSize = 36.dp,
                    modifier = modifier
                )
            }
        )
        return
    }
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            DiscoverStatusActionIcon(
                onClick = onAlreadyInLibrary,
                status = status,
                iconSize = 20.dp,
                boxSize = 36.dp,
                modifier = modifier
            )
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                DiscoverStatusActionIcon(
                    onClick = onAlreadyInLibrary,
                    status = status,
                    iconSize = 20.dp,
                    boxSize = 36.dp
                )
                DiscoverActionIcon(
                    onClick = onDownload,
                    icon = Icons.Default.Download,
                    contentDescription = "Descargar localmente",
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 20.dp,
                    boxSize = 36.dp
                )
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            DiscoverActionIcon(
                onClick = onDownload,
                icon = Icons.Default.Download,
                contentDescription = "Descargar",
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 20.dp,
                boxSize = 36.dp,
                modifier = modifier
            )
        }
    }
}

/** Level 2: Album save / saved icon button. */
@Composable
fun AlbumLibraryActionButton(
    status: ItemLibraryStatus,
    onSave: () -> Unit,
    onNotifyStatus: ((String) -> Unit)? = null,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    modifier: Modifier = Modifier
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED, ItemLibraryStatus.SAVED_REMOTE -> {
            DiscoverStatusActionIcon(
                onClick = onAlreadySaved,
                status = status,
                iconSize = 18.dp,
                boxSize = 32.dp,
                modifier = modifier
            )
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            DiscoverActionIcon(
                onClick = onSave,
                icon = Icons.Default.BookmarkAdd,
                contentDescription = "Guardar álbum",
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 18.dp,
                boxSize = 32.dp,
                modifier = modifier
            )
        }
    }
}

/** Level 2: Album save / status button for collection headers. */
@Composable
fun AlbumLibraryHeaderButton(
    status: ItemLibraryStatus,
    onSaveAlbum: () -> Unit,
    onAlreadyInLibrary: (String) -> Unit
) {
    when (status) {
        ItemLibraryStatus.DOWNLOADED -> {
            FilledTonalButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("En biblioteca")
            }
        }
        ItemLibraryStatus.SAVED_REMOTE -> {
            OutlinedButton(
                onClick = { onAlreadyInLibrary(status.albumMessage) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardado")
            }
        }
        ItemLibraryStatus.NOT_IN_LIBRARY -> {
            OutlinedButton(
                onClick = onSaveAlbum,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardar")
            }
        }
    }
}

/**
 * Level 1: Low-level primitive media card (Artwork + text + customizable overlay badges/actions).
 * Provides continuous granularity for all catalog/discover cards.
 */
@Composable
fun DiscoverMediaCard(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    imageSize: Dp? = null,
    aspectRatio: Float = 1f,
    onSwipeAction: (() -> Unit)? = null,
    swipeAction: SubmenuSwipeAction = LocalSubmenuGestureSettings.current.swipeLeftAction,
    topEndBadge: @Composable (BoxScope.() -> Unit)? = null,
    bottomEndAction: @Composable (BoxScope.() -> Unit)? = null
) {
    val cardModifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth()
    val cardContent = @Composable {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                val imageBoxModifier = if (imageSize != null) {
                    Modifier.size(imageSize)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                }
                Box(modifier = imageBoxModifier) {
                    ArtworkThumbnail(
                        artworkUri = artworkUri,
                        size = imageSize,
                        cornerRadius = 12.dp,
                        modifier = Modifier.fillMaxSize()
                    )

                    topEndBadge?.invoke(this)
                    bottomEndAction?.invoke(this)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (onSwipeAction != null && swipeAction != SubmenuSwipeAction.DISABLED) {
        ItemSwipeBox(
            action = swipeAction,
            onSwipeAction = onSwipeAction,
            shape = RoundedCornerShape(16.dp),
            modifier = modifier.then(cardModifier)
        ) {
            cardContent()
        }
    } else {
        Box(modifier = modifier.then(cardModifier)) {
            cardContent()
        }
    }
}

/** Level 1: Reusable top-end badge container for media cards. */
@Composable
fun BoxScope.MediaCardBadge(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(26.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Level 1: Reusable bottom-end circular action button for media cards. */
@Composable
fun BoxScope.MediaCardAction(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    size: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .size(size)
            .background(containerColor, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** Level 2: Track card with play overlay and library status badge. */
@Composable
fun DiscoverTrackCard(
    track: OnlineCatalogTrack,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    status: ItemLibraryStatus = LocalDiscoverContext.current.getTrackStatus(track.identity),
    onNotifyStatus: ((String) -> Unit)? = LocalDiscoverContext.current.onNotifyStatus,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedSwipeAction = onSwipeAction ?: LocalDiscoverContext.current.swipeActions.onSwipeTrack?.let { cb -> { cb(track) } }
    DiscoverMediaCard(
        title = track.title,
        subtitle = track.artist,
        artworkUri = track.artworkUri,
        cardWidth = 140.dp,
        imageSize = 124.dp,
        onClick = onPlay,
        onSwipeAction = resolvedSwipeAction,
        modifier = modifier,
        topEndBadge = {
            if (status.isPresent) {
                MediaCardBadge(onClick = onAlreadyInLibrary) {
                    ItemLibraryStatusIcon(status = status, size = 18.dp)
                }
            }
        },
        bottomEndAction = {
            MediaCardAction(
                onClick = onPlay,
                icon = Icons.Default.PlayArrow,
                contentDescription = "Reproducir"
            )
        }
    )
}

/** Level 1: Low-level primitive album card with customizable badges and actions. */
@Composable
fun DiscoverAlbumCard(
    title: String,
    artist: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    imageSize: Dp = 134.dp,
    onSwipeAction: (() -> Unit)? = null,
    topEndBadge: @Composable (BoxScope.() -> Unit)? = null,
    bottomEndAction: @Composable (BoxScope.() -> Unit)? = null
) {
    DiscoverMediaCard(
        title = title,
        subtitle = artist,
        artworkUri = coverUrl,
        cardWidth = cardWidth,
        imageSize = imageSize,
        onClick = onClick,
        onSwipeAction = onSwipeAction,
        modifier = modifier,
        topEndBadge = topEndBadge,
        bottomEndAction = bottomEndAction
    )
}

/** Level 2: Album card with save/status action button and download progress indicator. */
@Composable
fun DiscoverAlbumCard(
    album: CatalogAlbum,
    onClick: () -> Unit,
    onSave: () -> Unit,
    status: ItemLibraryStatus = LocalDiscoverContext.current.getAlbumStatus(album),
    activeDownloads: List<ActiveDownload>? = LocalDiscoverContext.current.activeDownloads,
    isDownloading: Boolean = activeDownloads?.isAlbumDownloading(album) ?: false,
    onNotifyStatus: ((String) -> Unit)? = LocalDiscoverContext.current.onNotifyStatus,
    onAlreadySaved: () -> Unit = { onNotifyStatus?.invoke(status.albumMessage) },
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedSwipeAction = onSwipeAction ?: LocalDiscoverContext.current.swipeActions.onSwipeAlbum?.let { cb -> { cb(album) } }
    DiscoverAlbumCard(
        title = album.title,
        artist = album.artist,
        coverUrl = album.coverUrl,
        onClick = onClick,
        onSwipeAction = resolvedSwipeAction,
        modifier = modifier,
        bottomEndAction = {
            if (isDownloading) {
                MediaCardDownloadSpinner()
            } else {
                AlbumLibraryActionButton(
                    status = status,
                    onSave = onSave,
                    onAlreadySaved = onAlreadySaved,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                )
            }
        }
    )
}

/** Level 2: Reusable Discover track list item using [TrackMetaRow], with continuous granularity slots. */
@Composable
fun DiscoverTrackListItem(
    track: TrackMeta,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    activeDownload: ActiveDownload? = null,
    status: ItemLibraryStatus = LocalDiscoverContext.current.getTrackStatus(track),
    highlighted: Boolean = false,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onNotifyStatus: ((String) -> Unit)? = LocalDiscoverContext.current.onNotifyStatus,
    onAlreadyInLibrary: () -> Unit = { onNotifyStatus?.invoke(status.trackMessage) },
    onSwipeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedSwipeAction = onSwipeAction ?: LocalDiscoverContext.current.swipeActions.onSwipeTrack?.let { cb -> { cb(track) } }
    val rowContent = @Composable {
        TrackMetaRow(
            artworkUri = track.artworkUri,
            title = track.title,
            subtitle = subtitle ?: track.artist,
            highlighted = highlighted,
            leading = leading,
            onClick = onPlay,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            trailing = trailing ?: {
                TrackLibraryActionButtons(
                    status = status,
                    onDownload = onDownload,
                    activeDownload = activeDownload,
                    onNotifyStatus = onNotifyStatus,
                    onAlreadyInLibrary = onAlreadyInLibrary
                )
            }
        )
    }

    if (resolvedSwipeAction != null) {
        ItemSwipeBox(
            onSwipeAction = resolvedSwipeAction,
            shape = RoundedCornerShape(12.dp),
            modifier = modifier
        ) {
            rowContent()
        }
    } else {
        Box(modifier = modifier) {
            rowContent()
        }
    }
}

/** Level 1: Low-level section header with title and optional badge / action trailing. */
@Composable
fun DiscoverSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        if (badgeText != null) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
        } else if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Level 2: Shared horizontal carousel wrapper that suppresses swipe-left gestures
 * on child items to avoid gesture conflicts with horizontal carousel scrolling.
 */
@Composable
fun DiscoverCarouselRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyListScope.() -> Unit
) {
    CompositionLocalProvider(
        LocalDiscoverContext provides LocalDiscoverContext.current.withoutSwipeActions()
    ) {
        LazyRow(
            modifier = modifier,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            content = content
        )
    }
}

/** Level 2: Shared horizontal section (header + spaced carousel) for Discover feed carousels. */
@Composable
fun DiscoverFeedHorizontalSection(
    title: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DiscoverSectionHeader(
            title = title,
            badgeText = badgeText,
            badgeColor = badgeColor,
            trailing = trailing
        )
        DiscoverCarouselRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
