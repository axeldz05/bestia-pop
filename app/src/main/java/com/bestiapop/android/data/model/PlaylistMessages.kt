package com.bestiapop.android.data.model

/**
 * Centralized user-facing messages, actions, and dialog strings for Playlists.
 */
object PlaylistMessages {
    const val addToPlaylist = "Añadir a playlist"
    fun addToPlaylistNamed(name: String) = "Añadir a $name"
    fun addSongsCount(count: Int) = if (count > 1) "Añadir $count canciones a playlist" else addToPlaylist
    fun addSongsButtonLabel(count: Int) = if (count > 1) "Añadir ($count)" else "Añadir"
    const val newPlaylist = "Nueva Playlist"
    const val createPlaylist = "Crear playlist"
    const val createAndOpen = "Crear y entrar"
    const val createNewPlaylistButton = "+ Crear nueva playlist"
    const val editPlaylist = "Editar Playlist"
    const val deletePlaylist = "Eliminar Playlist"
    fun deletePlaylistConfirm(name: String) = "¿Estás seguro de que deseas eliminar '$name'? Esta acción no se puede deshacer."
    const val removeFromPlaylist = "Quitar de la playlist"
    const val emptyPlaylist = "Esta playlist está vacía"
    const val noPlaylistsYet = "No tenés playlists creadas todavía."
    const val createFailed = "No se pudo crear la playlist"
    const val openFailed = "No se pudo abrir la playlist"
    const val playlistNameLabel = "Nombre de la playlist *"
}
