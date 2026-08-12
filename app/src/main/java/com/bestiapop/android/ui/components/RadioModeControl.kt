package com.bestiapop.android.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bestiapop.android.domain.radio.RadioMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioModeControl(
    radioActive: Boolean,
    radioLoading: Boolean,
    onStartPreferred: () -> Unit,
    onStartMode: (RadioMode) -> Unit,
    onStop: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = !radioLoading,
                    onClick = onStartPreferred,
                    onLongClick = { menuExpanded = true }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (radioLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = "Radio (mantener para modos)",
                    modifier = Modifier.size(28.dp),
                    tint = if (radioActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    }
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            RadioMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.menuLabel()) },
                    onClick = {
                        menuExpanded = false
                        onStartMode(mode)
                    }
                )
            }
            if (radioActive) {
                DropdownMenuItem(
                    text = { Text("Detener radio") },
                    onClick = {
                        menuExpanded = false
                        onStop()
                    }
                )
            }
        }
    }
}

private fun RadioMode.menuLabel(): String = when (this) {
    RadioMode.KNOWN -> "Solo conocidos"
    RadioMode.NEW -> "Solo nuevos"
    RadioMode.BOTH -> "Ambos"
}
