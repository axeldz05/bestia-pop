package com.bestiapop.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Gestor unificado para pantallas modales superpuestas con apertura por swipe up progresivo
 * y cierre por swipe down progresivo con seguimiento 1:1 del dedo.
 */
@Stable
class ProgressiveSheetState(
    var screenHeightPx: Float,
    val coroutineScope: CoroutineScope,
    var onDismissCallback: () -> Unit = {},
    var onOpenCallback: () -> Unit = {},
    val dismissThresholdFraction: Float = 0.15f,
    val openThresholdFraction: Float = 0.08f,
) {
    var offset by mutableFloatStateOf(screenHeightPx)
        private set

    val isOpen: Boolean by derivedStateOf { offset < screenHeightPx }
    val isFullyOpen: Boolean by derivedStateOf { offset <= 0f }
    val progress: Float by derivedStateOf {
        if (screenHeightPx <= 0f) 0f else (1f - (offset / screenHeightPx)).coerceIn(0f, 1f)
    }

    private val animatable = Animatable(screenHeightPx)
    private var animationJob: Job? = null

    fun updateScreenHeight(newHeight: Float) {
        if (newHeight <= 0f) return
        val wasClosed = offset >= screenHeightPx
        screenHeightPx = newHeight
        if (wasClosed) {
            offset = newHeight
        }
    }

    fun cancelAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    fun open(durationMs: Int = 280) {
        cancelAnimation()
        onOpenCallback()
        animationJob =
            coroutineScope.launch {
                animatable.snapTo(offset)
                animatable.animateTo(0f, tween(durationMs)) {
                    offset = value
                }
            }
    }

    fun dismiss(durationMs: Int = 250) {
        cancelAnimation()
        animationJob =
            coroutineScope.launch {
                animatable.snapTo(offset)
                animatable.animateTo(screenHeightPx, tween(durationMs)) {
                    offset = value
                }
                onDismissCallback()
            }
    }

    fun snapTo(targetOffset: Float) {
        cancelAnimation()
        offset = targetOffset.coerceIn(0f, screenHeightPx)
    }

    fun onDragDelta(delta: Float) {
        cancelAnimation()
        offset = (offset + delta).coerceIn(0f, screenHeightPx)
    }

    fun settleFromOpenDrag(velocity: Float = 0f) {
        cancelAnimation()
        val current = offset
        animationJob =
            coroutineScope.launch {
                animatable.snapTo(current)
                if (current < screenHeightPx * (1f - openThresholdFraction) || velocity < -150f) {
                    animatable.animateTo(0f, tween(220)) {
                        offset = value
                    }
                    onOpenCallback()
                } else {
                    animatable.animateTo(screenHeightPx, tween(220)) {
                        offset = value
                    }
                    onDismissCallback()
                }
            }
    }

    fun settleFromDismissDrag(velocity: Float = 0f) {
        cancelAnimation()
        val current = offset
        animationJob =
            coroutineScope.launch {
                animatable.snapTo(current)
                if (current > screenHeightPx * dismissThresholdFraction || velocity > 150f) {
                    animatable.animateTo(screenHeightPx, tween(220)) {
                        offset = value
                    }
                    onDismissCallback()
                } else {
                    animatable.animateTo(0f, tween(200)) {
                        offset = value
                    }
                }
            }
    }
}

@Composable
fun rememberProgressiveSheetState(
    screenHeightPx: Float,
    onDismiss: () -> Unit = {},
    onOpen: () -> Unit = {},
    dismissThresholdFraction: Float = 0.15f,
    openThresholdFraction: Float = 0.08f,
): ProgressiveSheetState {
    val coroutineScope = rememberCoroutineScope()
    val state =
        remember {
            ProgressiveSheetState(
                screenHeightPx = screenHeightPx,
                coroutineScope = coroutineScope,
                onDismissCallback = onDismiss,
                onOpenCallback = onOpen,
                dismissThresholdFraction = dismissThresholdFraction,
                openThresholdFraction = openThresholdFraction,
            )
        }
    LaunchedEffect(screenHeightPx) {
        state.updateScreenHeight(screenHeightPx)
    }
    state.onDismissCallback = onDismiss
    state.onOpenCallback = onOpen
    return state
}

/**
 * Modifier para barras disparadoras (BottomPlayerBar, barra "Cola") que detecta swipe UP progresivo
 * y tap (onClick) unificado, actualizando el desplazamiento del sheet 1:1 con el dedo.
 */
fun Modifier.sheetDragUpTrigger(
    state: ProgressiveSheetState,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onStartDrag: () -> Unit = {},
): Modifier =
    if (!enabled) {
        this
    } else {
        this.pointerInput(state.screenHeightPx, enabled) {
            val velocityTracker = VelocityTracker()
            val touchSlop = viewConfiguration.touchSlop

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                velocityTracker.resetTracking()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var isDragging = false
                var totalDragY = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                    if (!change.pressed) {
                        if (isDragging) {
                            change.consume()
                            val velocity = velocityTracker.calculateVelocity().y
                            state.settleFromOpenDrag(velocity)
                        } else if (!change.isConsumed) {
                            change.consume()
                            onClick?.invoke()
                        }
                        break
                    }

                    val deltaY = change.positionChange().y
                    totalDragY += deltaY
                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                    if (!isDragging) {
                        if (totalDragY < -touchSlop || kotlin.math.abs(totalDragY) > touchSlop) {
                            if (totalDragY < 0f || state.offset < state.screenHeightPx) {
                                isDragging = true
                                state.cancelAnimation()
                                onStartDrag()
                                change.consume()
                                state.onDragDelta(totalDragY)
                            }
                        }
                    } else {
                        change.consume()
                        if (deltaY < 0f || state.offset < state.screenHeightPx) {
                            state.onDragDelta(deltaY)
                        }
                    }
                }
            }
        }
    }

/**
 * Modifier para la pantalla del sheet (cabecera, fondo, vista principal) que detecta swipe DOWN progresivo
 * para cerrar el sheet con seguimiento 1:1 del dedo sin interferir con taps o botones hijos.
 */
fun Modifier.sheetDragDownDismiss(
    state: ProgressiveSheetState?,
    enabled: Boolean = true,
): Modifier =
    if (state == null || !enabled) {
        this
    } else {
        this.pointerInput(state.screenHeightPx, enabled) {
            val velocityTracker = VelocityTracker()
            val touchSlop = viewConfiguration.touchSlop

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                velocityTracker.resetTracking()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var isDragging = false
                var totalDragY = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                    if (!change.pressed) {
                        if (isDragging) {
                            change.consume()
                            val velocity = velocityTracker.calculateVelocity().y
                            state.settleFromDismissDrag(velocity)
                        }
                        break
                    }

                    val deltaY = change.positionChange().y
                    totalDragY += deltaY
                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                    if (!isDragging) {
                        if (totalDragY > touchSlop) {
                            if (totalDragY > 0f || state.offset > 0f) {
                                isDragging = true
                                state.cancelAnimation()
                                change.consume()
                                state.onDragDelta(totalDragY)
                            }
                        }
                    } else {
                        change.consume()
                        if (deltaY > 0f || state.offset > 0f) {
                            state.onDragDelta(deltaY)
                        }
                    }
                }
            }
        }
    }

/**
 * Modificador de renderizado que traslada el sheet según su desplazamiento actual.
 */
fun Modifier.sheetLayout(state: ProgressiveSheetState?): Modifier =
    if (state == null) {
        this
    } else {
        this.graphicsLayer {
            translationY = state.offset
        }
    }

/**
 * NestedScrollConnection para listas internas que permite cerrar el sheet al scrollear hacia abajo
 * cuando la lista se encuentra en la posición inicial (offset 0).
 */
fun sheetNestedScrollConnection(
    state: ProgressiveSheetState?,
    canDismissAtTop: () -> Boolean = { true },
): NestedScrollConnection =
    if (state == null) {
        object : NestedScrollConnection {}
    } else {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                if (delta < 0f && state.offset > 0f) {
                    val old = state.offset
                    state.onDragDelta(delta)
                    return Offset(0f, state.offset - old)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = available.y
                if (delta > 0f && canDismissAtTop()) {
                    val old = state.offset
                    state.onDragDelta(delta)
                    return Offset(0f, state.offset - old)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (state.offset > 0f) {
                    state.settleFromDismissDrag(available.y)
                }
                return available
            }
        }
    }
