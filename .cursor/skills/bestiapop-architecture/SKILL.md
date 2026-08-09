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
| Preferencias | DataStore (`ThemePreferencesRepository`, `ListenBrainzPreferencesRepository`, `PlaybackPreferencesRepository`, `LibraryPreferencesRepository` display+nav, `ActiveDownloadsStore`) |
| Red / catálogo | OkHttp + `MetadataFetcher` (iTunes/Deezer) + `YouTubeExtractor` + `ListenBrainzClient` |
| Sync WiFi | Ktor CIO embebido (`WebServerService`) |
| Imágenes | Coil |
| Crash reporting | Firebase Crashlytics vía `CrashReporter` (`BestiaPopApplication`) |

minSdk 26 · targetSdk 35 · compileSdk 36 · Java/Kotlin 17 · AGP 9.3 + KSP · Room 2.8.

## Capas y paquetes

```
ui/          Compose screens, components, theme, ViewModel, ui.state
domain/      use cases + radio + IMusicRepository (puerto)
data/        MusicRepository, Room, network, stream, preferences, models, util
service/     MusicService (playback), WebServerService (WiFi sync)
```

`BestiaPopApplication` inicializa Crashlytics (colección solo en builds no-debug). Non-fatals con contexto: `data/util/CrashReporter.kt`.

**Regla de dependencia:** `ui` → `domain` → (interfaces). `data` implementa `domain.repository`. `ui` puede usar `data.model` y servicios Media3; la lógica de negocio nueva va en `domain/usecase` o `domain/radio`, no en pantallas.

## Flujo de datos (happy path)

1. Room / MediaStore → `MusicRepository` (`MusicFileStore` para I/O local) → `Flow<List<Song>>`
2. ViewModel combina flows + search/sort → `songsState` / `albumsState` / `artistsState`
3. Screens Compose observan StateFlows y llaman métodos del ViewModel
4. Reproducción: ViewModel cola `List<PlayableItem>` (`Local` | `Remote`) → `MediaController` → `MusicService` (ExoPlayer)
5. Stream remoto: `StreamResolver.resolve` / `resolveQuery` → `YouTubeExtractor.extractAudioStreamDetailed` → MediaItem HTTPS + `StreamPlaybackTag` (UA) → ExoPlayer
6. Radio: `RadioEngine` (KNOWN / NEW / BOTH; fill LB→CF→Deezer; dedupe global `tryAddRemote`) → `playPlayableCollection` / refill de cola; remotos reusan stream
7. Para Ti: `MatchListenBrainzTracksUseCase` → `MatchedLbPlaylist.toPlayableItems` → `playPlayableCollection`; CF: `FetchAndMatchCfRecommendationsUseCase` → `matchFromMetadata` → `MatchedCfRecommendations.toPlayableItems`; descarga manual `downloadRemoteItem` (`DISCOVER`); opcional `saveWhileListening` → download background; import Room vía `ImportListenBrainzPlaylistUseCase` + `LB_IMPORT`
8. Descarga online: siempre vía `runTrackedDownload` → cola `activeDownloads` (tab Descargas) → `DownloadAudioTrackUseCase` → `MusicRepository.downloadAndSaveOnlineTrack` (re-extract vía `StreamResolver.resolveQuery`) → Room + storage

## Navegación UI

`MainScreen` bottom nav (índice persistido en `LibraryPreferencesRepository` / `selectedNavIndex`; deep-link descargas = `openDownloadsTabTransient` sin pisar snapshot):
0. Biblioteca (`LibraryScreen` + subviews album/artist/song)
1. Playlists (`PlaylistsScreen` + `PlaylistDetailNav`)
2. Descargas (`DownloadsScreen`)
3. WiFi Sync (`WebServerScreen`)
4. Ajustes (`SettingsScreen` / temas / ListenBrainz / Reproducción / Sonido)

Overlay: `BottomPlayerBar` → `NowPlayingScreen`; cola en `QueueScreen`.
Mini player se rehidrata desde `MediaController` (sesión viva) o `PlaybackSessionStore` / seed idle (ver features §10b).

**System back:** un paso por gesto en la jerarquía UI (`BackHandler` anidados; sin Navigation Compose). Prioridad: diálogos/menús → Now Playing → nested del tab → doble atrás para salir en raíz (`MainScreen`). Ver features §12.

## Principios estructurales (invariantes)

1. **Todo es colección** — play/shuffle/enqueue pasan por pipeline unificado (`playPlayableCollection` / `applyShuffledQueue` en ViewModel); cola interna es `PlayableItem`.
2. **Catálogo ≠ audio** — metadatos de iTunes/Deezer; bytes de audio vía YouTube (re-extraer URL antes de descargar/stream por CDN 403).
3. **Álbum vs playlist en portadas/metadata** — álbum tiene `album_overrides` (guardar solo álbum vs álbum+canciones); portada de playlist es entidad propia; editar canción no reescribe álbum.
4. **Portadas locales** — copiar a `context.filesDir` (no depender de content URIs temporales).
5. **Remoto efímero** — `PlayableItem.Remote` + `ResolvedStream` en memoria; nunca persistir URLs CDN en Room.
6. **Radio** — sesión con seed + providers (`LocalMetadataRadio` / `ListenBrainzRadio` / `CfRecommendationsRadio` / `DeezerSimilarRadio` via `SimilarTracksProvider`); fill NEW/BOTH sin exigir token LB (red + Deezer); refill de cola; no pipeline paralelo.
7. **Para Ti mixto** — Discover + CF Recomendados reproducen Local+Remote; descarga manual por track (`DISCOVER`) / “Guardar al escuchar” / import LB no bloquean ni persisten URLs CDN; faltantes de import usan `activeDownloads` (`LB_IMPORT` + `targetPlaylistId`).
8. **Cola de descargas** — `QUEUED`/`SUCCESS` visibles; máx. 3 concurrentes; playlist del catálogo crea playlist local.

## Servicios Android

| Servicio | Rol |
|----------|-----|
| `MusicService` | `MediaLibraryService` + ExoPlayer; FGS `mediaPlayback` vía `promotePlaybackForeground` (`startForeground`, no `startForegroundService`) mientras hay playback + `setSessionActivity`; `UserAgentMediaSourceFactory` lee UA de `StreamPlaybackTag`; `StereoBalanceAudioProcessor` + `LoudnessEnhancer` desde `PlaybackPreferencesRepository` |
| `WebServerService` | Servidor Ktor local para sync/upload por WiFi |

## Base de datos

Entidades: `SongEntity`, `PlaylistEntity`, `PlaylistSongCrossRef`, `PlaylistPendingTrackEntity`, `PendingListenEntity`, `AlbumOverrideEntity`.
Índice único Room: `songs.uriString`. Deduplicación lógica por `matchKey(artist, title)` en filtros de scan / download conflict (`Music/BestiaPop` app-managed). I/O local solo vía `MusicFileStore` / `AudioPersistRef.canonicalize`: BestiaPop = path absoluto; MediaStore ajeno = `content://media`. One-shot `migrateCanonicalAudioUris` reescribe SAF/cache. Migrator dedup histórico: branch `archive/library-dedup-v1-migrator`.
Migraciones Room: 1→2 (dedupe + unique index), 2→3 (playlist description/coverUri), 3→4 (pending_listens), 4→5 (playlist_pending_tracks), 5→6 (`album_overrides`), 6→7 (index `playlist_song_cross_ref.songId`).

## Relacionado

- Features esenciales → skill `bestiapop-features`
- Mapa de archivos/funciones → skill `bestiapop-implementation-map`
- Protocolo de actualización de docs → skill `bestiapop-living-docs`
- Principios históricos resumidos → `.agents/AGENTS.md` (mantener alineado)
