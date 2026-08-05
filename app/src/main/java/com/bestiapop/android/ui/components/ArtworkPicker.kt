package com.bestiapop.android.ui.components

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberImagePicker(
    onImagePicked: (String) -> Unit
): ManagedActivityResultLauncher<String, Uri?> = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri ->
    uri?.let { onImagePicked(it.toString()) }
}

@Composable
fun ArtworkPickerBlock(
    artworkUri: String?,
    onPick: () -> Unit,
    buttonText: String,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    preview: @Composable (String?) -> Unit = { uri ->
        ArtworkThumbnail(
            artworkUri = uri,
            size = 120.dp,
            cornerRadius = 12.dp
        )
    },
    buttonLeading: @Composable () -> Unit = {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
    },
    trailing: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        preview(artworkUri)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPick) {
                buttonLeading()
                Text(buttonText)
            }
            trailing()
        }
    }
}
