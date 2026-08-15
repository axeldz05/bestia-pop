package com.bestiapop.android.ui.screens.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.SyncedLyricLine
import com.bestiapop.android.data.util.SyncedLyrics
import com.bestiapop.android.ui.components.PlaybackScrubber
import com.bestiapop.android.ui.components.playPauseVector
import kotlinx.coroutines.flow.StateFlow

@Composable
fun EditLyricsDialog(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    onDismiss: () -> Unit,
    onSave: (lyrics: String?) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onFetchOnline: ((String?) -> Unit) -> Unit
) {
    val positionMs by positionMsFlow.collectAsState()
    val initialLines = remember(song.id, song.lyrics) { SyncedLyrics.parse(song.lyrics.orEmpty()) }
    var lines by remember(song.id, song.lyrics) { mutableStateOf(initialLines) }
    var text by remember(song.id, song.lyrics) { mutableStateOf(SyncedLyrics.plainText(initialLines)) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var fetching by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val thisPlaying = isCurrent && isPlaying
    val highlightIndex = if (isCurrent) SyncedLyrics.currentLineIndex(lines, positionMs) else -1

    fun applyFetched(raw: String?) {
        fetching = false
        if (raw.isNullOrBlank()) return
        val parsed = SyncedLyrics.parse(raw)
        lines = parsed
        text = SyncedLyrics.plainText(parsed).ifBlank { raw.trim() }
    }

    fun requestFetch() {
        if (fetching) return
        fetching = true
        onFetchOnline { applyFetched(it) }
    }

    fun applyPlainText(raw: String) {
        if (SyncedLyrics.looksLikeLrc(raw)) {
            val parsed = SyncedLyrics.parse(raw)
            lines = parsed.ifEmpty { listOf(SyncedLyricLine(text = raw)) }
            text = SyncedLyrics.plainText(lines)
        } else {
            text = raw
            lines = SyncedLyrics.realignByText(lines, raw.split("\n"))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Editar letra",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Texto") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Sincronizar") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedTab == 0) {
                    LyricsTextTab(
                        text = text,
                        lines = lines,
                        highlightIndex = highlightIndex,
                        fetching = fetching,
                        onTextChange = ::applyPlainText,
                        onRequestFetch = {
                            val hasContent = text.isNotBlank() || SyncedLyrics.hasTimestamps(lines)
                            if (hasContent) confirmOverwrite = true else requestFetch()
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LyricsSyncTab(
                        lines = lines,
                        highlightIndex = highlightIndex,
                        canStamp = isCurrent,
                        onStamp = { index ->
                            if (!isCurrent || lines.isEmpty()) return@LyricsSyncTab
                            lines = lines.mapIndexed { i, line ->
                                if (i == index) SyncedLyrics.stamp(line, positionMs) else line
                            }
                        },
                        onClearTime = { index ->
                            lines = lines.mapIndexed { i, line ->
                                if (i == index) line.copy(timeMs = null) else line
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LyricsPlaybackBar(
                    durationMs = durationMs,
                    positionMsFlow = positionMsFlow,
                    isPlaying = thisPlaying,
                    seekEnabled = isCurrent,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(
                        onClick = {
                            onSave(SyncedLyrics.format(lines).ifBlank { null })
                        }
                    ) {
                        Text("Guardar")
                    }
                }
            }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Reemplazar letra") },
            text = { Text("Esto reemplaza la letra actual por la que se encuentre en línea.") },
            confirmButton = {
                Button(onClick = {
                    confirmOverwrite = false
                    requestFetch()
                }) { Text("Reemplazar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun LyricsTextTab(
    text: String,
    lines: List<SyncedLyricLine>,
    highlightIndex: Int,
    fetching: Boolean,
    onTextChange: (String) -> Unit,
    onRequestFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = textColor))

    LaunchedEffect(highlightIndex) {
        val layout = textLayout ?: return@LaunchedEffect
        if (highlightIndex < 0) return@LaunchedEffect
        val offset = offsetForLyricLine(text, highlightIndex).coerceIn(0, text.length)
        val layoutLine = layout.getLineForOffset(offset)
        val top = layout.getLineTop(layoutLine).toInt()
        val target = (top - 24).coerceAtLeast(0)
        if (target != scrollState.value) {
            scrollState.animateScrollTo(target.coerceAtMost(scrollState.maxValue))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRequestFetch, enabled = !fetching) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Buscar en línea")
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .drawBehind {
                        val layout = textLayout ?: return@drawBehind
                        if (highlightIndex < 0 || !SyncedLyrics.hasTimestamps(lines)) return@drawBehind
                        val offset = offsetForLyricLine(text, highlightIndex).coerceIn(0, text.length)
                        val layoutLine = layout.getLineForOffset(offset)
                        val top = layout.getLineTop(layoutLine)
                        val bottom = layout.getLineBottom(layoutLine)
                        drawRect(
                            color = highlightColor,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, (bottom - top).coerceAtLeast(1f))
                        )
                    }
            )
        }
    }
}

@Composable
private fun LyricsSyncTab(
    lines: List<SyncedLyricLine>,
    highlightIndex: Int,
    canStamp: Boolean,
    onStamp: (Int) -> Unit,
    onClearTime: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(highlightIndex) {
        if (highlightIndex >= 0) {
            listState.animateScrollToItem(highlightIndex)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (!canStamp) {
            Text(
                text = "Reproducí esta canción para marcar tiempos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (lines.isEmpty() || lines.all { it.text.isBlank() }) {
            Text(
                text = "Escribí la letra en Texto para sincronizarla.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(lines) { index, line ->
                    val current = index == highlightIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canStamp) { onStamp(index) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = line.text.ifBlank { " " },
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            val timeLabel = line.timeMs?.let(SyncedLyrics::formatTimestamp)
                            if (timeLabel != null) {
                                Text(
                                    text = timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (line.timeMs != null) {
                            IconButton(onClick = { onClearTime(index) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Quitar tiempo"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPlaybackBar(
    durationMs: Long,
    positionMsFlow: StateFlow<Long>,
    isPlaying: Boolean,
    seekEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = playPauseVector(isPlaying),
                contentDescription = if (isPlaying) "Pausa" else "Reproducir"
            )
        }
        PlaybackScrubber(
            durationMs = durationMs,
            positionMsFlow = positionMsFlow,
            onSeek = onSeek,
            enabled = seekEnabled,
            holdAtZero = !seekEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun offsetForLyricLine(text: String, lineIndex: Int): Int {
    if (lineIndex <= 0) return 0
    var seen = 0
    text.forEachIndexed { i, c ->
        if (c == '\n') {
            seen++
            if (seen == lineIndex) return i + 1
        }
    }
    return text.length
}
