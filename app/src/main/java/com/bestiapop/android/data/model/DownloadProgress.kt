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

    data class Downloading(val title: String) : DownloadPhase() {
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
    const val searching = "Buscando audio de alta calidad en YouTube..."
    fun downloading(title: String) = "Descargando audio ($title)..."
    fun downloadingQuoted(label: String) = "Descargando «$label»…"
    fun downloadingCount(count: Int) = "Descargando $count canciones…"
    const val downloadingEllipsis = "Descargando…"
    const val downloadingAudio = "Descargando audio..."
    const val fetchingMetadata = "Obteniendo información del álbum y portada..."
    const val saving = "Guardando en la biblioteca..."
    const val completed = "¡Canción agregada con éxito!"
    const val overwritten = "¡Canción sobrescrita con éxito!"
    const val downloadedShort = "Descargada"
    const val queued = "En cola"
    const val queuedEllipsis = "En cola…"
    const val starting = "Iniciando descarga..."
    const val conflictInLibrary = "Conflicto: ya está en la biblioteca"
    const val inLibrary = "En biblioteca"
    const val savedInLibrary = "¡Guardado en biblioteca!"
    const val interrupted = "Interrumpida — tocá Reintentar"
    const val missingArtistOrTitle = "No se puede descargar: faltan artista o título"

    fun songSaved(title: String) = "«$title» guardada en biblioteca"
    fun songAdded(title: String) = "¡$title agregada a la biblioteca!"
    fun songAlready(title: String) = "«$title» ya está en la biblioteca"
    fun batchProcessed(done: Int, total: Int) = "¡$done de $total canciones procesadas!"
    fun failedQuoted(label: String) = "Falló «$label»"
    fun downloadsFailed(count: Int) = "$count descargas fallaron"
    fun downloadFailed(detail: String) = "Falló la descarga: $detail"

    const val conflictPending = "Ya existe en la biblioteca — decidí qué hacer"
    const val alreadyQueued = "Ya está en cola — ver Descargas"
    const val downloadQueued = "Descarga en cola — ver Descargas"
    fun downloadsQueued(count: Int) = "$count descargas en cola — ver Descargas"

    const val blockedOnMetered =
        "Descarga bloqueada: estás en datos móviles. Activá «Descargar con datos móviles» en Ajustes → Descargas."
}
