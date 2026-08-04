---
name: bestiapop-implementation-map
description: >-
  Mapa vivo de implementaciones BestiaPop: archivos, clases y funciones clave
  por concern. Usar para localizar código rápido, citar entry points en PRs o
  al añadir features. Actualizar cuando se creen, renombren o muevan símbolos
  importantes.
---

# BestiaPop — Mapa de implementaciones

Paths relativos a `app/src/main/java/com/bestiapop/android/`.

## Entrada app

| Concern | Archivo |
|---------|---------|
| Activity | `MainActivity.kt` |
| Manifest / permisos / services | `app/src/main/AndroidManifest.xml` |
| Gradle app | `app/build.gradle.kts` |
| Deploy script | `install.sh` (repo root) |

## UI — screens

| Pantalla | Archivo |
|----------|---------|
| Shell + bottom nav | `ui/screens/MainScreen.kt` |
| Biblioteca | `ui/screens/LibraryScreen.kt` |
| Lista canciones / álbumes / artistas | `ui/screens/library/LibrarySongList.kt`, `LibraryAlbumGrid.kt`, `LibraryArtistList.kt`, `LibraryDialogs.kt` |
| Playlists | `ui/screens/PlaylistsScreen.kt` |
| Ajustes / ListenBrainz | `ui/screens/SettingsScreen.kt`, `ListenBrainzSettingsScreen.kt` |
| Now playing | `ui/screens/NowPlayingScreen.kt` |
| Cola | `ui/screens/QueueScreen.kt` |
| WiFi sync | `ui/screens/WebServerScreen.kt` |
| Temas | `ui/screens/ThemeSettingsScreen.kt` |

## UI — components / state / theme

| Concern | Archivo |
|---------|---------|
| ViewModel central | `ui/MusicPlayerViewModel.kt` |
| Mini player | `ui/components/BottomPlayerBar.kt` |
| Add / download music | `ui/components/AddMusicDialog.kt` |
| Song row | `ui/components/SongListItem.kt` |
| Artwork thumb | `ui/components/ArtworkThumbnail.kt` |
| Multi-select bar | `ui/components/MultiSelectActionBar.kt` |
| Sort helper UI | `ui/components/SortRelevantInfo.kt` |
| Color picker | `ui/components/ColorPickerDialog.kt` |
| Library list model | `ui/state/LibraryListItem.kt`, `LibraryUiState.kt` |
| Theme Compose | `ui/theme/Theme.kt`, `ThemePresets.kt` |

## Domain

| Use case | Archivo | Responsabilidad |
|----------|---------|-----------------|
| `PlayCollectionUseCase` | `domain/usecase/PlayCollectionUseCase.kt` | play / shuffle / append queue plans |
| `GetLibrarySongsUseCase` | `domain/usecase/GetLibrarySongsUseCase.kt` | filter, sort, album groups, extract albums/artists |
| `DownloadAudioTrackUseCase` | `domain/usecase/DownloadAudioTrackUseCase.kt` | wrap download Result |
| `ManageArtworkUseCase` | `domain/usecase/ManageArtworkUseCase.kt` | album artwork propagation |
| `ManagePlaylistUseCase` | `domain/usecase/ManagePlaylistUseCase.kt` | playlist ops over repo |
| `MatchListenBrainzTracksUseCase` | `domain/usecase/MatchListenBrainzTracksUseCase.kt` | match LB tracks → local `Song` |
| Puerto | `domain/repository/IMusicRepository.kt` | contrato repositorio |

## Data

| Concern | Archivo |
|---------|---------|
| Repo impl | `data/repository/MusicRepository.kt` |
| Modelos dominio UI | `data/model/Models.kt` |
| Cola Local/Remote | `data/model/PlayableItem.kt` (`PlayableItem`, `ResolvedStream`, `Song.toPlayable`) |
| Room DB | `data/db/AppDatabase.kt` |
| DAO | `data/db/MusicDao.kt` |
| Song entity + mappers | `data/db/SongEntity.kt` |
| Playlist entities | `data/db/PlaylistEntities.kt` |
| Catálogo / lyrics / covers web | `data/network/MetadataFetcher.kt` |
| YouTube search + stream | `data/network/YouTubeExtractor.kt` |
| Stream resolve + cache TTL | `data/stream/StreamResolver.kt` |
| Theme DataStore | `data/preferences/ThemePreferencesRepository.kt` |
| ListenBrainz prefs | `data/preferences/ListenBrainzPreferencesRepository.kt` |
| ListenBrainz API | `data/network/ListenBrainzClient.kt` |
| LB models + sync | `data/listenbrainz/LbPlaylistModels.kt`, `ListenTracker.kt`, `ListenSyncCoordinator.kt` |
| Connectivity | `data/network/ConnectivityObserver.kt` |
| Pending listens Room | `data/db/PendingListenEntity.kt`, `PendingListenDao.kt` |
| Storage helpers | `data/util/StorageUtils.kt` |

## Services

| Servicio | Archivo |
|----------|---------|
| Playback Media3 + UA HTTP | `service/MusicService.kt`, `service/StreamPlaybackTag.kt` |
| Ktor WiFi server | `service/WebServerService.kt` |

## Tests de referencia

| Tipo | Archivo |
|------|---------|
| Library list items | `app/src/test/.../GetLibrarySongsUseCaseListItemsTest.kt` |
| YouTube extraction | `app/src/test/.../YouTubeExtractionIntegrationTest.kt` |
| StreamResolver cache/TTL | `app/src/test/.../StreamResolverTest.kt` |
| UI functional library | `app/src/androidTest/.../LibraryScreenFunctionalTest.kt` |

## Símbolos ViewModel frecuentes

Mantener esta lista alineada con `MusicPlayerViewModel.kt`:

- Biblioteca: `songsState`, `albumsState`, `artistsState`, `searchQuery`, `sortOption`, `buildLibraryListItems`
- Playback: `playSong`, `playCollection`, `playPlayableCollection`, `shuffleCollection`, `enqueueCollection`, `playNextInQueue`, `playNextBatch`, `currentItem`, `currentSong`, `queue`, `resolvingRemote`, `repeatMode`, `isShuffle`
- Artwork: `setAlbumArtwork`
- Online: `searchCatalog`, `searchOnlineCatalog`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `playOnlineCatalogTrackAsStream`, `cycleSongCatalogResult`, `cycleTrackCandidate`, `catalogPreviewKey`
- ListenBrainz: `listenBrainzSettings`, `setListenBrainzEnabled`, `setListenBrainzDiscoverEnabled`, `refreshListenBrainzDiscoverPlaylists`, `openListenBrainzPlaylist`, `playListenBrainzPlaylist`, `shuffleListenBrainzPlaylist`

## Cómo actualizar este mapa

Tras crear/renombrar/mover un archivo o API pública relevante:

1. Editar la fila correspondiente (o añadir sección).
2. Si el change afecta un feature, actualizar también `bestiapop-features`.
3. Si cambia capas/paquetes/stack, actualizar `bestiapop-architecture`.
