package com.bestiapop.android.data.model

/**
 * Centralized user-facing messages, actions, and dialog strings for Playlists.
 */
object PlaylistMessages {
    val addToPlaylist get() = "Añadir a playlist"

    fun addToPlaylistNamed(name: String) = "Añadir a $name"

    fun addSongsCount(count: Int) = if (count > 1) "Añadir $count canciones a playlist" else addToPlaylist

    fun addSongsButtonLabel(count: Int) = if (count > 1) "Añadir ($count)" else "Añadir"

    val newPlaylist get() = "Nueva Playlist"
    val createPlaylist get() = "Crear playlist"
    val createAndOpen get() = "Crear y entrar"
    val createNewPlaylistButton get() = "+ Crear nueva playlist"
    val editPlaylist get() = "Editar Playlist"
    val deletePlaylist get() = "Eliminar Playlist"

    fun deletePlaylistConfirm(name: String) = "¿Estás seguro de que deseas eliminar '$name'? Esta acción no se puede deshacer."

    val removeFromPlaylist get() = "Quitar de la playlist"
    val emptyPlaylist get() = "Esta playlist está vacía"
    val noPlaylistsYet get() = "No tenés playlists creadas todavía."
    val createFailed get() = "No se pudo crear la playlist"
    val openFailed get() = "No se pudo abrir la playlist"
    val playlistNameLabel get() = "Nombre de la playlist *"
}
