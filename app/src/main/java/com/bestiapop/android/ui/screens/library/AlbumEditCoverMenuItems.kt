package com.bestiapop.android.ui.screens.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Shared Edit / Change-cover items for album overflow menus. */
@Composable
fun AlbumEditCoverMenuItems(
    onEditAlbum: () -> Unit,
    onChangeCover: () -> Unit
) {
    DropdownMenuItem(
        text = { Text("Editar álbum") },
        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
        onClick = onEditAlbum
    )
    DropdownMenuItem(
        text = { Text("Cambiar portada") },
        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
        onClick = onChangeCover
    )
}
