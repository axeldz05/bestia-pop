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
| System back (exit doble + orquestación) | `MainScreen` `BackHandler` + `SnackbarHost`; nested en screens abajo |
| Biblioteca | `ui/screens/LibraryScreen.kt` (`BackHandler`: multi-select / addition / album-artist / search) |
| Lista canciones / álbumes / artistas | `ui/screens/library/LibrarySongList.kt`, `LibraryAlbumGrid.kt`, `LibraryArtistList.kt`, `LibraryDialogs.kt` |
| Playlists | `ui/screens/PlaylistsScreen.kt` (`BackHandler`: CF → LB → local detail) |
| Ajustes / ListenBrainz | `ui/screens/SettingsScreen.kt` (`BackHandler` sección), `ListenBrainzSettingsScreen.kt` |
| Now playing | `ui/screens/NowPlayingScreen.kt` (`BackHandler` → `onDismiss`) |
| Cola | `ui/screens/QueueScreen.kt` |
| WiFi sync | `ui/screens/WebServerScreen.kt` (`WebServerScreen(viewModel)` + transferencias) |
| Descargas | `ui/screens/DownloadsScreen.kt` (`DownloadsScreen(viewModel)` + `ActiveDownloadRow`) |
| Temas | `ui/screens/ThemeSettingsScreen.kt` |

## UI — components / state / theme

| Concern | Archivo |
|---------|---------|
| ViewModel central | `ui/MusicPlayerViewModel.kt` |
| Mini player | `ui/components/BottomPlayerBar.kt` (`statusLabel`, Previous/Next/Play) |
| Active download row | `ui/components/ActiveDownloadRow.kt` |
| Download conflict dialog | `ui/components/DownloadConflictDialog.kt` |
| Add / download music | `ui/components/AddMusicDialog.kt` (banners vía `activeDownloads` + `ActiveDownloadsSummaryBanner`; `BackHandler` step-back colección) |
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
| `MatchListenBrainzTracksUseCase` | `domain/usecase/MatchListenBrainzTracksUseCase.kt` | match LB tracks → local `Song` (`normalize` / `matchKey`) |
| `ImportListenBrainzPlaylistUseCase` | `domain/usecase/ImportListenBrainzPlaylistUseCase.kt` | create Room playlist: matched + `PlaylistPendingTrack` metadata |
| `FetchAndMatchCfRecommendationsUseCase` | `domain/usecase/FetchAndMatchCfRecommendationsUseCase.kt` | CF mbids → metadata → Local/Remote |
| `RadioEngine` | `domain/radio/RadioEngine.kt` | orquesta local → LB → CF; `RadioSuggestResult` |
| `LocalMetadataRadio` | `domain/radio/LocalMetadataRadio.kt` | score biblioteca (artista/género/año/álbum) |
| `ListenBrainzRadio` | `domain/radio/ListenBrainzRadio.kt` | lb-radio → Local/Remote |
| `CfRecommendationsRadio` | `domain/radio/CfRecommendationsRadio.kt` | CF pool cache → Local/Remote (Radio EXPLORE fill) |
| `RadioMode` | `domain/radio/RadioMode.kt` | `EASY` / `EXPLORE` |
| Puerto | `domain/repository/IMusicRepository.kt` | contrato repositorio |

## Data

| Concern | Archivo |
|---------|---------|
| Repo impl | `data/repository/MusicRepository.kt` |
| Modelos dominio UI | `data/model/Models.kt` (`OnlineCatalogTrack`, `CatalogTrackCandidate`, `DownloadStatus`, `ActiveDownload` + `targetPlaylistId` / `resultSongId`, `ActiveDownloadSource` incl. `LB_IMPORT`, `CandidateDownloadState` incl. `QUEUED`, `PlaylistPendingTrack`, `AlbumOverride`, `WifiTransferItem` / `WifiTransferState`, `Album.displayName`) |
| Cola Local/Remote | `data/model/PlayableItem.kt` (`PlayableItem`, `ResolvedStream`, `Song.toPlayable`) |
| Room DB | `data/db/AppDatabase.kt` (v6) |
| DAO | `data/db/MusicDao.kt` |
| Song entity + mappers | `data/db/SongEntity.kt` |
| Album overrides | `data/db/AlbumOverrideEntity.kt` |
| Playlist entities | `data/db/PlaylistEntities.kt` (`PlaylistPendingTrackEntity`) |
| Catálogo / lyrics / covers web | `data/network/MetadataFetcher.kt` |
| YouTube search + stream | `data/network/YouTubeExtractor.kt` |
| Stream resolve + cache TTL | `data/stream/StreamResolver.kt` |
| Theme DataStore | `data/preferences/ThemePreferencesRepository.kt` |
| Active downloads persist | `data/preferences/ActiveDownloadsStore.kt` (`ActiveDownloadCodec`, `activeDownloadBadgeCount`) |
| Last-played / idle seed | `data/preferences/PlaybackSessionStore.kt` (`LastPlayedCodec`, `PlaybackHydration`, `LastPlayedSnapshot`) |
| ListenBrainz prefs | `data/preferences/ListenBrainzPreferencesRepository.kt` |
| ListenBrainz API | `data/network/ListenBrainzClient.kt` (`submitListens`, createdfor, playlist, `lookupRecordingMetadata`, `fetchLbRadioArtist`, `fetchRecordingMetadata`, `fetchCfRecordingRecommendations`, `parseCfRecommendations`) |
| LB models + sync | `data/listenbrainz/LbPlaylistModels.kt` (`MatchedLbPlaylist.toPlayableItems`, `streamCount`), `LbRadioModels.kt`, `CfRecommendationModels.kt` (`MatchedCfRecommendations`), `ListenTracker.kt`, `ListenSyncCoordinator.kt` |
| Connectivity | `data/network/ConnectivityObserver.kt` |
| Pending listens Room | `data/db/PendingListenEntity.kt`, `PendingListenDao.kt` |
| Storage helpers | `data/util/StorageUtils.kt`, `data/util/SongPathNormalizer.kt` |
| Download conflict models | `data/model/Models.kt` (`DownloadConflictPolicy`, `DuplicateSongException`, `DownloadConflict`) |
| One-shot dedup archive | branch `archive/library-dedup-v1-migrator` (`LibraryDedupMigrator` / `LibraryDedupLogic` / prefs; not on LB) |

## Services

| Servicio | Archivo |
|----------|---------|
| Playback Media3 + UA HTTP | `service/MusicService.kt`, `service/StreamPlaybackTag.kt` |
| Ktor WiFi server | `service/WebServerService.kt` (`serverState`, `transfers`, `dismissTransfer`) |
| Download progress notif | `service/DownloadNotificationHelper.kt` (`EXTRA_OPEN_TAB` / `TAB_DOWNLOADS`) |

## Tests de referencia

| Tipo | Archivo |
|------|---------|
| Library list items | `app/src/test/.../GetLibrarySongsUseCaseListItemsTest.kt` |
| YouTube extraction | `app/src/test/.../YouTubeExtractionIntegrationTest.kt` |
| StreamResolver cache/TTL | `app/src/test/.../StreamResolverTest.kt` |
| Radio local / engine | `app/src/test/.../RadioEngineTest.kt` |
| LB radio / CF JSON parse | `app/src/test/.../ListenBrainzRadioParseTest.kt` |
| LB Para Ti → PlayableItem | `app/src/test/.../MatchedLbPlaylistPlayableTest.kt` |
| CF match Local|Remote | `app/src/test/.../FetchAndMatchCfRecommendationsUseCaseTest.kt` |
| ActiveDownload cycle | `app/src/test/.../ActiveDownloadCycleTest.kt` |
| ActiveDownload codec / badge | `app/src/test/.../ActiveDownloadCodecTest.kt` |
| Last-played / idle hydration | `app/src/test/.../PlaybackSessionStoreTest.kt` |
| Import LB playlist | `app/src/test/.../ImportListenBrainzPlaylistUseCaseTest.kt` |
| Path normalize | `app/src/test/.../SongPathNormalizerTest.kt` |
| UI functional library | `app/src/androidTest/.../LibraryScreenFunctionalTest.kt` |

## Símbolos ViewModel frecuentes

Mantener esta lista alineada con `MusicPlayerViewModel.kt`:

- Biblioteca: `songsState`, `albumsState`, `artistsState`, `searchQuery`, `sortOption`, `buildLibraryListItems`
- Playback: `playSong`, `playCollection`, `playPlayableCollection`, `shuffleCollection`, `enqueueCollection`, `playNextInQueue`, `playNextBatch`, `currentItem`, `currentSong`, `queue`, `resolvingRemote`, `repeatMode`, `isShuffle`, `syncUiFromController`, `maybeSeedIdlePlayer`, `togglePlayPause`
- Radio: `startRadio` / `stopRadio` / `setRadioPreferredMode` / `setRadioForceOnline`, `radioMode`, `radioForceOnline`, `radioStatusLabel`, `replaceUpcomingWithRadio`, `maybeAutoStartRadioOnQueueEnd`
- Artwork: `setAlbumArtwork`, `saveAlbumMetadata`
- Online: `searchCatalog`, `searchOnlineCatalog`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `activeDownloads`, `downloadConflict`, `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict`, `retryActiveDownload`, `cycleActiveDownload`, `previewActiveDownload`, `playActiveDownload`, `dismissActiveDownload`, `requestOpenDownloads` / `pendingOpenDownloads`, `playOnlineCatalogTrackAsStream`, `cycleSongCatalogResult`, `cycleTrackCandidate`, `catalogPreviewKey`
- ListenBrainz: `listenBrainzSettings`, `setListenBrainzEnabled`, `setListenBrainzDiscoverEnabled`, `setListenBrainzSaveWhileListening`, `setListenBrainzSaveWhileListeningPercent`, `refreshListenBrainzDiscoverPlaylists`, `openListenBrainzPlaylist`, `playListenBrainzPlaylist`, `shuffleListenBrainzPlaylist`, `playListenBrainzPlaylistAt`, `saveListenBrainzPlaylistAsLocal`, `importListenBrainzPlaylistWithDownloads`, `downloadPlaylistPendingTracks`, `getPlaylistPendingTracksFlow`, `refreshCfRecommendations`, `openCfRecommendations`, `closeCfRecommendations`, `playCfRecommendations`, `shuffleCfRecommendations`, `playCfAt`, `cfRecommendations`, `cfListState`, `cfDetailOpen`

## Cómo actualizar este mapa

Tras crear/renombrar/mover un archivo o API pública relevante:

1. Editar la fila correspondiente (o añadir sección).
2. Si el change afecta un feature, actualizar también `bestiapop-features`.
3. Si cambia capas/paquetes/stack, actualizar `bestiapop-architecture`.
