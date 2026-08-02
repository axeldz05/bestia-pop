package com.bestiapop.android.data.model

enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

data class Song(
    val id: Long = 0,
    val uriString: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val genre: String = "Unknown Genre",
    val durationMs: Long = 0,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val artworkUri: String? = null,
    val lyrics: String? = null,
    val folderPath: String = "",
    val dateAdded: Long = System.currentTimeMillis()
)

data class Album(
    val name: String,
    val artist: String,
    val songCount: Int,
    val artworkUri: String? = null
)

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int
)

data class Playlist(
    val id: Long = 0,
    val name: String,
    val songCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class ColorSchemeData(
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val accent: Long
)

data class CustomTheme(
    val id: String,
    val name: String,
    val colors: ColorSchemeData,
    val isDark: Boolean = true
)
