package com.bestiapop.android.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

class ReorderDragModifiers(
    val rowModifier: Modifier,
    val handleModifier: Modifier?
)

/**
 * Encapsulates interactive vertical drag-to-reorder logic for list rows.
 * Measures row height dynamically to support varied row heights and compact/expanded layouts.
 */
@Composable
fun rememberVerticalReorderDrag(
    index: Int,
    reorderCount: Int,
    enabled: Boolean = true,
    onReorder: ((from: Int, to: Int) -> Unit)?
): ReorderDragModifiers {
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val measured = Modifier.onSizeChanged { rowHeightPx = it.height }
    val isDragActive = enabled && onReorder != null && reorderCount > 1

    val rowModifier = if (isDragActive) {
        measured
            .zIndex(if (dragOffsetY != 0f) 1f else 0f)
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
    } else {
        measured
    }

    val handleModifier = if (isDragActive) {
        Modifier.pointerInput(index, reorderCount) {
            detectVerticalDragGestures(
                onDragEnd = {
                    val rowPx = rowHeightPx.takeIf { it > 0 }?.toFloat() ?: 56.dp.toPx()
                    val deltaSlots = (dragOffsetY / rowPx).roundToInt()
                    val to = (index + deltaSlots).coerceIn(0, reorderCount - 1)
                    if (to != index) onReorder(index, to)
                    dragOffsetY = 0f
                },
                onDragCancel = { dragOffsetY = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    dragOffsetY += amount
                }
            )
        }
    } else {
        null
    }

    return ReorderDragModifiers(rowModifier, handleModifier)
}
