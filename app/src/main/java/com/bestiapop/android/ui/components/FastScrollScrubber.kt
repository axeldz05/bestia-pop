package com.bestiapop.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.data.preferences.FastScrollSide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Maps [FastScrollSide] to corresponding Compose [Alignment].
 */
fun FastScrollSide.toAlignment(): Alignment =
    if (this == FastScrollSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd

/**
 * Section descriptor for fast scroll.
 *
 * @property label Compact symbol shown on the vertical rail (e.g. "A", "'26", "Rk").
 * @property popupLabel Full title shown in the preview bubble (e.g. "A", "2026", "Rock").
 * @property itemIndex Target index in the LazyList to jump to.
 * @property previewText Optional text preview of the first item in this section.
 */
@Immutable
data class FastScrollSection(
    val label: String,
    val popupLabel: String,
    val itemIndex: Int,
    val previewText: String? = null
) {
    constructor(
        labelPair: Pair<String, String>,
        itemIndex: Int,
        previewText: String? = null
    ) : this(
        label = labelPair.first,
        popupLabel = labelPair.second,
        itemIndex = itemIndex,
        previewText = previewText
    )
}

/**
 * Shared layout constants and utilities for fast scroll navigation.
 */
object FastScrollDefaults {
    val RailWidth: Dp = 32.dp
    val DedicatedGutterWidth: Dp = 36.dp
    val DedicatedLeftGutterWidth: Dp = DedicatedGutterWidth
    val DedicatedRightGutterWidth: Dp = DedicatedGutterWidth

    /**
     * Calculates the dedicated start (left) padding required for the list content.
     * When [side] is [FastScrollSide.LEFT], reserves dedicated gutter space so the rail
     * never overlaps item artwork, checkboxes, or touch targets.
     */
    fun dedicatedStartGutterWidth(
        enabled: Boolean,
        side: FastScrollSide,
        sectionsCount: Int
    ): Dp = if (enabled && side == FastScrollSide.LEFT && sectionsCount > 1) {
        DedicatedLeftGutterWidth
    } else {
        0.dp
    }

    /**
     * Calculates the dedicated end (right) padding required for the list content.
     * When [side] is [FastScrollSide.RIGHT], reserves dedicated gutter space so the rail
     * never overlaps item action buttons (3 dots menu, play/shuffle) or trailing touch targets.
     */
    fun dedicatedEndGutterWidth(
        enabled: Boolean,
        side: FastScrollSide,
        sectionsCount: Int
    ): Dp = if (enabled && side == FastScrollSide.RIGHT && sectionsCount > 1) {
        DedicatedRightGutterWidth
    } else {
        0.dp
    }

    fun dedicatedStartGutterWidth(
        settings: FastScrollSettings,
        sectionsCount: Int
    ): Dp = dedicatedStartGutterWidth(settings.enabled, settings.side, sectionsCount)

    fun dedicatedEndGutterWidth(
        settings: FastScrollSettings,
        sectionsCount: Int
    ): Dp = dedicatedEndGutterWidth(settings.enabled, settings.side, sectionsCount)

    /**
     * Calculates the dedicated start padding (kept for backwards compatibility).
     */
    fun dedicatedGutterWidth(
        enabled: Boolean,
        side: FastScrollSide,
        sectionsCount: Int
    ): Dp = dedicatedStartGutterWidth(enabled, side, sectionsCount)

    fun dedicatedGutterWidth(
        settings: FastScrollSettings,
        sectionsCount: Int
    ): Dp = dedicatedStartGutterWidth(settings.enabled, settings.side, sectionsCount)
}

/**
 * Level 2 container: Hosts a scrollable list alongside [FastScrollScrubber], automatically
 * reserving a dedicated gutter on the active edge (left or right) so the rail never
 * overlaps item artwork, checkboxes, or action buttons.
 */
@Composable
fun FastScrollContainer(
    sections: List<FastScrollSection>,
    listState: LazyListState,
    settings: FastScrollSettings,
    modifier: Modifier = Modifier,
    content: @Composable (contentModifier: Modifier) -> Unit
) {
    FastScrollContainer(
        sections = sections,
        listState = listState,
        modifier = modifier,
        enabled = settings.enabled,
        side = settings.side,
        content = content
    )
}

/**
 * Level 1 container: Primitive container overload taking individual parameters for continuous granularity.
 */
@Composable
fun FastScrollContainer(
    sections: List<FastScrollSection>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    side: FastScrollSide = FastScrollSide.RIGHT,
    content: @Composable (contentModifier: Modifier) -> Unit
) {
    val startGutter = FastScrollDefaults.dedicatedStartGutterWidth(enabled, side, sections.size)
    val endGutter = FastScrollDefaults.dedicatedEndGutterWidth(enabled, side, sections.size)

    Box(modifier = modifier.fillMaxSize()) {
        content(
            Modifier
                .fillMaxSize()
                .padding(start = startGutter, end = endGutter)
        )

        FastScrollScrubber(
            sections = sections,
            listState = listState,
            enabled = enabled,
            side = side,
            modifier = Modifier.align(side.toAlignment())
        )
    }
}

/**
 * Level 2: Shared FastScroll LazyColumn layout for aggregate/browse lists (albums, artists, genres).
 * Renders [EmptyListHint] when empty, otherwise sets up [FastScrollContainer] + [LazyColumn].
 */
@Composable
fun <T> FastScrollLazyColumn(
    items: List<T>,
    sections: List<FastScrollSection>,
    emptyText: String,
    modifier: Modifier = Modifier,
    emptySubtitle: String? = null,
    key: ((T) -> Any)? = null,
    listState: LazyListState = rememberLazyListState(),
    fastScrollSettings: FastScrollSettings = FastScrollSettings(),
    itemContent: @Composable LazyItemScope.(T) -> Unit
) {
    if (items.isEmpty()) {
        EmptyListHint(
            text = emptyText,
            subtitle = emptySubtitle,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    FastScrollContainer(
        sections = sections,
        listState = listState,
        settings = fastScrollSettings,
        modifier = modifier.fillMaxSize()
    ) { listModifier ->
        LazyColumn(state = listState, modifier = listModifier) {
            items(items, key = key) { item ->
                itemContent(item)
            }
        }
    }
}

/**
 * Level 2: Scrubber accepting bundled [FastScrollSettings] and auto-aligning to the correct edge in [BoxScope].
 */
@Composable
fun BoxScope.FastScrollScrubber(
    sections: List<FastScrollSection>,
    listState: LazyListState,
    settings: FastScrollSettings,
    modifier: Modifier = Modifier
) {
    FastScrollScrubber(
        sections = sections,
        listState = listState,
        enabled = settings.enabled,
        side = settings.side,
        modifier = modifier.align(settings.side.toAlignment())
    )
}

/**
 * Level 2: Scrubber accepting bundled [FastScrollSettings] without required [BoxScope].
 */
@Composable
fun FastScrollScrubber(
    sections: List<FastScrollSection>,
    listState: LazyListState,
    settings: FastScrollSettings,
    modifier: Modifier = Modifier
) {
    FastScrollScrubber(
        sections = sections,
        listState = listState,
        modifier = modifier,
        enabled = settings.enabled,
        side = settings.side
    )
}

/**
 * Level 1/2: Interactive vertical fast scrollbar connected to a [LazyListState].
 * Includes haptic ticks, magnifying letter wave animation, and floating indicator bubble.
 */
@Composable
fun FastScrollScrubber(
    sections: List<FastScrollSection>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    side: FastScrollSide = FastScrollSide.RIGHT
) {
    if (!enabled || sections.size <= 1) return

    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var railHeightPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val bubbleOffsetY = remember(touchY, railHeightPx) {
        if (railHeightPx <= 0f) 0
        else {
            val clampedY = touchY.coerceIn(0f, railHeightPx)
            with(density) { (clampedY - 24.dp.toPx()).roundToInt() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 12.dp)
            .onSizeChanged { railHeightPx = it.height.toFloat() }
    ) {
        val safeActiveIndex = activeIndex.coerceIn(0, sections.size - 1)
        val currentSection = sections[safeActiveIndex]
        val isLeft = side == FastScrollSide.LEFT

        // Floating preview bubble (positioned opposite the screen edge)
        AnimatedVisibility(
            visible = isDragging,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(if (isLeft) Alignment.TopStart else Alignment.TopEnd)
                .offset { IntOffset(x = 0, y = bubbleOffsetY) }
                .padding(start = if (isLeft) 44.dp else 0.dp, end = if (isLeft) 0.dp else 44.dp)
        ) {
            FastScrollIndicatorBubble(section = currentSection)
        }

        val currentVisibleSectionIndex by remember(sections, listState) {
            derivedStateOf {
                val firstVisible = listState.firstVisibleItemIndex
                var matched = 0
                for (i in sections.indices) {
                    if (sections[i].itemIndex <= firstVisible) {
                        matched = i
                    } else {
                        break
                    }
                }
                matched
            }
        }

        val scrollFraction by remember(listState) {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems <= 1) 0f
                else {
                    val first = listState.firstVisibleItemIndex
                    (first.toFloat() / (totalItems - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                }
            }
        }

        // Vertical touch rail (Level 1 primitive)
        FastScrollRail(
            sections = sections,
            activeIndex = if (isDragging) safeActiveIndex else -1,
            currentVisibleIndex = currentVisibleSectionIndex,
            scrollFraction = scrollFraction,
            isDragging = isDragging,
            side = side,
            onSectionSelected = { newIndex, absoluteY ->
                touchY = absoluteY
                if (newIndex != activeIndex || !isDragging) {
                    activeIndex = newIndex
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    coroutineScope.launch {
                        listState.scrollToItem(sections[newIndex].itemIndex)
                    }
                }
                isDragging = true
            },
            onDragEnd = {
                coroutineScope.launch {
                    delay(250)
                    isDragging = false
                }
            },
            modifier = Modifier
                .align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .then(if (isLeft) Modifier.padding(start = 2.dp) else Modifier.padding(end = 2.dp))
        )
    }
}

/**
 * Level 1: Pure vertical index rail rendering letters and capturing touch geometry with exact item coordinates.
 */
@Composable
fun FastScrollRail(
    sections: List<FastScrollSection>,
    activeIndex: Int,
    currentVisibleIndex: Int = -1,
    scrollFraction: Float = 0f,
    isDragging: Boolean = false,
    side: FastScrollSide = FastScrollSide.RIGHT,
    onSectionSelected: (index: Int, touchY: Float) -> Unit,
    onTouchFractionChange: (fraction: Float, touchY: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val count = sections.size
    val fontSize = when {
        count > 28 -> 8.sp
        count > 20 -> 9.sp
        count > 14 -> 10.sp
        else -> 11.sp
    }

    var railHeightPx by remember { mutableFloatStateOf(0f) }
    var railCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemCenters = remember(sections) { FloatArray(sections.size) }
    val density = LocalDensity.current
    val thumbHeightDp = 24.dp
    val verticalPaddingDp = 4.dp
    val verticalPaddingPx = with(density) { verticalPaddingDp.toPx() }
    val isLeft = side == FastScrollSide.LEFT

    Box(
        modifier = modifier
            .width(32.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
            )
            .onGloballyPositioned { railCoordinates = it }
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .pointerInput(sections) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val selectedIndex = FastScrollSections.findClosestSectionIndex(
                        touchY = down.position.y,
                        itemCenters = itemCenters,
                        sectionsCount = sections.size,
                        railHeight = railHeightPx,
                        verticalPaddingPx = verticalPaddingPx
                    )
                    val fraction = if (railHeightPx > 0f) (down.position.y / railHeightPx).coerceIn(0f, 1f) else 0f
                    onSectionSelected(selectedIndex, down.position.y)
                    onTouchFractionChange(fraction, down.position.y)

                    drag(down.id) { change ->
                        val positionChange = change.positionChange()
                        if (positionChange.y != 0f || positionChange.x != 0f) {
                            change.consume()
                            val dragIndex = FastScrollSections.findClosestSectionIndex(
                                touchY = change.position.y,
                                itemCenters = itemCenters,
                                sectionsCount = sections.size,
                                railHeight = railHeightPx,
                                verticalPaddingPx = verticalPaddingPx
                            )
                            val dragFraction = if (railHeightPx > 0f) (change.position.y / railHeightPx).coerceIn(0f, 1f) else 0f
                            onSectionSelected(dragIndex, change.position.y)
                            onTouchFractionChange(dragFraction, change.position.y)
                        }
                    }
                    onDragEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Continuous position indicator thumb along the rail's inner edge
        if (railHeightPx > 0f) {
            val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
            val maxTravel = (railHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbOffsetY = (maxTravel * scrollFraction).roundToInt()
            Box(
                modifier = Modifier
                    .align(if (isLeft) Alignment.TopEnd else Alignment.TopStart)
                    .offset { IntOffset(x = 0, y = thumbOffsetY) }
                    .width(3.dp)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (isDragging) 0.9f else 0.55f
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = verticalPaddingDp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            sections.forEachIndexed { index, section ->
                val isTouched = index == activeIndex && isDragging
                val isCurrent = index == currentVisibleIndex
                val scale by animateFloatAsState(
                    targetValue = if (isTouched) 1.5f else if (isCurrent) 1.2f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                    label = "fastScrollCharScale"
                )
                val itemBackground = if (isCurrent && !isDragging) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val railCoords = railCoordinates
                            if (railCoords != null && railCoords.isAttached && coords.isAttached) {
                                val posInRail = railCoords.localPositionOf(coords, Offset.Zero)
                                val centerY = posInRail.y + coords.size.height / 2f
                                if (index in itemCenters.indices) {
                                    itemCenters[index] = centerY
                                }
                            } else {
                                val pos = coords.positionInParent()
                                val centerY = verticalPaddingPx + pos.y + coords.size.height / 2f
                                if (index in itemCenters.indices) {
                                    itemCenters[index] = centerY
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = section.label,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        fontWeight = if (isTouched || isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isTouched || isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .scale(scale)
                            .clip(RoundedCornerShape(6.dp))
                            .background(itemBackground)
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Level 1 overload retaining direct [onTouchFractionChange] callback for continuous granularity.
 */
@Composable
fun FastScrollRail(
    sections: List<FastScrollSection>,
    activeIndex: Int,
    currentVisibleIndex: Int = -1,
    scrollFraction: Float = 0f,
    isDragging: Boolean = false,
    side: FastScrollSide = FastScrollSide.RIGHT,
    onTouchFractionChange: (fraction: Float, touchY: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    FastScrollRail(
        sections = sections,
        activeIndex = activeIndex,
        currentVisibleIndex = currentVisibleIndex,
        scrollFraction = scrollFraction,
        isDragging = isDragging,
        side = side,
        onSectionSelected = { index, touchY ->
            val fraction = if (sections.isNotEmpty()) (index.toFloat() / sections.size).coerceIn(0f, 1f) else 0f
            onTouchFractionChange(fraction, touchY)
        },
        onTouchFractionChange = onTouchFractionChange,
        onDragEnd = onDragEnd,
        modifier = modifier
    )
}

/**
 * Floating bubble popup showing the magnified active section and item preview.
 */
@Composable
fun FastScrollIndicatorBubble(
    section: FastScrollSection,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = section.popupLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (!section.previewText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = section.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }
        }
    }
}
