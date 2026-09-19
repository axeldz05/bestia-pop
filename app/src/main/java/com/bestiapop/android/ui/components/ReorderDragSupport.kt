package com.bestiapop.android.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

class ReorderDragModifiers(
    val rowModifier: Modifier,
    val handleModifier: Modifier?,
    val isDragging: Boolean = false
)

/**
 * Encapsulates interactive vertical drag-to-reorder logic for list rows.
 * Measures row height dynamically to support varied row heights and compact/expanded layouts.
 * Uses [PointerEventPass.Initial] on drag handles so parent scroll containers cannot
 * steal or cancel the drag gesture.
 */
@Composable
fun rememberVerticalReorderDrag(
    index: Int,
    reorderCount: Int,
    enabled: Boolean = true,
    onDragStateChanged: ((Boolean) -> Unit)? = null,
    onReorder: ((from: Int, to: Int) -> Unit)?
): ReorderDragModifiers {
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val measured = Modifier.onSizeChanged { rowHeightPx = it.height }
    val isDragActive = enabled && onReorder != null && reorderCount > 1

    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentIndex by rememberUpdatedState(index)
    val currentReorderCount by rememberUpdatedState(reorderCount)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)

    val rowModifier = if (isDragActive) {
        measured
            .zIndex(if (isDragging) 10f else 0f)
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
    } else {
        measured
    }

    val handleModifier = if (isDragActive) {
        Modifier.pointerInput(isDragActive) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                isDragging = true
                currentOnDragStateChanged?.invoke(true)
                dragOffsetY = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val rowPx = rowHeightPx.takeIf { it > 0 }?.toFloat() ?: 56.dp.toPx()
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
                    }
                } finally {
                    dragOffsetY = 0f
                    isDragging = false
                    currentOnDragStateChanged?.invoke(false)
                }
            }
        }
    } else {
        null
    }

    return ReorderDragModifiers(rowModifier, handleModifier, isDragging)
}
