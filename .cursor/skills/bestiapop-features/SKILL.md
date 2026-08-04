---
name: bestiapop-features
description: >-
  Funcionalidades esenciales de BestiaPop con referencias a APIs y archivos.
  Usar al implementar o modificar reproducción por colecciones, biblioteca
  (filtro/orden/vistas), descarga YouTube, portadas álbum/playlist, playlists,
  temas, WiFi sync, ListenBrainz o Radio. Actualizar este skill cuando cambie el comportamiento.
---

# BestiaPop — Features esenciales

Cada feature lista **invariantes** + **entry points**. Si el código diverge, actualizar este archivo (ver `bestiapop-living-docs`).

## 1. Colecciones unificadas (“todo es playlist”)

**Invariante:** Listas, álbumes, artistas, playlists y colas usan el mismo pipeline de reproducción.

| Acción | ViewModel | Use case |
|--------|-----------|----------|
| Reproducir colección | `playCollection(songs, startIndex)` / `playCollection(songs, startSong)` | `PlayCollectionUseCase.playCollection` |
| Reproducir Local\|Remote | `playPlayableCollection(items, startIndex)` | (ViewModel + `StreamResolver`) |
| Shuffle | `shuffleCollection(songs)` | `PlayCollectionUseCase.shuffleCollection` |
| Encolar | `enqueueCollection(songs)` | `PlayCollectionUseCase.prepareQueueAppend` |
| Una canción | `playSong(song, playlistOrQueue)` | (arma cola + MediaController) |

Archivos: `domain/usecase/PlayCollectionUseCase.kt`, `ui/MusicPlayerViewModel.kt`.

## 2. Búsqueda online y descarga de audio

**Invariante:** Catálogo/metadatos pueden venir de iTunes/Deezer; el stream se resuelve con YouTube. Re-extraer URL CDN antes de descargar (evitar HTTP 403).

| Paso | Dónde |
|------|--------|
| Search catálogo tracks | `MetadataFetcher.searchOnlineCatalog` / `YouTubeExtractor.searchYouTube` |
| Álbumes / playlists online | `MetadataFetcher.searchAlbums` / `searchPlaylists` + `fetchAlbumTrackCandidates` / `fetchPlaylistTrackCandidates` |
| Extraer stream | `YouTubeExtractor.extractAudioStream` / `extractAudioStreamDetailed` |
| Descargar + persistir | `DownloadAudioTrackUseCase.execute` → `IMusicRepository.downloadAndSaveOnlineTrack` |
| UI diálogo | `ui/components/AddMusicDialog.kt` |
| Orquestación VM | `searchCatalog`, `searchOnlineCatalog`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack` |

Modelo clave: `OnlineCatalogTrack`, `CatalogTrackCandidate`, `DownloadStatus`.

## 3. Biblioteca: filtro, orden y vistas

**Invariante:** `songsState` filtra por título/artista/álbum/género y ordena con `SortOption`.

| Capacidad | API |
|-----------|-----|
| Query | `MusicPlayerViewModel.searchQuery` |
| Sort | `SortOption`: TITLE, ARTIST, ALBUM, GENRE, DATE_ADDED |
| Filtrado/orden | `GetLibrarySongsUseCase.execute` |
| Vista plana vs grupos álbum | `LibraryViewMode.FLAT` / `ALBUM_GROUPS` → `buildLibraryListItems` / `buildListItems` |
| Derivados | `extractAlbums`, `extractArtists` → `albumsState`, `artistsState` |

UI: `LibraryScreen`, `LibrarySongList`, `LibraryAlbumGrid`, `LibraryArtistList`.
Estado: `ui/state/LibraryUiState.kt`, `LibraryListItem.kt`.

## 4. Portadas: álbum ≠ playlist

| Tipo | Comportamiento | Entry points |
|------|----------------|--------------|
| **Álbum** | Al asignar portada, **todas** las canciones del álbum heredan | `setAlbumArtwork` → `ManageArtworkUseCase.updateAlbumArtwork` |
| **Playlist** | `Playlist.coverUri` / `PlaylistEntity.coverUri` es de la lista; **no** pisa artwork de canciones | `createPlaylist` / `updatePlaylist`, `savePlaylistCoverImage` |
| **Persistencia local** | Copiar imagen a `context.filesDir` | `MusicRepository.savePlaylistCoverImage`, `extractAndSaveEmbeddedArtwork` |

Herencia visual en lista: `GetLibrarySongsUseCase.execute` unifica artwork faltante desde otras canciones del mismo álbum.

## 5. Playlists locales

CRUD + membresía vía `IMusicRepository` / `ManagePlaylistUseCase`:
`createPlaylist`, `updatePlaylist`, `deletePlaylist`, `addSongToPlaylist`, `removeSongFromPlaylist`.
Flows: `playlistsFlow`, `getPlaylistSongsFlow`, `getPlaylistDetailsFlow`.
UI: `PlaylistsScreen`.

## 6. Importación / biblioteca local

| Acción | API |
|--------|-----|
| Scan MediaStore | `scanMediaStore()` |
| Scan carpeta SAF | `scanFolderUri(treeUri)` |
| Upload WiFi → DB | `saveUploadedSong` |
| Borrar app / dispositivo | `deleteSongsFromApp` / `deleteSongsFromDevice` |
| Enriquecer meta/letras | `enhanceSongMetadataAndLyrics` |

## 7. Temas

`ThemePreferencesRepository` + `ThemePresets` + `ThemeSettingsScreen` + `CustomTheme` / `ColorSchemeData`.
State: `currentThemeState`.

## 8. WiFi Sync

`WebServerService` (Ktor) + `WebServerScreen`. Sirve para transferir audio al dispositivo en red local.

## 9. ListenBrainz (scrobbling + Para Ti)

**Invariantes:**
- Scrobbling solo si `ListenBrainzSettings.enabled` + token válido; offline encola en `pending_listens`.
- Sección **Para Ti** en Playlists solo si `showDiscoverPlaylists` (`enabled && discoverEnabled && username`).
- Playlists Discover = `GET /1/user/{user}/playlists/createdfor`; detalle = `GET /1/playlist/{mbid}`.
- Reproducción: solo tracks matcheados a biblioteca local (artist+title normalizado); pipeline `playCollection` / `shuffleCollection`.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `ListenBrainzPreferencesRepository` / `ListenBrainzSettings` |
| Settings UI | `ListenBrainzSettingsScreen` — toggle registrar + **Mostrar Para Ti** |
| Submit listens | `ListenBrainzClient.submitListens`, `ListenTracker`, `ListenSyncCoordinator` |
| List Discover | `ListenBrainzClient.fetchCreatedForPlaylists` → `MusicPlayerViewModel.refreshListenBrainzDiscoverPlaylists` |
| Abrir playlist | `openListenBrainzPlaylist` + `MatchListenBrainzTracksUseCase` |
| Play / shuffle | `playListenBrainzPlaylist` / `shuffleListenBrainzPlaylist` |
| UI sección | `PlaylistsScreen` — sección "Para Ti" + detalle read-only |

## 10. Stream remoto (playback sin descarga)

**Invariantes:**
- Cola unificada `List<PlayableItem>` (`Local` | `Remote`); APIs `Song` se adaptan con `Song.toPlayable()`.
- Re-extraer stream YouTube just-in-time (`StreamResolver` → `YouTubeExtractor`); cache memoria TTL ~4 min; **no** guardar `audioUrl` CDN en Room.
- ExoPlayer usa UA del extract vía `StreamPlaybackTag` en `MusicService`.
- Prefetch índices N+1 / N+2; un reintento en 403/IO luego `seekToNext`.
- Descarga explícita (“Agregar”) sigue download-then-play; stream no la reemplaza.

| Capacidad | Entry point |
|-----------|-------------|
| Modelo | `PlayableItem`, `ResolvedStream` en `data/model/PlayableItem.kt` |
| Resolver | `StreamResolver.resolve` / `prefetch` en `data/stream/StreamResolver.kt` |
| UA ExoPlayer | `StreamPlaybackTag` + `MusicService` `UserAgentMediaSourceFactory` |
| Cola / play | `playPlayableCollection`, `currentItem`, `resolvingRemote` en `MusicPlayerViewModel` |
| Stream desde catálogo | `playOnlineCatalogTrackAsStream` + preview in-dialog (`CatalogTrackItem` / `CandidateTrackCard` + `CatalogPreviewBar`); `cycleSongCatalogResult` / `cycleTrackCandidate` (“Buscar otro”) |
| UI player | `BottomPlayerBar` / `NowPlayingScreen` / `QueueScreen` observan `PlayableItem` |

## 11. Radio (similares)

**Invariantes:**
- Seed = canción elegida (`startRadio(seedSong)` o `currentItem`); entry en menú de canción (“Iniciar radio”) y `NowPlayingScreen`.
- **Modos UI:** Offline (`EASY`) / Online (`EXPLORE`); label `radioStatusLabel` (“Radio · Offline|Online|Online (forzado)”).
- **Forzar online:** `radioForceOnline`; ante sin red/token o `listenBrainzFailed` → toast, force off, Offline, sigue con local.
- Long-press Radio en Now Playing: Offline / Online / Forzar online / Detener radio (`stopRadio` no vacía cola).
- **Auto:** al llegar a `STATE_ENDED` con `RepeatMode.OFF`, `startRadio(auto = true)` respeta preferred/force.
- **Durante reproducción:** no saltea el tema actual; `replaceUpcomingWithRadio` + toast “Se agregaron canciones de la radio a la cola”.
- **EASY:** solo biblioteca; **EXPLORE:** locales del seed primero + LB/remotos; `RadioSuggestResult` indica si LB aportó/falló.
- Refill cuando quedan < 5 con misma política force/degrade; **no** persistir URLs CDN.

| Capacidad | Entry point |
|-----------|-------------|
| Modos | `RadioMode.EASY` / `EXPLORE`; `radioMode` / `radioForceOnline` / `radioStatusLabel` |
| Motor | `RadioEngine.suggest` → `RadioSuggestResult` |
| Local | `LocalMetadataRadio.suggest` |
| LB | `ListenBrainzRadio.suggest` + LB client metadata/lb-radio |
| Sesión | `startRadio`, `stopRadio`, `setRadioPreferredMode`, `setRadioForceOnline`, `replaceUpcomingWithRadio`, refill/auto |
| UI | Now Playing (tap/long-press); `BottomPlayerBar.radioStatusLabel`; menú canción “Iniciar radio” |

## Relacionado

- Capas y stack → `bestiapop-architecture`
- Paths exactos → `bestiapop-implementation-map`
