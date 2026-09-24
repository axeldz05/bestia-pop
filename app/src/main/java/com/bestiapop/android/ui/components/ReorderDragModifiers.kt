package com.bestiapop.android.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class ReorderDragModifiers(
    val rowModifier: Modifier,
    val handleModifier: Modifier?,
    val isDragging: Boolean = false,
)

/**
 * State holding container bounds and the associated [LazyListState] for auto-scrolling
 * when items are dragged towards the top or bottom edges of a reorderable list.
 */
@Stable
class ReorderListState(
    val listState: LazyListState,
) {
    var containerCoordinates: LayoutCoordinates? by mutableStateOf(null)
        internal set
}

val LocalReorderListState = compositionLocalOf<ReorderListState?> { null }

@Composable
fun rememberReorderListState(listState: LazyListState = rememberLazyListState()): ReorderListState =
    remember(listState) { ReorderListState(listState) }

fun Modifier.reorderListContainer(state: ReorderListState): Modifier =
    onGloballyPositioned { coordinates ->
        state.containerCoordinates = coordinates
    }

/**
 * Calculates auto-scroll velocity in pixels per second based on the dragged pointer's
 * vertical position within the list container.
 */
internal fun calculateAutoScrollSpeedPxPerSecond(
    pointerY: Float,
    containerHeight: Float,
    edgeZonePx: Float,
    maxSpeedPxPerSecond: Float,
): Float {
    if (containerHeight <= 0f) return 0f
    val maxEdge = containerHeight * 0.28f
    val actualEdgeZonePx = edgeZonePx.coerceAtMost(maxEdge)
    if (actualEdgeZonePx <= 0f) return 0f

    return when {
        pointerY < actualEdgeZonePx -> {
            val distance = (actualEdgeZonePx - pointerY).coerceAtLeast(0f)
            val fraction = (distance / actualEdgeZonePx).coerceIn(0.15f, 2.0f)
            -maxSpeedPxPerSecond * fraction
        }

        pointerY > (containerHeight - actualEdgeZonePx) -> {
            val distance = (pointerY - (containerHeight - actualEdgeZonePx)).coerceAtLeast(0f)
            val fraction = (distance / actualEdgeZonePx).coerceIn(0.15f, 2.0f)
            maxSpeedPxPerSecond * fraction
        }

        else -> {
            0f
        }
    }
}

/**
 * Encapsulates interactive vertical drag-to-reorder logic for list rows.
 * Measures row height dynamically to support varied row heights and compact/expanded layouts.
 * Uses [PointerEventPass.Initial] on drag handles so parent scroll containers cannot
 * steal or cancel the drag gesture.
 * When [reorderListState] is present, automatically pins the item in composition and auto-scrolls
 * the list when the dragged item touches the top ("ceiling") or bottom ("floor") edges.
 */
@Composable
fun rememberVerticalReorderDrag(
    index: Int,
    reorderCount: Int,
    enabled: Boolean = true,
    reorderListState: ReorderListState? = LocalReorderListState.current,
    onDragStateChanged: ((Boolean) -> Unit)? = null,
    onReorder: ((from: Int, to: Int) -> Unit)?,
): ReorderDragModifiers {
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var currentScrollSpeedPxPerSecond by remember { mutableFloatStateOf(0f) }
    var handleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val measured = Modifier.onSizeChanged { rowHeightPx = it.height }
    val isDragActive = enabled && onReorder != null && reorderCount > 1

    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentIndex by rememberUpdatedState(index)
    val currentReorderCount by rememberUpdatedState(reorderCount)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)

    val density = LocalDensity.current
    val fallbackRowPx = remember(density) { with(density) { 56.dp.toPx() } }
    val edgeZonePx = remember(density) { with(density) { 72.dp.toPx() } }
    val maxSpeedPxPerSecond = remember(density) { with(density) { 900.dp.toPx() } }
    val elevationPx = remember(density) { with(density) { 6.dp.toPx() } }
    val pinnableContainer = LocalPinnableContainer.current

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val listState = reorderListState?.listState ?: return@LaunchedEffect
        var lastFrameNanos = 0L
        try {
            listState.scroll(MutatePriority.UserInput) {
                while (isActive && isDragging) {
                    withFrameNanos { frameTimeNanos ->
                        if (lastFrameNanos == 0L) {
                            lastFrameNanos = frameTimeNanos
                            return@withFrameNanos
                        }
                        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                        lastFrameNanos = frameTimeNanos

                        val speed = currentScrollSpeedPxPerSecond
                        if (speed != 0f) {
                            val toScroll = speed * dt
                            val consumed = scrollBy(toScroll)
                            if (consumed != 0f) {
                                dragOffsetY += consumed
                            }
                        }
                    }
                }
            }
        } finally {
            currentScrollSpeedPxPerSecond = 0f
        }
    }

    val rowModifier =
        if (isDragActive) {
            measured
                .zIndex(if (isDragging) 10f else 0f)
                .graphicsLayer {
                    shadowElevation = if (isDragging) elevationPx else 0f
                }.offset { IntOffset(0, dragOffsetY.roundToInt()) }
        } else {
            measured
        }

    val handleModifier =
        if (isDragActive) {
            Modifier
                .onGloballyPositioned { handleCoordinates = it }
                .pointerInput(isDragActive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        val pinHandle = pinnableContainer?.pin()
                        isDragging = true
                        currentOnDragStateChanged?.invoke(true)
                        dragOffsetY = 0f
                        currentScrollSpeedPxPerSecond = 0f
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val rowPx = rowHeightPx.takeIf { it > 0 }?.toFloat() ?: fallbackRowPx
                                    val deltaSlots = (dragOffsetY / rowPx).roundToInt()
                                    val to = (currentIndex + deltaSlots).coerceIn(0, currentReorderCount - 1)
                                    if (to != currentIndex) {
                                        currentOnReorder?.invoke(currentIndex, to)
                                    }
                                    change.consume()
                                    break
                                }
                                val deltaY = change.positionChange().y
                                dragOffsetY += deltaY
                                change.consume()

                                val container = reorderListState?.containerCoordinates
                                val handle = handleCoordinates
                                if (container != null && container.isAttached && handle != null && handle.isAttached) {
                                    val pointerInContainer = container.localPositionOf(handle, change.position)
                                    currentScrollSpeedPxPerSecond =
                                        calculateAutoScrollSpeedPxPerSecond(
                                            pointerY = pointerInContainer.y,
                                            containerHeight = container.size.height.toFloat(),
                                            edgeZonePx = edgeZonePx,
                                            maxSpeedPxPerSecond = maxSpeedPxPerSecond,
                                        )
                                } else {
                                    currentScrollSpeedPxPerSecond = 0f
                                }
                            }
                        } finally {
                            currentScrollSpeedPxPerSecond = 0f
                            dragOffsetY = 0f
                            isDragging = false
                            currentOnDragStateChanged?.invoke(false)
                            pinHandle?.release()
                        }
                    }
                }
        } else {
            null
        }

    return ReorderDragModifiers(rowModifier, handleModifier, isDragging)
}
