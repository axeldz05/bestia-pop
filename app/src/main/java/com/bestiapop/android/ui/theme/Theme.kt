package com.bestiapop.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bestiapop.android.data.model.CustomTheme
import com.bestiapop.android.ui.theme.ThemePresets.toMaterialColorScheme

@Composable
fun BestiaPopTheme(
    customTheme: CustomTheme = ThemePresets.DynamicSong,
    content: @Composable () -> Unit
) {
    val targetScheme = customTheme.toMaterialColorScheme()

    val animSpec = tween<Color>(350)

    val animatedColorScheme = targetScheme.copy(
        primary = animateColorAsState(targetScheme.primary, animSpec, label = "primary").value,
        onPrimary = animateColorAsState(targetScheme.onPrimary, animSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(targetScheme.primaryContainer, animSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetScheme.onPrimaryContainer, animSpec, label = "onPrimaryContainer").value,
        secondary = animateColorAsState(targetScheme.secondary, animSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetScheme.onSecondary, animSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetScheme.secondaryContainer, animSpec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(targetScheme.onSecondaryContainer, animSpec, label = "onSecondaryContainer").value,
        tertiary = animateColorAsState(targetScheme.tertiary, animSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(targetScheme.onTertiary, animSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(targetScheme.tertiaryContainer, animSpec, label = "tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(targetScheme.onTertiaryContainer, animSpec, label = "onTertiaryContainer").value,
        background = animateColorAsState(targetScheme.background, animSpec, label = "bg").value,
        onBackground = animateColorAsState(targetScheme.onBackground, animSpec, label = "onBg").value,
        surface = animateColorAsState(targetScheme.surface, animSpec, label = "surf").value,
        onSurface = animateColorAsState(targetScheme.onSurface, animSpec, label = "onSurf").value,
        surfaceVariant = animateColorAsState(targetScheme.surfaceVariant, animSpec, label = "surfVar").value,
        onSurfaceVariant = animateColorAsState(targetScheme.onSurfaceVariant, animSpec, label = "onSurfVar").value,
        surfaceContainerLowest = animateColorAsState(targetScheme.surfaceContainerLowest, animSpec, label = "surfLowest").value,
        surfaceContainerLow = animateColorAsState(targetScheme.surfaceContainerLow, animSpec, label = "surfLow").value,
        surfaceContainer = animateColorAsState(targetScheme.surfaceContainer, animSpec, label = "surfContainer").value,
        surfaceContainerHigh = animateColorAsState(targetScheme.surfaceContainerHigh, animSpec, label = "surfHigh").value,
        surfaceContainerHighest = animateColorAsState(targetScheme.surfaceContainerHighest, animSpec, label = "surfHighest").value,
        outline = animateColorAsState(targetScheme.outline, animSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetScheme.outlineVariant, animSpec, label = "outlineVar").value
    )

    MaterialTheme(
        colorScheme = animatedColorScheme,
        content = content
    )
}
