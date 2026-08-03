---
name: bestiapop-architecture
description: >-
  Arquitectura actual de BestiaPop (sofoapps): capas UI/domain/data/service,
  stack Android/Kotlin/Compose/Media3/Room, flujo de datos y límites entre
  paquetes. Usar al diseñar cambios, refactorizar, añadir features o cuando el
  usuario pregunte por arquitectura, estructura del proyecto o dónde poner código.
---

# BestiaPop — Arquitectura

App Android de música local + descarga online. Package root: `com.bestiapop.android`.
Módulo único Gradle: `:app`. Nombre del proyecto: **BestiaPop**.

## Stack

| Capa | Tecnología |
|------|------------|
| UI | Jetpack Compose + Material3 |
| Estado UI | `MusicPlayerViewModel` (AndroidViewModel) + StateFlow |
| Reproducción | Media3 ExoPlayer + `MediaLibraryService` (`MusicService`) |
| Persistencia | Room (`bestiapop_music_db`, v3) |
| Preferencias | DataStore (`ThemePreferencesRepository`) |
| Red / catálogo | OkHttp + `MetadataFetcher` (iTunes/Deezer) + `YouTubeExtractor` |
| Sync WiFi | Ktor CIO embebido (`WebServerService`) |
| Imágenes | Coil |

minSdk 26 · target/compileSdk 35 · Java/Kotlin 17 · KSP para Room.

## Capas y paquetes

```
ui/          Compose screens, components, theme, ViewModel, ui.state
domain/      use cases + IMusicRepository (puerto)
data/        MusicRepository, Room, network, preferences, models, util
service/     MusicService (playback), WebServerService (WiFi sync)
```

**Regla de dependencia:** `ui` → `domain` → (interfaces). `data` implementa `domain.repository`. `ui` puede usar `data.model` y servicios Media3; la lógica de negocio nueva va en `domain/usecase`, no en pantallas.

## Flujo de datos (happy path)

1. Room / MediaStore → `MusicRepository` → `Flow<List<Song>>`
2. ViewModel combina flows + search/sort → `songsState` / `albumsState` / `artistsState`
3. Screens Compose observan StateFlows y llaman métodos del ViewModel
4. Reproducción: ViewModel → `MediaController` → `MusicService` (ExoPlayer)
5. Descarga online: catálogo (`MetadataFetcher` / `YouTubeExtractor`) → `DownloadAudioTrackUseCase` → `repository.downloadAndSaveOnlineTrack` → Room + storage

## Navegación UI

`MainScreen` bottom nav:
0. Biblioteca (`LibraryScreen` + subviews album/artist/song)
1. Playlists (`PlaylistsScreen`)
2. WiFi Sync (`WebServerScreen`)
3. Temas (`ThemeSettingsScreen`)

Overlay: `BottomPlayerBar` → `NowPlayingScreen`; cola en `QueueScreen`.

## Principios estructurales (invariantes)

1. **Todo es colección** — play/shuffle/enqueue pasan por pipeline unificado (`PlayCollectionUseCase` + ViewModel).
2. **Catálogo ≠ audio** — metadatos de iTunes/Deezer; bytes de audio vía YouTube (re-extraer URL antes de descargar por CDN 403).
3. **Álbum vs playlist en portadas** — álbum propaga a canciones; portada de playlist es entidad propia.
4. **Portadas locales** — copiar a `context.filesDir` (no depender de content URIs temporales).

## Servicios Android

| Servicio | Rol |
|----------|-----|
| `MusicService` | `MediaLibraryService` + ExoPlayer foreground playback |
| `WebServerService` | Servidor Ktor local para sync/upload por WiFi |

## Base de datos

Entidades: `SongEntity`, `PlaylistEntity`, `PlaylistSongCrossRef`.
Índice único: `songs.uriString`. Migraciones: 1→2 (dedupe + unique index), 2→3 (playlist description/coverUri).

## Relacionado

- Features esenciales → skill `bestiapop-features`
- Mapa de archivos/funciones → skill `bestiapop-implementation-map`
- Protocolo de actualización de docs → skill `bestiapop-living-docs`
- Principios históricos resumidos → `.agents/AGENTS.md` (mantener alineado)
