package com.bestiapop.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestiapop.android.data.preferences.DynamicEqualizerRule
import com.bestiapop.android.data.preferences.EQUALIZER_PRESETS
import com.bestiapop.android.data.preferences.EQUALIZER_PRESET_CUSTOM
import com.bestiapop.android.data.preferences.EQUALIZER_PRESET_FLAT
import com.bestiapop.android.data.preferences.EqualizerBand
import com.bestiapop.android.data.preferences.EqualizerTargetType
import com.bestiapop.android.data.preferences.MAX_EQUALIZER_BANDS
import com.bestiapop.android.data.preferences.MAX_EQUALIZER_GAIN_DB
import com.bestiapop.android.data.preferences.MIN_EQUALIZER_BANDS
import com.bestiapop.android.data.preferences.MIN_EQUALIZER_GAIN_DB
import com.bestiapop.android.data.preferences.formatEqualizerFrequency
import com.bestiapop.android.data.preferences.resolveActiveRule
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.components.ScreenBackHeader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val settings by viewModel.playbackSettings.collectAsStateWithLifecycle()
    val eqSettings = settings.equalizerSettings
    val currentPlayable by viewModel.currentItem.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCustomPresetDialog by rememberSaveable { mutableStateOf(false) }
    var targetFilter by rememberSaveable { mutableStateOf<EqualizerTargetType?>(null) }

    val activeRule =
        remember(currentPlayable, eqSettings.dynamicRules, eqSettings.dynamicEnabled) {
            if (eqSettings.dynamicEnabled) resolveActiveRule(currentPlayable, eqSettings.dynamicRules) else null
        }

    val insetsModifier =
        if (onBack != null) {
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
        } else {
            Modifier
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .then(insetsModifier)
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (onBack != null) {
            ScreenBackHeader(
                title = "Ecualizador",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        // Section Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp)),
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Equalizer, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Controles", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Perfiles (${eqSettings.dynamicRules.size})",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        }

        if (selectedTab == 0) {
            // Tab 0: Equalizer Controls
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Master Switch & Reset Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color =
                                    if (eqSettings.enabled) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Equalizer,
                                        contentDescription = null,
                                        tint =
                                            if (eqSettings.enabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Ecualizador",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text =
                                        if (eqSettings.enabled) {
                                            "${eqSettings.bandCount} bandas activas"
                                        } else {
                                            "Desactivado (sin procesar)"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedVisibility(visible = eqSettings.enabled) {
                                TextButton(
                                    onClick = viewModel::resetEqualizer,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Plano",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Switch(
                                checked = eqSettings.enabled,
                                onCheckedChange = viewModel::setEqualizerEnabled,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dynamic Equalizer Toggle Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color =
                                    if (eqSettings.dynamicEnabled) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint =
                                            if (eqSettings.dynamicEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Ecualizador dinámico",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = "Cambia automáticamente por canción, álbum o artista",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = eqSettings.dynamicEnabled,
                            onCheckedChange = viewModel::setDynamicEqualizerEnabled,
                            enabled = eqSettings.enabled,
                        )
                    }
                }

                // Active Dynamic Rule Indicator Banner
                if (activeRule != null && eqSettings.dynamicEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector =
                                        when (activeRule.targetType) {
                                            EqualizerTargetType.SONG -> Icons.Default.MusicNote
                                            EqualizerTargetType.ALBUM -> Icons.Default.Album
                                            EqualizerTargetType.ARTIST -> Icons.Default.Person
                                            EqualizerTargetType.CUSTOM_PRESET -> Icons.Default.Tune
                                        },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column {
                                    Text(
                                        text = "Perfil activo: ${activeRule.targetName}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${activeRule.targetType.label} • ${activeRule.presetName} (${activeRule.bandCount} bandas)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.removeEqualizerRule(activeRule.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Quitar asignación",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Visual Frequency Response Curve
                EqualizerCurveVisualizer(
                    bands = eqSettings.bands,
                    enabled = eqSettings.enabled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Dynamic Action Row: Apply for this Song / Album / Artist
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Asignar este ajuste a:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val playable = currentPlayable
                        val songAssigned = activeRule?.targetType == EqualizerTargetType.SONG
                        val albumAssigned = activeRule?.targetType == EqualizerTargetType.ALBUM
                        val artistAssigned = activeRule?.targetType == EqualizerTargetType.ARTIST

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Song button
                            FilterChip(
                                selected = songAssigned,
                                onClick = {
                                    if (playable != null) {
                                        viewModel.applyEqualizerRule(EqualizerTargetType.SONG, playable)
                                    }
                                },
                                label = { Text("Canción", maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (songAssigned) Icons.Default.Check else Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                enabled = eqSettings.enabled && playable?.title?.isNotBlank() == true,
                            )

                            // Album button
                            FilterChip(
                                selected = albumAssigned,
                                onClick = {
                                    if (playable != null) {
                                        viewModel.applyEqualizerRule(EqualizerTargetType.ALBUM, playable)
                                    }
                                },
                                label = { Text("Álbum", maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (albumAssigned) Icons.Default.Check else Icons.Default.Album,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                enabled = eqSettings.enabled && playable?.album?.isNotBlank() == true,
                            )

                            // Artist button
                            FilterChip(
                                selected = artistAssigned,
                                onClick = {
                                    if (playable != null) {
                                        viewModel.applyEqualizerRule(EqualizerTargetType.ARTIST, playable)
                                    }
                                },
                                label = { Text("Artista", maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (artistAssigned) Icons.Default.Check else Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                enabled = eqSettings.enabled && playable?.artist?.isNotBlank() == true,
                            )
                        }

                        if (playable == null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Reproduce una canción para asignarla directamente",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "🎵 ${playable.title} • 🎤 ${playable.artist}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets Selector
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Preajustes",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    TextButton(
                        onClick = { showCustomPresetDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Guardar preset", style = MaterialTheme.typography.labelMedium)
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(EQUALIZER_PRESETS) { preset ->
                        val isSelected = eqSettings.presetName == preset.name
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setEqualizerPreset(preset.name) },
                            label = {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    if (eqSettings.presetName == EQUALIZER_PRESET_CUSTOM) {
                        item {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = {
                                    Text(
                                        text = EQUALIZER_PRESET_CUSTOM,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                                    ),
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Band Count Stepper (5 to 12 bands)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "Bandas de ecualización",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Configurable de 5 a 12 bandas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(
                                onClick = { viewModel.setEqualizerBandCount(eqSettings.bandCount - 1) },
                                enabled = eqSettings.bandCount > MIN_EQUALIZER_BANDS,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Menos bandas",
                                    tint =
                                        if (eqSettings.bandCount > MIN_EQUALIZER_BANDS) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        },
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = "${eqSettings.bandCount}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                )
                            }

                            IconButton(
                                onClick = { viewModel.setEqualizerBandCount(eqSettings.bandCount + 1) },
                                enabled = eqSettings.bandCount < MAX_EQUALIZER_BANDS,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Más bandas",
                                    tint =
                                        if (eqSettings.bandCount < MAX_EQUALIZER_BANDS) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Vertical Sliders (Faders)
                EqualizerFadersRow(
                    bands = eqSettings.bands,
                    enabled = eqSettings.enabled,
                    onGainChange = { bandIndex, gainDb ->
                        viewModel.setEqualizerBandGain(bandIndex, gainDb)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            // Tab 1: Saved Dynamic Profiles & Presets List
            val rules = eqSettings.dynamicRules
            val filteredRules =
                remember(rules, targetFilter) {
                    if (targetFilter == null) {
                        rules
                    } else {
                        rules.filter { it.targetType == targetFilter }
                    }
                }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Filter chips row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    item {
                        FilterChip(
                            selected = targetFilter == null,
                            onClick = { targetFilter = null },
                            label = { Text("Todos (${rules.size})") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = targetFilter == EqualizerTargetType.SONG,
                            onClick = {
                                targetFilter =
                                    if (targetFilter == EqualizerTargetType.SONG) null else EqualizerTargetType.SONG
                            },
                            label = { Text("Canciones") },
                            leadingIcon = {
                                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = targetFilter == EqualizerTargetType.ALBUM,
                            onClick = {
                                targetFilter =
                                    if (targetFilter == EqualizerTargetType.ALBUM) null else EqualizerTargetType.ALBUM
                            },
                            label = { Text("Álbumes") },
                            leadingIcon = {
                                Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = targetFilter == EqualizerTargetType.ARTIST,
                            onClick = {
                                targetFilter =
                                    if (targetFilter == EqualizerTargetType.ARTIST) null else EqualizerTargetType.ARTIST
                            },
                            label = { Text("Artistas") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = targetFilter == EqualizerTargetType.CUSTOM_PRESET,
                            onClick = {
                                targetFilter =
                                    if (targetFilter == EqualizerTargetType.CUSTOM_PRESET) null else EqualizerTargetType.CUSTOM_PRESET
                            },
                            label = { Text("Presets") },
                            leadingIcon = {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }

                if (filteredRules.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                            Text(
                                text = "Sin perfiles guardados",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text =
                                    "Ajusta las bandas en el ecualizador y presiona «Canción», «Álbum» o «Artista» para que BestiaPop lo active automáticamente al reproducir.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(filteredRules, key = { it.id }) { rule ->
                            val isRuleActive = activeRule?.id == rule.id
                            DynamicEqualizerRuleCard(
                                rule = rule,
                                isActive = isRuleActive,
                                onLoad = {
                                    viewModel.loadRuleIntoEqualizer(rule)
                                    selectedTab = 0
                                },
                                onDelete = {
                                    viewModel.removeEqualizerRule(rule.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCustomPresetDialog) {
        SaveCustomPresetDialog(
            onDismiss = { showCustomPresetDialog = false },
            onSave = { presetName ->
                viewModel.applyEqualizerRule(
                    targetType = EqualizerTargetType.CUSTOM_PRESET,
                    track = null,
                    customName = presetName,
                )
                showCustomPresetDialog = false
            },
        )
    }
}

@Composable
private fun EqualizerCurveVisualizer(
    bands: List<EqualizerBand>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val curveColor by animateColorAsState(
        targetValue =
            if (enabled) {
                primaryColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
        label = "curveColor",
    )
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val width = size.width
            val height = size.height
            val midY = height / 2f
            val topY = 4f
            val bottomY = height - 4f

            // 0 dB center reference line
            drawLine(
                color = zeroLineColor,
                start = Offset(0f, midY),
                end = Offset(width, midY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )

            // +12 dB and -12 dB limits
            drawLine(
                color = gridColor,
                start = Offset(0f, topY),
                end = Offset(width, topY),
                strokeWidth = 0.5.dp.toPx(),
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, bottomY),
                end = Offset(width, bottomY),
                strokeWidth = 0.5.dp.toPx(),
            )

            if (bands.isEmpty()) return@Canvas

            val bandCount = bands.size
            val points =
                bands.mapIndexed { index, band ->
                    val x =
                        if (bandCount == 1) {
                            width / 2f
                        } else {
                            (index.toFloat() / (bandCount - 1)) * width
                        }
                    val normalized =
                        (
                            (band.gainDb - MIN_EQUALIZER_GAIN_DB) /
                                (MAX_EQUALIZER_GAIN_DB - MIN_EQUALIZER_GAIN_DB)
                        ).coerceIn(0f, 1f)
                    val y = bottomY - normalized * (bottomY - topY)
                    Offset(x, y)
                }

            val path = Path()
            path.moveTo(points.first().x, points.first().y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val midX = (p0.x + p1.x) / 2f
                path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }

            // Translucent gradient fill beneath curve
            val fillPath =
                Path().apply {
                    addPath(path)
                    lineTo(points.last().x, bottomY)
                    lineTo(points.first().x, bottomY)
                    close()
                }

            drawPath(
                path = fillPath,
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                curveColor.copy(alpha = if (enabled) 0.25f else 0.08f),
                                Color.Transparent,
                            ),
                        startY = topY,
                        endY = bottomY,
                    ),
            )

            // Smooth curve stroke
            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // Dots for each band
            for (pt in points) {
                drawCircle(
                    color = curveColor,
                    radius = 3.5.dp.toPx(),
                    center = pt,
                )
            }
        }
    }
}

@Composable
private fun EqualizerFadersRow(
    bands: List<EqualizerBand>,
    enabled: Boolean,
    onGainChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val isScrollable = bands.size > 5
    var prevBandCount by remember { mutableIntStateOf(bands.size) }

    LaunchedEffect(bands.size) {
        if (bands.size > prevBandCount && scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
        prevBandCount = bands.size
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            // Card Header with band count and horizontal scroll indicator cue
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bandas individuales",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isScrollable) {
                    Text(
                        text = "Desliza horizontalmente ↔",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val rowModifier =
                if (isScrollable) {
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                }

            Row(
                modifier = rowModifier,
                horizontalArrangement =
                    if (isScrollable) {
                        Arrangement.spacedBy(10.dp)
                    } else {
                        Arrangement.SpaceEvenly
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                bands.forEach { band ->
                    EqualizerVerticalFader(
                        band = band,
                        enabled = enabled,
                        onGainChange = { gain -> onGainChange(band.index, gain) },
                        modifier =
                            if (isScrollable) {
                                Modifier.width(62.dp)
                            } else {
                                Modifier.weight(1f)
                            },
                    )
                }
            }

            if (isScrollable) {
                Spacer(modifier = Modifier.height(10.dp))
                // Subtle horizontal scroll indicator at bottom of card
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val maxScroll = scrollState.maxValue.coerceAtLeast(1)
                    val scrollFraction = (scrollState.value.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
                    val trackWidth = 72.dp
                    val thumbWidth = 24.dp

                    Box(
                        modifier =
                            Modifier
                                .width(trackWidth)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(thumbWidth)
                                    .height(4.dp)
                                    .offset {
                                        val maxOffsetPx = (trackWidth - thumbWidth).toPx()
                                        IntOffset((scrollFraction * maxOffsetPx).roundToInt(), 0)
                                    }.clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerVerticalFader(
    band: EqualizerBand,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackHeight = 170.dp
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val paddingPx = with(density) { 10.dp.toPx() }
    val viewConfig = LocalViewConfiguration.current
    val touchSlop = viewConfig.touchSlop
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var isDraggingVertical by remember { mutableStateOf(false) }

    val gainAnimated by animateFloatAsState(
        targetValue = band.gainDb,
        label = "gainAnimated",
    )

    Column(
        modifier =
            modifier
                .alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Gain readout at top
        val gainText =
            when {
                band.gainDb > 0.05f -> "+${String.format(Locale.US, "%.1f", band.gainDb)}"
                band.gainDb < -0.05f -> String.format(Locale.US, "%.1f", band.gainDb)
                else -> "0.0"
            }

        Text(
            text = gainText,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isDraggingVertical) FontWeight.ExtraBold else FontWeight.Bold,
                    fontSize = 11.sp,
                ),
            color =
                when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    isDraggingVertical -> MaterialTheme.colorScheme.primary
                    band.gainDb > 0.05f -> MaterialTheme.colorScheme.primary
                    band.gainDb < -0.05f -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Vertical Fader Track
        BoxWithConstraints(
            modifier =
                Modifier
                    .height(trackHeight)
                    .width(44.dp)
                    .pointerInput(enabled, trackHeightPx) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downX = down.position.x
                            val downY = down.position.y
                            val downTime = System.currentTimeMillis()
                            var isVerticalDrag = false
                            var isHorizontalDrag = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // Pointer released (Up)
                                    if (!isVerticalDrag && !isHorizontalDrag) {
                                        val now = System.currentTimeMillis()
                                        if (now - downTime < 500L) {
                                            if (now - lastTapTimeMs < 300L) {
                                                // Double-tap resets this band to 0 dB
                                                onGainChange(0f)
                                                lastTapTimeMs = 0L
                                            } else {
                                                lastTapTimeMs = now
                                                updateGainFromY(change.position.y, trackHeightPx, paddingPx, onGainChange)
                                            }
                                            change.consume()
                                        }
                                    }
                                    break
                                }

                                val dx = change.position.x - downX
                                val dy = change.position.y - downY

                                if (!isVerticalDrag && !isHorizontalDrag) {
                                    if (abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                                        // Horizontal swipe across bands: let parent horizontalScroll handle it!
                                        isHorizontalDrag = true
                                        // Do not consume. Exit gesture loop immediately.
                                        break
                                    } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                                        // Vertical adjustment: this fader takes control!
                                        isVerticalDrag = true
                                        isDraggingVertical = true
                                        updateGainFromY(change.position.y, trackHeightPx, paddingPx, onGainChange)
                                        change.consume()
                                    }
                                } else if (isVerticalDrag) {
                                    updateGainFromY(change.position.y, trackHeightPx, paddingPx, onGainChange)
                                    change.consume()
                                }
                            }
                            isDraggingVertical = false
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            val totalHeight = maxHeight
            val midY = totalHeight / 2f

            // Background track channel
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .height(totalHeight - 16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            )

            // Center 0 dB notch indicator
            Box(
                modifier =
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
            )

            // Active bar from center (0 dB) to current thumb position
            val normalizedFraction =
                (
                    (gainAnimated - MIN_EQUALIZER_GAIN_DB) /
                        (MAX_EQUALIZER_GAIN_DB - MIN_EQUALIZER_GAIN_DB)
                ).coerceIn(0f, 1f)
            // Invert because Y = 0 is top
            val thumbYOffset = (totalHeight - 20.dp) * (1f - normalizedFraction)

            val barHeight = abs(gainAnimated / MAX_EQUALIZER_GAIN_DB) * (totalHeight.value / 2f - 10f)
            val barYOffset =
                if (gainAnimated >= 0) {
                    midY - barHeight.dp
                } else {
                    midY
                }

            if (barHeight > 1f) {
                Box(
                    modifier =
                        Modifier
                            .width(6.dp)
                            .height(barHeight.dp)
                            .offset { IntOffset(0, (barYOffset - midY).roundToPx()) }
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (gainAnimated >= 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            ),
                )
            }

            // Draggable Thumb Knob
            val thumbSize = if (isDraggingVertical) 26.dp else 24.dp
            val thumbElevation = if (isDraggingVertical) 6.dp else 3.dp

            Surface(
                shape = CircleShape,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                shadowElevation = thumbElevation,
                modifier =
                    Modifier
                        .size(thumbSize)
                        .offset {
                            val centerOffset = (thumbYOffset - (totalHeight / 2f) + 10.dp).roundToPx()
                            IntOffset(0, centerOffset)
                        }.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier =
                            Modifier
                                .width(8.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(MaterialTheme.colorScheme.onPrimary),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Frequency Label at bottom
        Text(
            text = formatEqualizerFrequency(band.centerFrequencyHz),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun updateGainFromY(
    y: Float,
    trackHeightPx: Float,
    paddingPx: Float,
    onGainChange: (Float) -> Unit,
) {
    if (trackHeightPx <= 0f) return
    val usableHeightPx = (trackHeightPx - 2f * paddingPx).coerceAtLeast(1f)
    val clampedY = (y - paddingPx).coerceIn(0f, usableHeightPx)
    val fractionFromTop = (clampedY / usableHeightPx).coerceIn(0f, 1f)
    // 0 = top (+12 dB), 1 = bottom (-12 dB)
    var gain = MAX_EQUALIZER_GAIN_DB - fractionFromTop * (MAX_EQUALIZER_GAIN_DB - MIN_EQUALIZER_GAIN_DB)

    // Magnetic snap to 0 dB when close
    if (abs(gain) < 0.4f) {
        gain = 0f
    } else {
        // Step to 0.5 dB
        gain = (round(gain * 2f) / 2f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
    }
    onGainChange(gain)
}

@Composable
fun EqualizerMiniCurveVisualizer(
    bands: List<EqualizerBand>,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Draw 0 dB baseline
        drawLine(
            color = gridColor,
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )

        if (bands.isEmpty()) return@Canvas

        val range = (MAX_EQUALIZER_GAIN_DB - MIN_EQUALIZER_GAIN_DB)
        val points =
            bands.mapIndexed { index, band ->
                val x =
                    if (bands.size > 1) {
                        (index.toFloat() / (bands.size - 1)) * width
                    } else {
                        width / 2f
                    }
                val gainClamped = band.gainDb.coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
                val normalized = (gainClamped - MIN_EQUALIZER_GAIN_DB) / range
                val y = height - (normalized * height * 0.85f + height * 0.075f)
                Offset(x, y)
            }

        // Build smooth curve path
        val curvePath = Path()
        val fillPath = Path()

        curvePath.moveTo(points.first().x, points.first().y)
        fillPath.moveTo(0f, midY)
        fillPath.lineTo(points.first().x, points.first().y)

        for (i in 0 until points.size - 1) {
            val p0 = points[maxOf(0, i - 1)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[minOf(points.size - 1, i + 2)]

            val cp1x = p1.x + (p2.x - p0.x) / 6f
            val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f
            val cp2y = p2.y - (p3.y - p1.y) / 6f

            curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }

        fillPath.lineTo(width, midY)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            primaryColor.copy(alpha = 0.25f),
                            primaryColor.copy(alpha = 0.02f),
                        ),
                    startY = 0f,
                    endY = height,
                ),
        )

        drawPath(
            path = curvePath,
            color = primaryColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun DynamicEqualizerRuleCard(
    rule: DynamicEqualizerRule,
    isActive: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isActive) 0.65f else 0.4f),
        shape = RoundedCornerShape(16.dp),
        border =
            if (isActive) {
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Target icon, badge, title and delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    val (typeIcon, typeBg, typeFg) =
                        when (rule.targetType) {
                            EqualizerTargetType.SONG -> {
                                Triple(
                                    Icons.Default.MusicNote,
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.primary,
                                )
                            }

                            EqualizerTargetType.ALBUM -> {
                                Triple(
                                    Icons.Default.Album,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.secondary,
                                )
                            }

                            EqualizerTargetType.ARTIST -> {
                                Triple(
                                    Icons.Default.Person,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            }

                            EqualizerTargetType.CUSTOM_PRESET -> {
                                Triple(
                                    Icons.Default.Tune,
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                    Surface(
                        shape = CircleShape,
                        color = typeBg,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = typeIcon,
                                contentDescription = null,
                                tint = typeFg,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = rule.targetName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                ) {
                                    Text(
                                        text = "Activo",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        if (rule.targetArtist.isNotBlank()) {
                            Text(
                                text = rule.targetArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar perfil",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pills: Type, Band Count, Preset Name
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                ) {
                    Text(
                        text = rule.targetType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                ) {
                    Text(
                        text = "${rule.bandCount} bandas",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                ) {
                    Text(
                        text = rule.presetName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mini Curve preview
            EqualizerMiniCurveVisualizer(
                bands = rule.bands,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Band Summary chips (showing summary depending on the band used)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(rule.bands) { band ->
                    val sign = if (band.gainDb > 0f) "+" else ""
                    val formattedGain = String.format(Locale.US, "%.1f", band.gainDb)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color =
                            if (band.gainDb != 0f) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            },
                    ) {
                        Text(
                            text = "${formatEqualizerFrequency(band.centerFrequencyHz)}: $sign$formattedGain dB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color =
                                if (band.gainDb != 0f) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer action: Load into controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onLoad,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cargar en ecualizador", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun SaveCustomPresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Guardar preset personalizado",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Asigna un nombre a la configuración actual de bandas y ganancias:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del preset") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim())
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}
