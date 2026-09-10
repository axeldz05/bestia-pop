package com.bestiapop.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesUnificationTest {

    @Test
    fun playlistMessages_formatsPluralizedAndDynamicStrings() {
        assertEquals("Añadir a Favoritos", PlaylistMessages.addToPlaylistNamed("Favoritos"))
        assertEquals("¿Estás seguro de que deseas eliminar 'Rock'? Esta acción no se puede deshacer.", PlaylistMessages.deletePlaylistConfirm("Rock"))

        assertEquals("Añadir a playlist", PlaylistMessages.addSongsCount(1))
        assertEquals("Añadir 5 canciones a playlist", PlaylistMessages.addSongsCount(5))

        assertEquals("Añadir", PlaylistMessages.addSongsButtonLabel(1))
        assertEquals("Añadir (3)", PlaylistMessages.addSongsButtonLabel(3))

        assertEquals("Nueva Playlist", PlaylistMessages.newPlaylist)
        assertEquals("Crear playlist", PlaylistMessages.createPlaylist)
        assertEquals("No se pudo crear la playlist", PlaylistMessages.createFailed)
        assertEquals("No se pudo abrir la playlist", PlaylistMessages.openFailed)
    }

    @Test
    fun downloadMessages_formatsAccurately() {
        assertEquals("¡Song agregada a la biblioteca!", DownloadMessages.completed("Song"))
        assertEquals("¡Canción agregada con éxito!", DownloadMessages.completed(null))
        assertEquals("Preparando descargas…", DownloadMessages.preparingDownloads)
        assertEquals("En biblioteca", DownloadMessages.inLibrary)
        assertEquals("Pendiente de descarga", DownloadMessages.pendingDownloadBadge)
        assertEquals("2 descargadas · 3 pendientes", DownloadMessages.playlistCounts(2, 3))
    }
}
