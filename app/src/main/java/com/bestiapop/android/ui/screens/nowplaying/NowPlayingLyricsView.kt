package com.bestiapop.android.ui.screens.nowplaying

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.model.DisplayLyricLine
import com.bestiapop.android.data.model.RepeatMode
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.LyricsPhoneticProcessor
import com.bestiapop.android.data.util.SyncedLyrics
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.PlaybackScrubber
import com.bestiapop.android.ui.state.NowPlayingTransportActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Level 2: Pantalla completa de letras sincronizadas aceptando [NowPlayingTransportActions].
 */
@Composable
fun NowPlayingLyricsView(
    song: Song,
    viewModel: MusicPlayerViewModel,
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    isFetchingLyrics: Boolean,
    lyricsFetchError: String?,
    actions: NowPlayingTransportActions,
    onSeekToLyric: (Long) -> Unit,
    onRetryFetchLyrics: (Song) -> Unit,
    modifier: Modifier = Modifier
) = NowPlayingLyricsView(
    song = song,
    viewModel = viewModel,
    positionMsFlow = positionMsFlow,
    durationMs = durationMs,
    isPlaying = isPlaying,
    isShuffle = isShuffle,
    repeatMode = repeatMode,
    isFetchingLyrics = isFetchingLyrics,
    lyricsFetchError = lyricsFetchError,
    onTogglePlayPause = actions.onTogglePlayPause,
    onSkipPrevious = actions.onSkipPrevious,
    onSkipNext = actions.onSkipNext,
    onToggleShuffle = actions.onToggleShuffle,
    onToggleRepeatMode = actions.onToggleRepeatMode,
    onSeekTo = actions.onSeek,
    onSeekToLyric = onSeekToLyric,
    onRetryFetchLyrics = onRetryFetchLyrics,
    modifier = modifier
)

/**
 * Level 1: Pantalla completa de letras sincronizadas con timestamps sutiles y controles fijados en la base.
 */
@Composable
fun NowPlayingLyricsView(
    song: Song,
    viewModel: MusicPlayerViewModel,
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    isFetchingLyrics: Boolean,
    lyricsFetchError: String?,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeatMode: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekToLyric: (Long) -> Unit,
    onRetryFetchLyrics: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val rawLyrics = song.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!rawLyrics.isNullOrEmpty()) {
                val parsedLrc = remember(rawLyrics) { SyncedLyrics.parse(rawLyrics) }
                val plainLines = remember(parsedLrc) { parsedLrc.map { it.text } }
                val timed = remember(parsedLrc) { SyncedLyrics.hasTimestamps(parsedLrc) }

                val lyricsSettings by viewModel.lyricsSettings.collectAsStateWithLifecycle()
                val translationState by viewModel.lyricsTranslationState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(song.id, plainLines, lyricsSettings.phoneticGuideEnabled) {
                    if (lyricsSettings.phoneticGuideEnabled) {
                        viewModel.ensureRomanization(song.id, plainLines)
                    }
                }

                val displayLines = remember(
                    parsedLrc,
                    translationState,
                    lyricsSettings
                ) {
                    val translated = if (translationState.isTranslationActive) {
                        viewModel.getTranslatedLines(song.id)
                    } else {
                        null
                    }
                    val romanized = viewModel.getRomanizedLines(song.id)

                    parsedLrc.mapIndexed { idx, line ->
                        val formattedTime = line.timeMs?.let { formatLyricStamp(it) }
                        if (line.text.isEmpty()) {
                            DisplayLyricLine(line.timeMs, "", null, formattedTime)
                        } else if (translationState.isTranslationActive) {
                            val transText = translated?.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: line.text
                            DisplayLyricLine(
                                timeMs = line.timeMs,
                                primaryText = transText,
                                secondaryText = if (transText != line.text) line.text else null,
                                formattedTime = formattedTime
                            )
                        } else {
                            val romCandidate = romanized?.getOrNull(idx)
                            val secondary = if (lyricsSettings.phoneticGuideEnabled) {
                                LyricsPhoneticProcessor.formatPhoneticLine(
                                    original = line.text,
                                    romanizedCandidate = romCandidate,
                                    japaneseMode = lyricsSettings.japanesePhoneticMode
                                )
                            } else {
                                null
                            }
                            DisplayLyricLine(
                                timeMs = line.timeMs,
                                primaryText = line.text,
                                secondaryText = secondary,
                                formattedTime = formattedTime
                            )
                        }
                    }
                }

                if (translationState.pendingGoogleTranslatePrompt) {
                    AlertDialog(
                        onDismissRequest = viewModel::cancelGoogleTranslatePrompt,
                        title = {
                            Text("Traducción no encontrada")
                        },
                        text = {
                            Text("No se encontró una traducción comunitaria en Musixmatch ni sitios similares.\n\n¿Querés traducir esta letra con Google Traductor?")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.confirmGoogleTranslate(song, plainLines)
                                }
                            ) {
                                Text("Traducir con Google")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::cancelGoogleTranslatePrompt) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Barra superior: Atribución de fuente y botón de traducción
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (translationState.isTranslationActive && translationState.translationSource != null) {
                            val source = translationState.translationSource!!
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !source.url.isNullOrBlank()) {
                                        source.url?.let { urlStr ->
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, urlStr.toUri())
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    }
                            ) {
                                Text(
                                    text = "Fuente: ${source.name} ↗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.toggleLyricsTranslation(song, plainLines)
                            },
                            enabled = !translationState.isFetchingTranslation,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (translationState.isTranslationActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                },
                                contentColor = if (translationState.isTranslationActive) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        ) {
                            if (translationState.isFetchingTranslation) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = if (translationState.isTranslationActive) "Original" else "Traducir",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (timed) {
                            val currentLineIndex by remember(parsedLrc, positionMsFlow) {
                                positionMsFlow
                                    .map { pos -> SyncedLyrics.currentLineIndex(parsedLrc, pos) }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(initialValue = SyncedLyrics.currentLineIndex(parsedLrc, positionMsFlow.value))

                            val listState = rememberLazyListState()
                            val isDragged by listState.interactionSource.collectIsDraggedAsState()
                            var userScrolledRecent by remember { mutableStateOf(false) }

                            LaunchedEffect(isDragged) {
                                if (isDragged) {
                                    userScrolledRecent = true
                                } else if (userScrolledRecent) {
                                    delay(3500)
                                    userScrolledRecent = false
                                }
                            }

                            LaunchedEffect(currentLineIndex, userScrolledRecent, isDragged) {
                                if (!userScrolledRecent && !isDragged && currentLineIndex in displayLines.indices) {
                                    val targetIndex = (currentLineIndex - 1).coerceAtLeast(0)
                                    listState.animateScrollToItem(
                                        index = targetIndex,
                                        scrollOffset = 0
                                    )
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(vertical = 40.dp)
                            ) {
                                itemsIndexed(
                                    items = displayLines,
                                    key = { index, line -> "lyric_${index}_${line.timeMs ?: 0}" }
                                ) { index, line ->
                                    if (line.primaryText.isNotEmpty()) {
                                        TimedLyricRow(
                                            line = line,
                                            isCurrent = (index == currentLineIndex),
                                            onSeekToLyric = onSeekToLyric
                                        )
                                    }
                                }
                            }
                        } else {
                            // Letra en texto plano (sin timestamps)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                displayLines.forEach { line ->
                                    if (line.primaryText.isNotEmpty()) {
                                        UntimedLyricRow(line = line)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isFetchingLyrics) {
                // Buscando letra (local o en línea)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Buscando letra…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                // Estado sin letra
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Sin letra disponible",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { onRetryFetchLyrics(song) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buscar en línea")
                    }
                    if (!lyricsFetchError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = lyricsFetchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Controles de reproducción fijos al pie en vista de letras
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlaybackScrubber(
                    durationMs = durationMs,
                    positionMsFlow = positionMsFlow,
                    onSeek = onSeekTo
                )
                Spacer(modifier = Modifier.height(8.dp))
                NowPlayingControlsRow(
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    onToggleShuffle = onToggleShuffle,
                    onSkipPrevious = onSkipPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onToggleRepeatMode = onToggleRepeatMode,
                    playFabSize = 56.dp,
                    playIconSize = 32.dp
                )
            }
        }
    }
}

fun formatLyricStamp(timeMs: Long): String {
    val totalSec = (timeMs / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@Composable
fun TimedLyricRow(
    line: DisplayLyricLine,
    isCurrent: Boolean,
    onSeekToLyric: (Long) -> Unit
) {
    val timeMs = line.timeMs
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = timeMs != null,
                onClick = { timeMs?.let(onSeekToLyric) }
            )
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        if (line.formattedTime != null) {
            Text(
                text = line.formattedTime,
                fontSize = 11.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Text(
            text = line.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (isCurrent) 20.sp else 16.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = if (isCurrent) 0.2.sp else 0.sp
            ),
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (!line.secondaryText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = line.secondaryText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = if (isCurrent) 13.sp else 11.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun UntimedLyricRow(line: DisplayLyricLine) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp)
    ) {
        Text(
            text = line.primaryText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 26.sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxWidth()
        )
        if (!line.secondaryText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = line.secondaryText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
