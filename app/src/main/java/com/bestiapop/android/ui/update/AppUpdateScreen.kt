package com.bestiapop.android.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.update.AppRelease
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ajustes → Actualización: installed version, repo link, notes of the running build and, once a
 * check finds newer releases, every change published since (install goes through [AppUpdateDialogs]).
 */
@Composable
fun AppUpdateScreen(viewModel: AppUpdateViewModel) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsState()
    val updateState by viewModel.state.collectAsState()
    val busy = notes.loading || updateState is AppUpdateUiState.Downloading

    LaunchedEffect(Unit) { viewModel.refreshReleases() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        UpdateCard {
            Text(
                text = "Versión instalada",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "versionCode ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            viewModel.repositoryUrl?.let { url ->
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { openUrl(context, url) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver el repositorio")
                }
                Text(
                    text = url.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.refreshReleases(force = true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (notes.loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (notes.loading) "Buscando…" else "Buscar actualización")
        }

        notes.error?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (notes.newer.isEmpty() && notes.checked && notes.error == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ya tenés la última versión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (notes.newer.isNotEmpty()) {
            val latest = notes.newer.first()
            val target = notes.newer.firstOrNull { it.isInstallable }
            Spacer(modifier = Modifier.height(16.dp))
            UpdateCard(highlighted = true) {
                Text(
                    text = if (notes.newer.size == 1) {
                        "Nueva versión ${latest.versionName}"
                    } else {
                        "${notes.newer.size} versiones nuevas desde la tuya"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (target != null) {
                    Button(
                        onClick = { viewModel.startUpdate(target) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (updateState is AppUpdateUiState.Downloading) {
                                "Descargando…"
                            } else {
                                "Actualizar a ${target.versionName}"
                            }
                        )
                    }
                } else {
                    Text(
                        text = "El release no trae APK: descargalo desde GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle("Qué cambia")
            notes.newer.forEach { release ->
                ReleaseNotesCard(
                    release = release,
                    onOpen = release.htmlUrl?.let { url -> { openUrl(context, url) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("Novedades de esta versión")
        UpdateCard {
            val currentNotes = notes.currentNotes?.trim().orEmpty()
            Text(
                text = currentNotes.ifBlank {
                    if (notes.loading) {
                        "Buscando las notas en GitHub…"
                    } else {
                        "Todavía no hay notas publicadas para esta versión."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (currentNotes.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun ReleaseNotesCard(
    release: AppRelease,
    onOpen: (() -> Unit)?
) {
    UpdateCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = release.versionName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                releaseSubtitle(release)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onOpen != null) {
                OutlinedButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Ver en GitHub",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = release.notes?.trim()?.ifBlank { null } ?: "Sin notas publicadas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun UpdateCard(
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

private fun releaseSubtitle(release: AppRelease): String? {
    val date = release.publishedAtMs?.let {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it))
    }
    val code = release.versionCode?.let { "versionCode $it" }
    return listOfNotNull(date, code).joinToString(" · ").ifBlank { null }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay navegador para abrir el link", Toast.LENGTH_SHORT).show()
    }
}
