package com.bestiapop.android.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision

/** When false, list thumbs only render if already in Coil's memory cache (no decode on fling). */
val LocalAllowArtworkDecode = compositionLocalOf { true }

internal fun artworkMemoryCacheKey(uri: String, sizePx: Int?): String =
    if (sizePx != null) "$uri@$sizePx" else uri

@Composable
fun rememberArtworkRequest(uri: String?, sizePx: Int? = null): ImageRequest? {
    val context = LocalContext.current
    return remember(uri, sizePx) {
        if (uri.isNullOrEmpty()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(uri)
                .apply {
                    if (sizePx != null) {
                        size(sizePx)
                        precision(Precision.INEXACT)
                    }
                }
                .crossfade(false)
                .memoryCacheKey(artworkMemoryCacheKey(uri, sizePx))
                .diskCacheKey(artworkMemoryCacheKey(uri, sizePx))
                .build()
        }
    }
}

@Composable
fun ArtworkThumbnail(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 48.dp,
    cornerRadius: Dp = 8.dp,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    contentDescription: String? = "Artwork"
) {
    val context = LocalContext.current
    val sizePx = size?.let { with(LocalDensity.current) { it.roundToPx().coerceAtLeast(1) } }
    val imageRequest = rememberArtworkRequest(artworkUri, sizePx)
    val fallbackSize = size ?: 48.dp
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val placeholder = remember(placeholderColor) { ColorPainter(placeholderColor) }
    val allowDecode = LocalAllowArtworkDecode.current
    val canDecode = allowDecode || artworkCachedInMemory(context, artworkUri, sizePx)

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null && canDecode) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = placeholder
            )
        } else if (imageRequest == null) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(fallbackSize * 0.5f)
            )
        }
    }
}

private fun artworkCachedInMemory(
    context: Context,
    uri: String?,
    sizePx: Int?
): Boolean {
    if (uri.isNullOrEmpty()) return false
    val key = MemoryCache.Key(artworkMemoryCacheKey(uri, sizePx))
    return context.imageLoader.memoryCache?.get(key) != null
}

@Composable
fun ArtworkHero(
    uri: String?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.MusicNote,
    contentDescription: String? = null,
    cornerRadius: Dp = 24.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    fallbackTint: Color? = null,
    targetSizePx: Int = 600
) {
    val imageRequest = rememberArtworkRequest(uri, targetSizePx)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = contentDescription,
                tint = fallbackTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}
