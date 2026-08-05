---
name: bestiapop-features
description: >-
  Funcionalidades esenciales de BestiaPop con referencias a APIs y archivos.
  Usar al implementar o modificar reproducción por colecciones, biblioteca
  (filtro/orden/vistas), descarga YouTube, portadas álbum/playlist, playlists,
  temas, WiFi sync, ListenBrainz, Radio o CF. Actualizar este skill cuando cambie el comportamiento.
---

# BestiaPop — Features esenciales

Cada feature lista **invariantes** + **entry points**. Si el código diverge, actualizar este archivo (ver `bestiapop-living-docs`).

## 1. Colecciones unificadas (“todo es playlist”)

**Invariante:** Listas, álbumes, artistas, playlists y colas usan el mismo pipeline de reproducción.

| Acción | ViewModel | Pipeline |
|--------|-----------|----------|
| Reproducir colección | `playCollection(songs, startIndex)` / `playCollection(songs, startSong)` | `playPlayableCollection` |
| Reproducir Local\|Remote | `playPlayableCollection(items, startIndex)` | ViewModel + `StreamResolver` |
| Shuffle | `shuffleCollection(songs)` | `applyShuffledQueue` |
| Encolar | `enqueueCollection(songs)` | append a cola `PlayableItem` |
| Una canción | `playSong(song, playlistOrQueue)` | (arma cola + MediaController) |

Archivos: `ui/MusicPlayerViewModel.kt` (`playPlayableCollection` / `applyShuffledQueue`).

## 2. Búsqueda online y descarga de audio

**Invariante:** Catálogo/metadatos pueden venir de iTunes/Deezer; el stream se resuelve con YouTube. Re-extraer URL CDN antes de descargar (evitar HTTP 403).

| Paso | Dónde |
|------|--------|
| Search catálogo tracks | `MetadataFetcher.searchOnlineCatalog` / `YouTubeExtractor.searchYouTube` (`parseSearchContents` + `audioPreferenceScore` / `rankByAudioPreference`: prioriza Topic / Official Audio sobre music video) |
| Query YT desde catálogo | `YouTubeExtractor.resolveYouTubeQueryOrId` (ignora ids Deezer/iTunes; usa `audioUrl` o `artist title`) |
| Álbumes / playlists online | `MetadataFetcher.searchAlbums` / `searchPlaylists` + `fetchAlbumTrackCandidates` / `fetchPlaylistTrackCandidates` |
| Extraer stream | `YouTubeExtractor.extractAudioStream` / `extractAudioStreamDetailed` |
| Descargar + persistir | `DownloadAudioTrackUseCase.execute` → `IMusicRepository.downloadAndSaveOnlineTrack` |
| UI diálogo | `ui/components/AddMusicDialog.kt` |
| Centro de descargas | `DownloadsScreen` + `ActiveDownloadRow`; persistencia `ActiveDownloadsStore` / `ActiveDownloadCodec`; notif `DownloadNotificationHelper`; badge `activeDownloadBadgeCount` en tab Descargas (`MainScreen`) |
| Orquestación VM | `enqueueTrackedBatch` → `runTrackedDownload` ← `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `downloadRemoteItem`, `maybeEnqueueSaveWhileListening`; candidatos vía `expandCandidates`; acciones `retryActiveDownload` / `cycleActiveDownload` / `previewActiveDownload` / `playActiveDownload` / `dismissActiveDownload`; deep-link `requestOpenDownloads` / `pendingOpenDownloads` |

Modelo clave: `OnlineCatalogTrack`, `CatalogTrackCandidate`, `DownloadStatus` (legacy Idle), `ActiveDownload` / `ActiveDownloadSource` (`CATALOG`, `LINK`, `SAVE_WHILE_LISTENING`, `BATCH`, `LB_IMPORT`, `DISCOVER`), cola `activeDownloads` (+ `targetPlaylistId` opcional, `resultSongId` en SUCCESS).

**Invariante cola:** todas las descargas online se registran en `activeDownloads` (estado `QUEUED` → `DOWNLOADING` → `SUCCESS`/`ERROR`); éxito **se mantiene** con play/limpiar hasta `dismissActiveDownload`. Fallo deja `ERROR`. Concurrencia global `Semaphore(3)` en `runTrackedDownload`. Tras kill: `ActiveDownloadCodec.forPersistence` restaura SUCCESS; DOWNLOADING/QUEUED → ERROR “Interrumpida”. Badge = DOWNLOADING + ERROR. Add Music banners leen `activeDownloads`. `LB_IMPORT` y batch de **playlist del catálogo** añaden a playlist al éxito vía `targetPlaylistId` (`ensureCatalogPlaylistForBatch`).

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

## 4. Portadas y metadata: álbum ≠ playlist ≠ canción

| Tipo | Comportamiento | Entry points |
|------|----------------|--------------|
| **Álbum (override)** | Tabla `album_overrides`; UI lee override si existe. **Guardar para álbum** = solo override; **Guardar para álbum y canciones** = override + bulk update de songs | `saveAlbumMetadata` / `upsertAlbumOverride` / `updateAlbumMetadataPropagateToSongs`; UI `EditAlbumMetadataDialog` |
| **Álbum portada** | `setAlbumArtwork` → propagate via `updateAlbumMetadataPropagateToSongs` | `MusicPlayerViewModel.setAlbumArtwork` |
| **Playlist** | `Playlist.coverUri` / `PlaylistEntity.coverUri` es de la lista; **no** pisa artwork de canciones | `createPlaylist` / `updatePlaylist`, `savePlaylistCoverImage` |
| **Canción** | Editar una canción **no** reescribe el álbum ni siblings | `updateSongMetadata` (incluye `year`); UI `EditSongMetadataDialog` |
| **Persistencia local** | Copiar imagen a `context.filesDir` (`album_covers` / playlist covers) | `saveAlbumCoverImage`, `savePlaylistCoverImage`, `extractAndSaveEmbeddedArtwork` |

Herencia visual en lista: `GetLibrarySongsUseCase.execute` unifica artwork faltante desde otras canciones del mismo álbum; `extractAlbums(songs, overrides)` aplica `AlbumOverride`.

## 5. Playlists locales

CRUD + membresía vía `IMusicRepository`:
`createPlaylist`, `updatePlaylist`, `deletePlaylist`, `addSongToPlaylist`, `removeSongFromPlaylist`.
Import LB: matched + `PlaylistPendingTrack` (`getPlaylistPendingTracksFlow` / `downloadPlaylistPendingTracks`).
Flows: `playlistsFlow`, `getPlaylistSongsFlow`, `getPlaylistDetailsFlow`.
UI: `PlaylistsScreen`.

## 6. Importación / biblioteca local

**Invariantes:**
- Unicidad lógica por `matchKey(artist, title)` (además del índice Room `uriString`).
- `Music/BestiaPop` es app-managed: `scanMediaStore` **no** reinserta esos archivos (evita duplicar `file:`/path vs `content://`).
- URIs de descarga/upload se guardan como **path absoluto**.
- Descarga con conflicto → `DuplicateSongException` / `DownloadConflict` → diálogo Sobrescribir | Crear nueva | Cancelar (`DownloadConflictPolicy`).
- One-shot migrator histórico: branch `archive/library-dedup-v1-migrator` (no compila en LB).

| Acción | API |
|--------|-----|
| Scan MediaStore | `scanMediaStore()` (skip BestiaPop + path/matchKey conocidos) |
| Scan carpeta SAF | `scanFolderUri(treeUri)` |
| Metadata archivo → Room | `AudioFileMetadata.fromPath` / `toSongEntity` (scan SAF + upload directo; sin MediaStore intermedio) |
| Upload WiFi → DB | `saveUploadedSong` (`absolutePath`; merge por matchKey) |
| Lookup duplicado | `findSongByArtistTitle` |
| Descarga + política | `downloadAndSaveOnlineTrack(..., conflictPolicy)` |
| Conflicto UI | `downloadConflict` / `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict` + `DownloadConflictDialog` |
| Borrar app / dispositivo | `deleteSongsFromApp` / `deleteSongsFromDevice` |
| Enriquecer meta/letras | `enhanceSongMetadataAndLyrics` |

## 7. Temas

`ThemePreferencesRepository` + `ThemePresets` + `ThemeSettingsScreen` + `CustomTheme` / `ColorSchemeData`.
State: `currentThemeState`.

## 7b. Sonido: amplificar + balance estéreo

**Invariantes:**
- Boost solo si `PlaybackSettings.volumeBoostEnabled` (Ajustes → Sonido; off por defecto).
- Now Playing: barra `0..1` (sistema) o `0..2` si enabled; `>1` = sistema al máximo + `LoudnessEnhancer` (0…`MAX_VOLUME_BOOST_GAIN_MB`). Volumen general; no faders L/R en Now Playing.
- Persistir `volumeBoostAmount` (`0f..1f`); al desactivar el flag se conserva el amount para reactivar.
- Balance L/R: `stereoLeftGain` / `stereoRightGain` (`0f..1f`, default `1f`); faders **independientes** (bajar uno no sube el otro). Atenuación PCM vía `StereoBalanceAudioProcessor` **antes** del `AudioTrack`; el boost (`LoudnessEnhancer`) se aplica después a ambos canales por igual (relación L/R se conserva).

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `PlaybackPreferencesRepository` / `PlaybackSettings` (`playback_settings`) |
| Settings UI | `VolumeBoostSettingsScreen` vía `SettingsScreen` sección Sonido |
| Aplicar boost | `MusicService.applyBoost` + `LoudnessEnhancer` en `ExoPlayer.audioSessionId` |
| Aplicar balance | `MusicService.applyStereoBalance` + `StereoBalanceAudioProcessor` en `DefaultAudioSink` |
| UI / persistir boost | `MusicPlayerViewModel.setVolume` / `setVolumeBoostEnabled` / `restoreVolumeBoostIfNeeded` |
| UI / persistir balance | `setStereoLeftGain` / `setStereoRightGain` / `resetStereoBalance` |
| UI slider general | `NowPlayingScreen` (`volumeBoostEnabled`, `valueRange` 0…2) |

## 8. WiFi Sync

`WebServerService` (Ktor) + `WebServerScreen(viewModel)`.

| Capacidad | Entry point |
|-----------|-------------|
| Servidor local | `WebServerService` + toggle en `WebServerScreen` |
| Transferencias en app | `WebServerService.transfers` (`WifiTransferItem` / `WifiTransferState`); lista en `WebServerScreen` (progreso + `SongListItem` al completar) |
| Dismiss | `WebServerService.dismissTransfer` |

Centro de descargas online → sección 2 (`DownloadsScreen`, tab Descargas).

## 8b. Centro de descargas (tab Descargas)

| Capacidad | Entry point |
|-----------|-------------|
| UI cola | `DownloadsScreen` + `ActiveDownloadRow` (QUEUED / progreso / SUCCESS play+limpiar / ERROR retry·cycle·dismiss) |
| Persistencia cola | `ActiveDownloadsStore` + `ActiveDownloadCodec` (DataStore JSON; conserva SUCCESS + `resultSongId`) |
| Notif progreso | `DownloadNotificationHelper` (canal `downloads_channel`; tap → tab Descargas) |
| Badge tab | `activeDownloadBadgeCount` en `MainScreen` NavigationBar Descargas |
| Deep-link | `requestOpenDownloads` / `pendingOpenDownloads` / `consumeOpenDownloads` |
| Límite | `downloadSemaphore` (3) en ViewModel |

## 9. ListenBrainz (scrobbling + Para Ti)

**Invariantes:**
- Scrobbling solo si `ListenBrainzSettings.enabled` + token válido; offline encola en `pending_listens`.
- Sección **Para Ti** / **Recomendados** en Playlists solo si `showDiscoverPlaylists` (`enabled && discoverEnabled && username`).
- Playlists Discover = `GET /1/user/{user}/playlists/createdfor`; detalle = `GET /1/playlist/{mbid}`.
- CF Recomendados = `GET /1/cf/recommendation/user/{user}/recording` + metadata → match Local|Remote.
- Match local por artist+title normalizado; faltantes = `PlayableItem.Remote`.
- Reproducción: cola mixta `Local|Remote` vía `playPlayableCollection` (prefetch / 403 retry de stream).
- **Descarga manual** de un Remote en detalle Para Ti / Recomendados: icono Descargar en `RemoteTrackPlaceholderRow` → `downloadRemoteItem` (`ActiveDownloadSource.DISCOVER`); progreso en Descargas; al éxito rematch LB + CF.
- **Guardar al escuchar** (`saveWhileListening` + `saveWhileListeningPercent`): al alcanzar ≥N% de la duración (o fin) de un Remote, encola en `activeDownloads` vía `runTrackedDownload` (sin reemplazar el MediaItem). Fallo → `ERROR` en el centro + Toast; quita la key de `saveWhileListeningAttempted` para permitir reintento manual/auto.
- **Import a Room:** “Guardar” crea playlist local con matched + metadata pendiente de faltantes (`playlist_pending_tracks`); “Descargar faltantes” / detalle local encola vía `runTrackedDownload` (`LB_IMPORT` + `targetPlaylistId`). Progreso en tab Descargas; nunca CDN en Room.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `ListenBrainzPreferencesRepository` / `ListenBrainzSettings` (`saveWhileListening`, `saveWhileListeningPercent`) |
| Settings UI | `ListenBrainzSettingsScreen` — registrar + **Mostrar Para Ti** + **Guardar al escuchar** (+ slider %) |
| Submit listens | `ListenBrainzClient.submitListens`, `ListenTracker`, `ListenSyncCoordinator` |
| List Discover | `ListenBrainzClient.fetchCreatedForPlaylists` → `MusicPlayerViewModel.refreshListenBrainzDiscoverPlaylists` |
| Abrir playlist | `openListenBrainzPlaylist` + `MatchListenBrainzTracksUseCase` |
| Map a cola | `MatchedLbPlaylist.toPlayableItems` / `MatchedLbTrack.toPlayableItem` |
| Play / shuffle / índice | `playListenBrainzPlaylist` / `shuffleListenBrainzPlaylist` / `playListenBrainzPlaylistAt` |
| Import locales + pendientes | `saveListenBrainzPlaylistAsLocal` → `ImportListenBrainzPlaylistUseCase.createLocalFromMatched` (+ `PlaylistPendingTrack`) |
| Import + descarga ya | `importListenBrainzPlaylistWithDownloads` / `downloadPlaylistPendingTracks` → `runTrackedDownload` (`LB_IMPORT`) |
| Descarga manual Remote | `downloadRemoteItem` → `runTrackedDownload` (`DISCOVER`); UI `RemoteTrackPlaceholderRow.onDownload` en detalle LB/CF |
| CF Recomendados | `ListenBrainzClient.fetchCfRecordingRecommendations` → `FetchAndMatchCfRecommendationsUseCase` → `refreshCfRecommendations` / `openCfRecommendations` / `playCfRecommendations` / `shuffleCfRecommendations` / `playCfAt` |
| UI sección | `PlaylistsScreen` — "Para Ti" + "Recomendados"; Guardar / Descargar faltantes / descarga por track; detalle local muestra pendientes |

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

## 10b. Continuidad del mini player

**Invariantes:**
- Tras reconnect de `MediaController`, `syncUiFromController` rehidrata `_queue` / `currentItem` / `isPlaying` / posición desde la sesión viva (prioridad sobre last-played).
- Sin sesión viva: seed idle con última canción **local** persistida (`PlaybackSessionStore`) o, si no hay historial, una aleatoria de la biblioteca; sin autoplay.
- Biblioteca vacía y sin sesión → `BottomPlayerBar` oculto (`currentItem == null`).
- Idle play: si `mediaItemCount == 0` y hay `currentItem`, `togglePlayPause` carga vía `playSong` / `playPlayableCollection` (resume de posición).
- No persistir CDN de `Remote`. Mini bar: Previous + status (`Resolviendo…` / `Armando radio…` / `radioStatusLabel`).

| Capacidad | Entry point |
|-----------|-------------|
| Resync sesión | `MusicPlayerViewModel.syncUiFromController` / `mediaItemToPlayable` |
| Last-played | `PlaybackSessionStore`, `LastPlayedCodec`, `PlaybackHydration` en `data/preferences/PlaybackSessionStore.kt` |
| Seed idle | `maybeSeedIdlePlayer` |
| Mini bar UI | `BottomPlayerBar` (`statusLabel`, Previous); wiring en `MainScreen` |

## 11. Radio (similares)

**Invariantes:**
- Seed = canción elegida (`startRadio(seedSong)` o `currentItem`); entry en menú de canción (“Iniciar radio”) y `NowPlayingScreen`.
- **Modos UI:** Solo conocidos (`KNOWN`) / Solo nuevos (`NEW`) / Ambos (`BOTH`); label `radioStatusLabel` (“Radio · Solo conocidos|Solo nuevos|Ambos”).
- Long-press Radio en Now Playing: Solo conocidos / Solo nuevos / Ambos / Detener radio (`stopRadio` no vacía cola).
- **Auto:** al llegar a `STATE_ENDED` con `RepeatMode.OFF`, `startRadio(auto = true)` respeta preferred; default sin preferred = `BOTH` si hay red (Deezer usable sin token LB), si no `KNOWN`.
- **Durante reproducción:** no saltea el tema actual; `replaceUpcomingWithRadio` + toast “Se agregaron canciones de la radio a la cola”.
- **KNOWN:** solo biblioteca (`LocalMetadataRadio` + boost co-playlist). **NEW:** solo `PlayableItem.Remote` vía LB → CF → Deezer (+ iTunes fill); matches de biblioteca se omiten; reintenta con backoff hasta ~45s (`suggestRadioWithRetry`); toast “Radio online no disponible” solo si tras timeout no hay Remotes. **BOTH:** intercala Remote, Local… (`RadioEngine.interleaveEquitable`); sin red sigue con conocidos (sin toast).
- Fill remoto: `SimilarTracksProvider` (Deezer); LB/CF siguen cableados en `RadioEngine` con credenciales.
- Refill con el mismo modo; **NEW** reintenta online ~20s; **no** persistir URLs CDN.

| Capacidad | Entry point |
|-----------|-------------|
| Modos | `RadioMode.KNOWN` / `NEW` / `BOTH`; `radioMode` / `radioStatusLabel` |
| Motor | `RadioEngine.suggest` → `RadioSuggestResult` (`usedOnlineDiscovery`); `interleaveEquitable` |
| Contrato fill | `SimilarTracksProvider` |
| Local | `LocalMetadataRadio.suggest` (+ `coPlaylistSongIds` vía `IMusicRepository.getCoPlaylistSongIds`) |
| LB | `ListenBrainzRadio.suggest` + LB client metadata/lb-radio |
| CF fill | `CfRecommendationsRadio.suggest` (`artist_type=similar`, cache TTL) |
| Deezer fill | `DeezerSimilarRadio.suggest` + `MetadataFetcher.resolveDeezerArtistId` / `fetchDeezerArtistRadio` / `fetchDeezerRelatedArtistIds` / `fetchDeezerArtistTop` / `fetchItunesArtistSongs` |
| Sesión | `startRadio`, `stopRadio`, `setRadioPreferredMode`, `suggestRadioWithRetry`, `replaceUpcomingWithRadio`, refill/auto |
| UI | Now Playing (tap/long-press); mini bar `statusLabel` (radio / resolving); menú canción “Iniciar radio” |

## 12. System back (jerarquía UI)

**Invariante:** un gesto atrás = un paso atrás. Paridad con ArrowBack / chevron / Cancel. No detiene reproducción. Sin Navigation Compose: `BackHandler` por capa.

| Prioridad | Comportamiento | Entry point |
|-----------|----------------|-------------|
| Diálogos / menús | Framework `onDismissRequest` | `Dialog` / `AlertDialog` / `DropdownMenu` |
| Add Music colección | `clearSelectedCollection` antes de cerrar | `AddMusicDialog` `BackHandler` + `onDismissRequest` |
| Now Playing | `dismissFullPlayer` | `NowPlayingScreen` `BackHandler` |
| Library nested | multi-select → cancel addition → album/artist → clear search | `LibraryScreen` `BackHandler` |
| Playlists nested | CF → LB Discover → playlist local | `PlaylistsScreen` `BackHandler` |
| Settings nested | Temas / LB / Sonido → home | `SettingsScreen` `BackHandler` |
| Raíz de tab | Doble atrás (~2s) + snackbar “Pulsa otra vez para salir” | `MainScreen` `BackHandler` + `SnackbarHost` |

Manifest: `android:enableOnBackInvokedCallback="true"` en `MainActivity`.

## Relacionado

- Capas y stack → `bestiapop-architecture`
- Paths exactos → `bestiapop-implementation-map`
