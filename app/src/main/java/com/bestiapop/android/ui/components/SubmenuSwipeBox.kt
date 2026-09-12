package com.bestiapop.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.SubmenuGestureSettings
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SWIPE_THRESHOLD = 72.dp
private val MAX_DRAG_OFFSET = 120.dp

/**
 * Level 2 compressed wrapper: binds directly to [SubmenuGestureSettings],
 * automatically managing enabled flags based on gesture settings and caller prerequisites.
 */
@Composable
fun SubmenuSwipeBox(
    settings: SubmenuGestureSettings,
    onSwipeRight: (() -> Unit)?,
    modifier: Modifier = Modifier,
    canSwipeBack: Boolean = true,
    content: @Composable () -> Unit
) = SubmenuSwipeBox(
    onSwipeRight = onSwipeRight,
    onSwipeLeft = null,
    modifier = modifier,
    swipeRightEnabled = canSwipeBack && settings.swipeBackEnabled,
    swipeLeftEnabled = false,
    content = content
)

/**
 * Level 2 compressed wrapper: binds directly to [SubmenuGestureSettings],
 * automatically managing enabled flags based on gesture settings and caller prerequisites.
 */
@Composable
fun SubmenuSwipeBox(
    settings: SubmenuGestureSettings,
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    modifier: Modifier = Modifier,
    canSwipeBack: Boolean = true,
    canExecuteAction: Boolean = false,
    content: @Composable () -> Unit
) = SubmenuSwipeBox(
    onSwipeRight = onSwipeRight,
    onSwipeLeft = onSwipeLeft,
    modifier = modifier,
    swipeRightEnabled = canSwipeBack && settings.swipeBackEnabled,
    swipeLeftEnabled = canExecuteAction && settings.swipeLeftAction != SubmenuSwipeAction.DISABLED,
    swipeLeftAction = settings.swipeLeftAction,
    content = content
)

/**
 * Level 1 primitive: exposes direct, granular control over individual swipe directions,
 * explicit enable flags, and custom actions.
 */
@Composable
fun SubmenuSwipeBox(
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    modifier: Modifier = Modifier,
    swipeRightEnabled: Boolean = true,
    swipeLeftEnabled: Boolean = true,
    swipeLeftAction: SubmenuSwipeAction = SubmenuSwipeAction.ENQUEUE_ALL,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val maxDragPx = with(density) { MAX_DRAG_OFFSET.toPx() }

    val offsetX = remember { Animatable(0f) }
    var hasTickedThreshold by remember { mutableStateOf(false) }

    val canSwipeRight = swipeRightEnabled && onSwipeRight != null
    val canSwipeLeft = swipeLeftEnabled && onSwipeLeft != null && swipeLeftAction != SubmenuSwipeAction.DISABLED

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(canSwipeRight, canSwipeLeft) {
                if (!canSwipeRight && !canSwipeLeft) return@pointerInput

                detectHorizontalDragGestures(
                    onDragStart = {
                        hasTickedThreshold = false
                    },
                    onDragEnd = {
                        val currentOffset = offsetX.value
                        coroutineScope.launch {
                            if (currentOffset >= thresholdPx && canSwipeRight) {
                                onSwipeRight?.invoke()
                            } else if (currentOffset <= -thresholdPx && canSwipeLeft) {
                                onSwipeLeft?.invoke()
                            }
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val newOffset = (offsetX.value + dragAmount).let { raw ->
                            val bounded = when {
                                raw > 0 && !canSwipeRight -> 0f
                                raw < 0 && !canSwipeLeft -> 0f
                                else -> raw
                            }
                            bounded.coerceIn(-maxDragPx, maxDragPx)
                        }
                        coroutineScope.launch {
                            offsetX.snapTo(newOffset)
                        }

                        val reachedThreshold = (newOffset >= thresholdPx && canSwipeRight) ||
                            (newOffset <= -thresholdPx && canSwipeLeft)

                        if (reachedThreshold && !hasTickedThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasTickedThreshold = true
                        } else if (!reachedThreshold && hasTickedThreshold) {
                            hasTickedThreshold = false
                        }
                    }
                )
            }
    ) {
        val currentOffset = offsetX.value

        // Left indicator (Swipe Right: Volver)
        if (canSwipeRight && currentOffset > 10f) {
            val progress = (currentOffset / thresholdPx).coerceIn(0f, 1f)
            val reached = currentOffset >= thresholdPx
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = 0.8f + (0.2f * progress)
                        scaleY = 0.8f + (0.2f * progress)
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (reached) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    },
                    contentColor = if (reached) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Volver",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // Right indicator (Swipe Left: Custom Action)
        if (canSwipeLeft && currentOffset < -10f) {
            val progress = (-currentOffset / thresholdPx).coerceIn(0f, 1f)
            val reached = -currentOffset >= thresholdPx
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = 0.8f + (0.2f * progress)
                        scaleY = 0.8f + (0.2f * progress)
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (reached) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    },
                    contentColor = if (reached) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = swipeLeftAction.label(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = swipeLeftAction.icon,
                            contentDescription = swipeLeftAction.label(),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Main content translated horizontally
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
        ) {
            content()
        }
    }
}

val SubmenuSwipeAction.icon: ImageVector
    get() = when (this) {
        SubmenuSwipeAction.ENQUEUE_ALL -> Icons.AutoMirrored.Filled.QueueMusic
        SubmenuSwipeAction.PLAY_NEXT -> Icons.Default.SkipNext
        SubmenuSwipeAction.START_RADIO -> Icons.Default.Radio
        SubmenuSwipeAction.SEARCH_SIMILAR -> Icons.Default.Explore
        SubmenuSwipeAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistAdd
        SubmenuSwipeAction.DISABLED -> Icons.AutoMirrored.Filled.QueueMusic
    }

fun actionIconFor(action: SubmenuSwipeAction): ImageVector = action.icon
