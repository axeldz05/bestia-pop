package com.bestiapop.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.SubmenuSwipeAction
import com.bestiapop.android.ui.theme.ListDensity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DEFAULT_SWIPE_THRESHOLD = 64.dp
private val DEFAULT_MAX_DRAG = 110.dp

/**
 * Short label for compact item swipe indicator.
 */
fun SubmenuSwipeAction.shortLabel(): String = when (this) {
    SubmenuSwipeAction.ENQUEUE_ALL -> "Encolar"
    SubmenuSwipeAction.PLAY_NEXT -> "Siguiente"
    SubmenuSwipeAction.START_RADIO -> "Radio"
    SubmenuSwipeAction.SEARCH_SIMILAR -> "Buscar"
    SubmenuSwipeAction.ADD_TO_PLAYLIST -> "Playlist"
    SubmenuSwipeAction.DISABLED -> ""
}

/**
 * Level 2: High-level compressed wrapper binding directly to [SubmenuSwipeAction].
 */
@Composable
fun ItemSwipeBox(
    action: SubmenuSwipeAction,
    onSwipeAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(ListDensity.corner),
    contentBackgroundColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    val isEnabled = enabled && action != SubmenuSwipeAction.DISABLED
    ItemSwipeBox(
        onSwipeLeft = if (isEnabled) onSwipeAction else null,
        leftIcon = if (isEnabled) action.icon else null,
        leftLabel = if (isEnabled) action.shortLabel() else null,
        modifier = modifier,
        enabled = isEnabled,
        shape = shape,
        contentBackgroundColor = contentBackgroundColor,
        content = content
    )
}

/**
 * Level 1: Low-level gesture box allowing independent left and right swipe actions.
 * Updates in layout/draw phase without recomposition during drag.
 * Directional touch-slop filtering ensures non-handled directions pass through cleanly to parent containers.
 */
@Composable
fun ItemSwipeBox(
    onSwipeLeft: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSwipeRight: (() -> Unit)? = null,
    leftIcon: ImageVector? = null,
    leftLabel: String? = null,
    rightIcon: ImageVector? = null,
    rightLabel: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(ListDensity.corner),
    threshold: Dp = DEFAULT_SWIPE_THRESHOLD,
    maxDrag: Dp = DEFAULT_MAX_DRAG,
    leftContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    leftContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    rightContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    rightContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentBackgroundColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    val canSwipeLeft = enabled && onSwipeLeft != null
    val canSwipeRight = enabled && onSwipeRight != null

    if (!canSwipeLeft && !canSwipeRight) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val thresholdPx = with(density) { threshold.toPx() }
    val maxDragPx = with(density) { maxDrag.toPx() }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animOffset = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }

    val effectiveOffset = if (isAnimating) animOffset.value else dragOffsetX

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(canSwipeLeft, canSwipeRight, thresholdPx, maxDragPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var overSlopTotal = 0f
                    var hasTickedThreshold = false

                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        val shouldConsume = (overSlop < 0f && canSwipeLeft) || (overSlop > 0f && canSwipeRight)
                        if (shouldConsume) {
                            overSlopTotal = overSlop
                            change.consume()
                        }
                    }

                    val isLeftValid = drag != null && overSlopTotal < 0f && canSwipeLeft
                    val isRightValid = drag != null && overSlopTotal > 0f && canSwipeRight

                    if (isLeftValid || isRightValid) {
                        dragOffsetX = overSlopTotal

                        val success = horizontalDrag(drag.id) { change ->
                            val amount = change.positionChange().x
                            val candidate = dragOffsetX + amount
                            val bounded = when {
                                candidate < 0f && !canSwipeLeft -> 0f
                                candidate > 0f && !canSwipeRight -> 0f
                                else -> candidate
                            }
                            dragOffsetX = bounded.coerceIn(-maxDragPx, maxDragPx)
                            change.consume()

                            val reached = (canSwipeLeft && dragOffsetX <= -thresholdPx) ||
                                (canSwipeRight && dragOffsetX >= thresholdPx)
                            if (reached && !hasTickedThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                hasTickedThreshold = true
                            } else if (!reached && hasTickedThreshold) {
                                hasTickedThreshold = false
                            }
                        }

                        val finalOffset = dragOffsetX
                        val triggered = (canSwipeLeft && finalOffset <= -thresholdPx) ||
                            (canSwipeRight && finalOffset >= thresholdPx)

                        if (triggered) {
                            if (finalOffset < 0f) onSwipeLeft?.invoke()
                            else onSwipeRight?.invoke()
                        }

                        coroutineScope.launch {
                            animOffset.snapTo(finalOffset)
                            isAnimating = true
                            animOffset.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            dragOffsetX = 0f
                            isAnimating = false
                        }
                    }
                }
            }
    ) {
        // Background indicator revealed under the sliding item
        if (effectiveOffset < -1f && canSwipeLeft) {
            val progress = (-effectiveOffset / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(leftContainerColor),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .graphicsLayer {
                            alpha = progress
                            scaleX = 0.75f + (0.25f * progress)
                            scaleY = 0.75f + (0.25f * progress)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!leftLabel.isNullOrBlank()) {
                        Text(
                            text = leftLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = leftContentColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (leftIcon != null) {
                        Icon(
                            imageVector = leftIcon,
                            contentDescription = leftLabel,
                            tint = leftContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else if (effectiveOffset > 1f && canSwipeRight) {
            val progress = (effectiveOffset / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(rightContainerColor),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .graphicsLayer {
                            alpha = progress
                            scaleX = 0.75f + (0.25f * progress)
                            scaleY = 0.75f + (0.25f * progress)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rightIcon != null) {
                        Icon(
                            imageVector = rightIcon,
                            contentDescription = rightLabel,
                            tint = rightContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!rightLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = rightLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = rightContentColor
                        )
                    }
                }
            }
        }

        // Foreground content sliding horizontally
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(effectiveOffset.roundToInt(), 0) }
                .then(
                    if (effectiveOffset != 0f) {
                        Modifier
                            .clip(shape)
                            .background(contentBackgroundColor)
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}
