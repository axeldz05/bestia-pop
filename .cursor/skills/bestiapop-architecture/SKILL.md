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
| Preferencias | DataStore (`ThemePreferencesRepository`, `ListenBrainzPreferencesRepository`, `ActiveDownloadsStore`) |
| Red / catálogo | OkHttp + `MetadataFetcher` (iTunes/Deezer) + `YouTubeExtractor` + `ListenBrainzClient` |
| Sync WiFi | Ktor CIO embebido (`WebServerService`) |
| Imágenes | Coil |

minSdk 26 · target/compileSdk 35 · Java/Kotlin 17 · KSP para Room.

## Capas y paquetes

```
ui/          Compose screens, components, theme, ViewModel, ui.state
domain/      use cases + radio + IMusicRepository (puerto)
data/        MusicRepository, Room, network, stream, preferences, models, util
service/     MusicService (playback), WebServerService (WiFi sync)
```

**Regla de dependencia:** `ui` → `domain` → (interfaces). `data` implementa `domain.repository`. `ui` puede usar `data.model` y servicios Media3; la lógica de negocio nueva va en `domain/usecase` o `domain/radio`, no en pantallas.

## Flujo de datos (happy path)

1. Room / MediaStore → `MusicRepository` → `Flow<List<Song>>`
2. ViewModel combina flows + search/sort → `songsState` / `albumsState` / `artistsState`
3. Screens Compose observan StateFlows y llaman métodos del ViewModel
4. Reproducción: ViewModel cola `List<PlayableItem>` (`Local` | `Remote`) → `MediaController` → `MusicService` (ExoPlayer)
5. Stream remoto: `StreamResolver` → `YouTubeExtractor.extractAudioStreamDetailed` → MediaItem HTTPS + `StreamPlaybackTag` (UA) → ExoPlayer
6. Radio: `RadioEngine` (local → LB → CF) → `playPlayableCollection` / refill de cola; remotos reusan stream
7. Para Ti: `MatchListenBrainzTracksUseCase` → `MatchedLbPlaylist.toPlayableItems` → `playPlayableCollection`; CF: `FetchAndMatchCfRecommendationsUseCase` → `MatchedCfRecommendations.toPlayableItems`; opcional `saveWhileListening` → download background; import Room vía `ImportListenBrainzPlaylistUseCase` + `LB_IMPORT`
8. Descarga online: siempre vía `runTrackedDownload` → cola `activeDownloads` (tab Descargas) → `DownloadAudioTrackUseCase` → Room + storage

## Navegación UI

`MainScreen` bottom nav:
0. Biblioteca (`LibraryScreen` + subviews album/artist/song)
1. Playlists (`PlaylistsScreen`)
2. Descargas (`DownloadsScreen`)
3. WiFi Sync (`WebServerScreen`)
4. Ajustes (`SettingsScreen` / temas / ListenBrainz)

Overlay: `BottomPlayerBar` → `NowPlayingScreen`; cola en `QueueScreen`.
Mini player se rehidrata desde `MediaController` (sesión viva) o `PlaybackSessionStore` / seed idle (ver features §10b).

## Principios estructurales (invariantes)

1. **Todo es colección** — play/shuffle/enqueue pasan por pipeline unificado (`PlayCollectionUseCase` + ViewModel); cola interna es `PlayableItem`.
2. **Catálogo ≠ audio** — metadatos de iTunes/Deezer; bytes de audio vía YouTube (re-extraer URL antes de descargar/stream por CDN 403).
3. **Álbum vs playlist en portadas** — álbum propaga a canciones; portada de playlist es entidad propia.
4. **Portadas locales** — copiar a `context.filesDir` (no depender de content URIs temporales).
5. **Remoto efímero** — `PlayableItem.Remote` + `ResolvedStream` en memoria; nunca persistir URLs CDN en Room.
6. **Radio** — sesión con seed + providers (`LocalMetadataRadio` / `ListenBrainzRadio` / `CfRecommendationsRadio`); refill de cola; no pipeline paralelo.
7. **Para Ti mixto** — Discover + CF Recomendados reproducen Local+Remote; “Guardar al escuchar” / import LB no bloquean ni persisten URLs CDN; faltantes de import usan `activeDownloads` (`LB_IMPORT` + `targetPlaylistId`).

## Servicios Android

| Servicio | Rol |
|----------|-----|
| `MusicService` | `MediaLibraryService` + ExoPlayer; `UserAgentMediaSourceFactory` lee UA de `StreamPlaybackTag` |
| `WebServerService` | Servidor Ktor local para sync/upload por WiFi |

## Base de datos

Entidades: `SongEntity`, `PlaylistEntity`, `PlaylistSongCrossRef`, `PlaylistPendingTrackEntity`, `PendingListenEntity`.
Índice único Room: `songs.uriString`. Deduplicación lógica por `matchKey(artist, title)` en filtros de scan / download conflict (`Music/BestiaPop` app-managed). URIs app-owned: path absoluto (`SongPathNormalizer`). One-shot migrator archivado en branch `archive/library-dedup-v1-migrator`.
Migraciones Room: 1→2 (dedupe + unique index), 2→3 (playlist description/coverUri), 3→4 (pending_listens), 4→5 (playlist_pending_tracks).

## Relacionado

- Features esenciales → skill `bestiapop-features`
- Mapa de archivos/funciones → skill `bestiapop-implementation-map`
- Protocolo de actualización de docs → skill `bestiapop-living-docs`
- Principios históricos resumidos → `.agents/AGENTS.md` (mantener alineado)
