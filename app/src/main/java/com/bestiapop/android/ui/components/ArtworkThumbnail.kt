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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import coil.request.ImageRequest
import coil.size.Precision

import coil.imageLoader
import coil.memory.MemoryCache
import java.util.Collections
import java.util.LinkedHashMap

private const val MAX_UNRESOLVABLE_URIS = 500

/** Thread-safe bounded set of URIs that failed to load (e.g. FileNotFoundException in MediaStore). */
private val unresolvableArtworkUris: MutableSet<String> = Collections.synchronizedSet(
    Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(MAX_UNRESOLVABLE_URIS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > MAX_UNRESOLVABLE_URIS
        }
    )
)

internal fun artworkMemoryCacheKey(uri: String, sizePx: Int?): String =
    if (sizePx != null) "$uri@$sizePx" else uri

fun isArtworkCachedInMemory(context: Context, uri: String?, sizePx: Int? = null): Boolean {
    if (uri.isNullOrEmpty()) return false
    val key = artworkMemoryCacheKey(uri, sizePx)
    return context.imageLoader.memoryCache?.get(MemoryCache.Key(key)) != null
}

internal fun buildArtworkImageRequest(context: Context, uri: String, sizePx: Int?): ImageRequest {
    return ImageRequest.Builder(context)
        .data(uri)
        .apply {
            if (sizePx != null) {
                size(sizePx)
                precision(Precision.INEXACT)
            }
        }
        .crossfade(false)
        .memoryCacheKey(artworkMemoryCacheKey(uri, sizePx))
        .diskCacheKey(uri)
        .build()
}

fun preloadArtwork(context: Context, uri: String?, sizePx: Int? = null) {
    if (uri.isNullOrEmpty() || unresolvableArtworkUris.contains(uri)) return
    if (isArtworkCachedInMemory(context, uri, sizePx)) return
    val request = buildArtworkImageRequest(context, uri, sizePx)
    context.imageLoader.enqueue(request)
}

suspend fun preloadArtworkSuspend(context: Context, uri: String?, sizePx: Int? = null): Boolean {
    if (uri.isNullOrEmpty() || unresolvableArtworkUris.contains(uri)) return false
    if (isArtworkCachedInMemory(context, uri, sizePx)) return false
    val request = buildArtworkImageRequest(context, uri, sizePx)
    val result = context.imageLoader.execute(request)
    if (result is coil.request.ErrorResult) {
        unresolvableArtworkUris.add(uri)
        return false
    }
    return true
}

@Composable
fun rememberArtworkRequest(uri: String?, sizePx: Int? = null): ImageRequest? {
    val context = LocalContext.current
    return remember(uri, sizePx) {
        if (uri.isNullOrEmpty() || unresolvableArtworkUris.contains(uri)) {
            null
        } else {
            buildArtworkImageRequest(context, uri, sizePx)
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
    contentDescription: String? = "Artwork",
    isFastScroll: Boolean = false,
    allowIntermittent: Boolean = true
) {
    val sizePx = size?.let { with(LocalDensity.current) { it.roundToPx().coerceAtLeast(1) } }
    val imageRequest = rememberArtworkRequest(artworkUri, sizePx)
    val fallbackSize = size ?: 48.dp
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    if (artworkUri != null) {
                        unresolvableArtworkUris.add(artworkUri)
                    }
                }
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(fallbackSize * 0.5f)
            )
        }
    }
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
