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
    customTheme: CustomTheme = ThemePresets.MidnightDark,
    content: @Composable () -> Unit
) {
    val targetScheme = customTheme.toMaterialColorScheme()

    val animatedColorScheme = targetScheme.copy(
        primary = animateColorAsState(targetScheme.primary, tween(400), label = "primary").value,
        onPrimary = animateColorAsState(targetScheme.onPrimary, tween(400), label = "onPrimary").value,
        secondary = animateColorAsState(targetScheme.secondary, tween(400), label = "secondary").value,
        background = animateColorAsState(targetScheme.background, tween(400), label = "bg").value,
        surface = animateColorAsState(targetScheme.surface, tween(400), label = "surf").value,
        surfaceVariant = animateColorAsState(targetScheme.surfaceVariant, tween(400), label = "surfVar").value,
        onBackground = animateColorAsState(targetScheme.onBackground, tween(400), label = "onBg").value,
        onSurface = animateColorAsState(targetScheme.onSurface, tween(400), label = "onSurf").value
    )

    MaterialTheme(
        colorScheme = animatedColorScheme,
        content = content
    )
}
