package com.bestiapop.android.data.model

/**
 * Typed download progress. Single source for phase percent + user-facing copy.
 * [DownloadMessages] holds strings shared with toasts/UI labels.
 */
sealed class DownloadPhase {
    abstract val percent: Int
    abstract val userMessage: String

    data object Searching : DownloadPhase() {
        override val percent: Int get() = 40
        override val userMessage: String get() = DownloadMessages.searching
    }

    data class Downloading(
        val title: String,
    ) : DownloadPhase() {
        override val percent: Int get() = 75
        override val userMessage: String get() = DownloadMessages.downloading(title)
    }

    data object FetchingMetadata : DownloadPhase() {
        override val percent: Int get() = 50
        override val userMessage: String get() = DownloadMessages.fetchingMetadata
    }

    data object Saving : DownloadPhase() {
        override val percent: Int get() = 90
        override val userMessage: String get() = DownloadMessages.saving
    }

    data object Completed : DownloadPhase() {
        override val percent: Int get() = 100
        override val userMessage: String get() = DownloadMessages.completed
    }

    data object Overwritten : DownloadPhase() {
        override val percent: Int get() = 100
        override val userMessage: String get() = DownloadMessages.overwritten
    }
}

object DownloadMessages {
    val searching get() = "Buscando audio de alta calidad en YouTube..."

    fun downloading(title: String) = "Descargando audio ($title)..."

    fun downloadingQuoted(label: String) = "Descargando «$label»…"

    fun downloadingCount(count: Int) = "Descargando $count canciones…"

    val downloadingEllipsis get() = "Descargando…"
    val downloadingAudio get() = "Descargando audio..."
    val fetchingMetadata get() = "Obteniendo información del álbum y portada..."
    val saving get() = "Guardando en la biblioteca..."
    val completed get() = "¡Canción agregada con éxito!"
    val overwritten get() = "¡Canción sobrescrita con éxito!"
    val downloadedShort get() = "Descargada"
    val queued get() = "En cola"
    val queuedEllipsis get() = "En cola…"
    val starting get() = "Iniciando descarga..."
    val conflictInLibrary get() = "Conflicto: ya está en la biblioteca"
    val inLibrary get() = "En biblioteca"
    val savedInLibrary get() = "¡Guardado en biblioteca!"
    val interrupted get() = "Interrumpida — se reanudará al abrir la app"
    val missingArtistOrTitle get() = "No se puede descargar: faltan artista o título"

    fun songSaved(title: String) = "«$title» guardada en biblioteca"

    fun songAdded(title: String) = "¡$title agregada a la biblioteca!"

    fun songAlready(title: String) = "«$title» ya está en la biblioteca"

    fun batchProcessed(
        done: Int,
        total: Int,
    ) = "¡$done de $total canciones procesadas!"

    fun failedQuoted(label: String) = "Falló «$label»"

    fun downloadsFailed(count: Int) = "$count descargas fallaron"

    fun downloadFailed(detail: String) = "Falló la descarga: $detail"

    val conflictPending get() = "Ya existe en la biblioteca — decidí qué hacer"
    val alreadyQueued get() = "Ya está en cola — ver Descargas"
    val downloadQueued get() = "Descarga en cola — ver Descargas"

    fun downloadsQueued(count: Int) = "$count descargas en cola — ver Descargas"

    val blockedOnMetered
        get() = "Descarga bloqueada: estás en datos móviles. Activá «Descargar con datos móviles» en Ajustes → Descargas."

    val noPendingTracks get() = "No hay canciones pendientes"

    fun playlistSaved(
        matchedCount: Int,
        pending: Int = 0,
    ): String =
        if (pending > 0) {
            "Playlist guardada ($matchedCount en lib · $pending pendientes)"
        } else {
            "Playlist guardada ($matchedCount canciones)"
        }

    val radioNeedsSeed get() = "Necesitás una canción con artista y título para Radio"
    val albumsMerged get() = "Álbumes unidos"
    val albumSaved get() = "Álbum guardado en la biblioteca"
    val selectAtLeastOneSong get() = "Seleccioná al menos una canción"
    val alreadyDownloadedSong get() = "Canción ya descargada en la biblioteca"
    val alreadyDownloadedAlbum get() = "El álbum ya está descargado en tu biblioteca"
    val cancelDownload get() = "Cancelar descarga"
    val downloadAction get() = "Descargar"
    val downloadNow get() = "Descargar ahora"
    val downloadAndAdd get() = "Descargar MP3 y Agregar"
    val preparingDownloads get() = "Preparando descargas…"
    val pendingDownloadBadge get() = "Pendiente de descarga"
    val searchAnother get() = "Buscar otro"
    val dismiss get() = "Descartar"
    val retry get() = "Reintentar"

    fun playlistCounts(
        downloaded: Int,
        pending: Int,
    ) = "$downloaded descargadas · $pending pendientes"

    fun completed(title: String? = null): String = if (title.isNullOrBlank()) completed else songAdded(title)
}
