package com.bestiapop.android.data.model

import androidx.compose.runtime.Immutable

/**
 * Representa una línea de letra formateada para la interfaz con texto principal y secundario.
 * En modo original: [primaryText] es la letra original y [secondaryText] es la pronunciación fonética/simplificada.
 * En modo traducción: [primaryText] es la traducción y [secondaryText] es la letra original.
 */
@Immutable
data class DisplayLyricLine(
    val timeMs: Long? = null,
    val primaryText: String = "",
    val secondaryText: String? = null,
    val formattedTime: String? = null
)
