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
| Application + process graph | `BestiaPopApplication.kt` (`BestiaPopApplication.onCreate`: `MusicRepository`; radio engine; `ProcessDownloadCoordinator.create`; `ProcessDownloadRuntime.create`; `ProcessSaveWhileListeningCoordinator`; `PlaybackRuntime.create`) |
| Crash non-fatals / keys | `data/util/CrashReporter.kt` (`setKey` / `log` / `recordNonFatal`; identify batch keys `identify_high`/`identify_medium`/`identify_low`/`identify_none`/`identify_skipped`/`identify_lb_hits`) |
| Activity | `MainActivity.kt` (`enableEdgeToEdge`; `onStart` → `MusicPlayerViewModel.onAppForeground` para status background + resume warm; unknown-sources launcher) |
| Manifest / permisos / services | `app/src/main/AndroidManifest.xml` (`MusicService` + `MediaButtonReceiver`; Android Auto `@xml/automotive_app_desc`; FGS permissions; FileProvider; backup rules) |
| Reglas de backup | `res/xml/data_extraction_rules.xml` (API 31+, `<cloud-backup>` excluye Room + `library_settings` + token LB; `<device-transfer>` permite casi todo) y `res/xml/backup_rules.xml` (API ≤ 30). Restaurar Room sin los archivos de `Music/BestiaPop` deja filas irreproducibles, y el flag `initial_library_scan_completed` restaurado impide el rescan que las reconstruye |
| Gradle app | `app/build.gradle.kts` (`signingConfigs.release`, debug+release same cert si hay keystore, `versionCode`/`versionName` desde `version.properties`, `BuildConfig.GITHUB_REPOSITORY` desde `github-release.properties`, `targetSdk` 36, release R8 + resource shrinking + `ndk.debugSymbolLevel`, Firebase Crashlytics sin Analytics); catálogo `gradle/libs.versions.toml` (`media3 = 1.11.0`) |
| R8 keep rules | `app/proguard-rules.pro` (keep `com.bestiapop.android.**`, Ktor/Room; mapping en AAB) |
| Versión release | `version.properties` (`VERSION_CODE` / `VERSION_NAME`; bump en `release.sh` vía `scripts/version.sh`) |
| Repo GitHub Releases | `github-release.properties` (`GITHUB_REPOSITORY` → `BuildConfig.GITHUB_REPOSITORY`) |
| Deploy dispositivo | `install.sh` (`--debug` default, `--release`; `adb install -r -d`, fallback `cmd package uninstall -k`; `appops RUN_ANY_IN_BACKGROUND allow`) |
| Diagnóstico bloqueo | `scripts/check-locked-playback.sh` (no instala/borra: comprueba PID, MediaSession, FGS y notif 1001 con pantalla apagada) |
| Deploy GitHub Releases | `release.sh` (bump, validación tag + `resolve_release_notes` antes de buildear, `assembleRelease`, `dist/BestiaPop-*.apk`, `gh release create --latest [--target commit]`; notas: `--notes-file` / `--notes` / `CHANGELOG.release-notes.md` / plantilla, `ensure_notes_have_body` + `ensure_version_code_in_notes`; `verify_published_release` post-publish) |
| Deploy Play Console | `deploy-play.sh` (legacy; track default `alpha`, bump vía `scripts/version.sh`, ícono `play/icon.png` → mipmap, `bundleRelease`, `dist/*.aab`, `--upload --rollout`) |
| Ícono launcher / Play hi-res | `play/icon.png` (PNG 512×512; placeholder; `AndroidManifest` `@mipmap/ic_launcher`) |
| Play feature graphic | `play/feature-graphic.png` (1024×500 RGB, ficha Console; no va en el AAB) |
| Release keystore template | `keystore.properties.example` |
| Play API service account | `play-service-account.json.example` → JSON descargado de Cloud (IAM → Keys), no de Play Console UI |

## UI — screens

| Pantalla | Archivo |
|----------|---------|
| Shell + bottom nav | `ui/screens/MainScreen.kt` (`ConfirmMergeAlbumsDialog` vía `pendingAlbumMerge`; `AppUpdateDialogs` + check al abrir; banner `blocksBackgroundPlayback` + `restrictionGuidance`; diálogo OEM `oemScreenOffCleanupIntent` una vez) |
| System back (exit doble + orquestación) | `MainScreen` `BackHandler` + `SnackbarHost`; nested en screens abajo; identify review overlay |
| Biblioteca | `ui/screens/LibraryScreen.kt` (`BackHandler`: multi-select / addition / album-artist-genre / search; Tune → `LibraryBrowseSortSheet`; chips `LibraryFilterChipRow`; `PlayShuffleIconPair`; `LibrarySongListHost` + `rememberSongActionDialogs`) |
| Identify review | `ui/screens/IdentifyReviewScreen.kt` (`IdentifyReviewOverview`, `IdentifyAlbumGroupCard`, `IdentifyCandidateRow`, `IdentifySearchBlock` + filtros, `IdentifyLoadMoreButton`; Aplicar automático / Omitir todas / Aplicar grupo; overlay en `MainScreen`) |
| Lista canciones / álbumes / artistas / géneros | `ui/screens/library/LibrarySongList.kt`, `LibrarySongListHost.kt` (`LibrarySongListActions`, `rememberSongActionDialogs` / `SongActionDialogsHost`, `AlbumEditDialogsHost`), `LibraryFilterChipRow.kt`, `LibraryBrowseSortSheet.kt` (`libraryOrderSummary`, `SortOption.sortLabel`), `LibraryAlbumBrowseList.kt` (`TauonAlbumHeader`), `AlbumEditCoverMenuItems.kt`, `LibraryAggregateListItem.kt` (fila artista/género), `LibraryArtistList.kt`, `LibraryGenreList.kt`, `LibraryDialogs.kt` (`EditSongMetadataDialog` Nº de pista, `EditAlbumMetadataDialog`, `ConfirmMergeAlbumsDialog`, `SetAlbumArtworkDialog`), `LibraryProgressBanner.kt` + `IdentifyPendingBanner` |
| Playlists | `ui/screens/PlaylistsScreen.kt` (`BackHandler`: CF → LB → local detail; play/shuffle vía `LabeledPlayShuffleButtons` + `playCollection`/`shuffleCollection`; `MatchedTrackLazyColumn`; `matchedStreamCountLabel`; `rememberSongQueueActions`) |
| Ajustes / ListenBrainz | `ui/screens/SettingsScreen.kt` (`BackHandler` sección; home **Actualización** (sección `Update`) + **Invitar amigos** → `/releases/latest`; `VERSION_NAME`), `ListenBrainzSettingsScreen.kt` |
| App update UI | `ui/update/AppUpdateViewModel.kt` (`AppUpdateDependencies`: gateway/store/clock/debug/installer inyectables; constructor `Application` conserva factory; `maybeCheckOnLaunch` / `refreshReleases` / `startUpdate` / `confirmUpdate` / `launchInstaller`; `AppUpdateUiState` instalación + `AppReleaseNotesState` notas); `ui/update/AppUpdateScreen.kt` (Ajustes → Actualización); `ui/update/AppUpdateDialogs.kt` (`Available` / `Downloading` / `Error`) |
| Ajustes / Reproducción | `ui/screens/PlaybackSettingsScreen.kt` (`SettingsScreen` sección `Playback`; slider online; Batería separa `blocksBackgroundPlayback` vs Doze; copy `backgroundRestrictionGuidance`; botón OEM `openOemScreenOffCleanupSettings` si el intent resuelve) |
| Ajustes / Sonido | `ui/screens/VolumeBoostSettingsScreen.kt` (`SettingsScreen` sección `Sound`) |
| Ajustes / Descargas | `ui/screens/DownloadSettingsScreen.kt` (`SettingsScreen` sección `Downloads`; metered + path + totales bytes; deep-link `pendingSettingsSection`) |
| Ajustes / Archivos | `ui/screens/LibraryTagWriteSettingsScreen.kt` (`SettingsScreen` sección `LibraryTags`; auto-write toggle + batch sync) |
| Now playing | `ui/screens/NowPlayingScreen.kt` (`BackHandler` → `onDismiss`; cola `displayQueue` vía `QueueLazyList`; ⋮ `NowPlayingActionsMenu`; remoto `NowPlayingRemoteDownloadAction`; hero `ArtworkHero`) |
| Cola | `ui/screens/QueueScreen.kt` (`QueueLazyList` + `displayQueue`, drag → `moveDisplayQueueItem`) |
| WiFi sync | `ui/screens/WebServerScreen.kt` (`WebServerScreen(viewModel)` + transferencias + botón conflictos + `rememberSongActionDialogs`) |
| Descargas | `ui/screens/DownloadsScreen.kt` (banner `backgroundExecutionStatus`; `DownloadsHeader` resume/clear; path + `ActiveDownloadRow`) |
| Temas | `ui/screens/ThemeSettingsScreen.kt` |

## UI — components / state / theme

| Concern | Archivo |
|---------|---------|
| ViewModel central | `ui/MusicPlayerViewModel.kt` (façade playback/download: adjunta `PlaybackRuntime`, expone `ProcessDownloadRuntime.downloads`/conflict/events, `runTrackedDownload` solo adapta `ProcessDownloadRequest`; estado cohesivo vía `libraryProjection`, `navigation`, `catalogSearch`, `catalogCollection`, `lbDiscover`, `lbPlaylistDetail`, `cfRecommendations`; no posee jobs ni notificación de descarga) |
| Mini player | `ui/components/BottomPlayerBar.kt` (`statusLabel`, Previous/Next/Play; `BottomPlayerProgress` es el único collector del tick 200 ms); wiring `ui/screens/MainScreen.kt`; estado desde los flows de `PlaybackRuntime` reexpuestos por `MusicPlayerViewModel` |
| Active download row | `ui/components/ActiveDownloadRow.kt` |
| Download conflict dialog | `ui/components/DownloadConflictDialog.kt` |
| Add / download music | `ui/components/AddMusicDialog.kt` (carga catálogo al componer por primera vez el tab; buscador con filtros especiales artista/álbum/año y UI compacta; preview colecta posición solo en progress/preview activos; banners vía `activeDownloads` + `ActiveDownloadsSummaryBanner`; `BackHandler` step-back colección) |
| Song row | `ui/components/SongListItem.kt` (`SongOverflowMenuItems`) |
| Track meta row | `ui/components/TrackMetaRow.kt` (`joinMeta`, `TrackMeta.artistAlbumLabel`, `playingRowColors` / `playingTitleStyle`, `TrackTextColumn`, `TrackMetaRow`) |
| Song queue actions | `ui/components/SongQueueActions.kt` (`SongQueueActions`, `rememberSongQueueActions`) |
| Matched local/remote row | `ui/components/MatchedTrackRow.kt` (`isCurrentPlaying`, `isMatchedTrackPlaying`, `MatchedTrackRow` + overflow opcional); `MatchedTrackLazyColumn.kt` |
| Remote placeholder row | `ui/components/RemoteTrackPlaceholderRow.kt` |
| Queue list / row | `ui/components/QueueLazyList.kt` (`queueRowKey` / `focusedQueueIndex`, ambos por `queueEntryId`); `ui/components/QueueItemRow.kt` (`PlayableItemRowContent`, `QueueItemRow` + drag handle) |
| Empty / back header | `ui/components/EmptyListHint.kt` (`actionLabel` / `onAction`); `ui/components/ScreenBackHeader.kt` (`backContentDescription`, default `"Volver"`) |
| Playback chrome | `ui/components/PlaybackUi.kt` (`playPauseVector`, `playbackProgressFraction`, `previewProgressFraction`, `previewFlags`) |
| Control Radio | `ui/components/RadioModeControl.kt` (tap preferred; long-press `RadioMode.entries` KNOWN/NEW/BOTH + Detener; usado por `NowPlayingScreen`) |
| Artwork UI | `ui/components/ArtworkThumbnail.kt` (`rememberArtworkRequest`, `ArtworkThumbnail`, `ArtworkHero`); `ui/components/ArtworkPicker.kt` (`ArtworkPickerBlock`, `rememberImagePicker`) |
| Play / shuffle icons | `ui/components/PlayShuffleButtons.kt` (`PlayIconButton`, `ShuffleIconButton`, `PlayShuffleIconPair`, `LabeledPlayShuffleButtons`) |
| Settings switch row | `ui/components/SettingsSwitchRow.kt` (`SettingsSwitchRow`, `SettingsScrollColumn`) |
| Download action widgets | `ui/components/DownloadActionWidgets.kt` (`DownloadStateTrailing`, `downloadStateStatusLabel`, `DownloadSuccessReadyLabel`, `List<ActiveDownload>.findUiDownloadByTrack`, progress/queued/retry/preview primitives) |
| Matched tracks UI | `ui/components/MatchedTrackLazyColumn.kt` (`MatchedTrackListItem.meta: TrackMeta`); `MatchedTrackRow` (L2 `meta`, L1 flat title/artist) |
| Multi-select bar | `ui/components/MultiSelectActionBar.kt` (`onIdentifySelected`, `onSimilarSelected`) |
| Similar preview dialog | `ui/components/SimilarPlaylistPreviewDialog.kt`; state `ui/state/SimilarPlaylistPreviewState.kt` |
| Factories / carga UI | `ui/state/UiStateFlows.kt` (`stateInUi`, `mapToUiState`) y `LoadableUiState.kt` (`LoadPhase`, `LoadableUiState.loading` / `success` / `failure` / `idle`) |
| Proyecciones biblioteca | `ui/state/LibraryProjectionState.kt` (`songs`, `albums`, `artists`, `genres`, `buildListItems`; sharing granular, `Dispatchers.Default`) |
| Catálogo agrupado | `ui/state/CatalogUiState.kt` (`CatalogSearchUiState`, `CatalogCollectionUiState`, `CatalogCollectionKind`) |
| Navegación agrupada | `ui/state/UiNavigationState.kt` (`UiNavigationState.toSnapshot` / `fromSnapshot`, `LibraryBrowseStack` transitions); detalle `ui/state/PlaylistDetailNav.kt` |
| Sort helper UI | `ui/components/SortEmphasis.kt` (`sortEmphasisFor`, `sortEmphasisForLastPlayed`); aggregate rows still `SortRelevantInfo.kt` |
| Color picker | `ui/components/ColorPickerDialog.kt` |
| Library list model | `ui/state/LibraryListItem.kt`; `LibraryUiState.kt` conserva solo `LibraryViewMode` |
| Playlist / nav detail | `ui/state/UiNavigationState.kt` + `PlaylistDetailNav.kt` (`None` / `Local` / `ListenBrainz` / `CfRecommendations`) |
| Discover playback origin | `data/model/DiscoverPlaybackOrigin.kt` (`None` / `ListenBrainz` / `CfRecommendations`, `MatchedLbPlaylist.toDiscoverOrigin`); process state `PlaybackRuntime.discoverPlaybackOrigin`, VM solo lo expone |
| NP song/album actions | `ui/screens/NowPlayingActions.kt` (`NowPlayingActionsMenu`) |
| Identify review state | `ui/state/IdentifyReviewState.kt` (`IdentifyReviewItem`, `IdentifyReviewPhase`, `IdentifyReviewState` `isVisible` / `pendingCount` / `albumGroups` / `canApplyRemaining` / `identifyReviewFromPersisted`) |
| Theme Compose | `ui/theme/Theme.kt`, `ThemePresets.kt`, `ListDensity.kt` (row/artwork/chip density tokens) |

## Domain

| Use case | Archivo | Responsabilidad |
|----------|---------|-----------------|
| `GetLibrarySongsUseCase` | `domain/usecase/GetLibrarySongsUseCase.kt` | filter (query normalizada una vez), sort (`SortOption` + `SortDirection`), album groups (`compareSongsWithinAlbum` / `sortSongsWithinAlbum` / `songsFromListItems`), `extractAlbums` / `extractArtists` / `extractGenres` (aggregate sort + `sortedAggregates`), `songsForBrowseProjection` por buckets case-insensitive O(n); wiring reactivo en `ui/state/LibraryProjectionState.kt` |
| `LibraryBrowseFilter` | `ui/state/LibraryBrowseFilter.kt` | chips SONGS/ALBUMS/ARTISTS/GENRES/RECENT |
| `DownloadAudioTrackUseCase` | `domain/usecase/DownloadAudioTrackUseCase.kt` | wrap download Result (re-lanza `CancellationException`) |
| `MatchListenBrainzTracksUseCase` | `domain/usecase/MatchListenBrainzTracksUseCase.kt` | match LB tracks → local `Song` (delega a `TrackMatchKeys`) |
| `TrackMatchKeys` | `domain/util/TrackMatchKeys.kt` | `normalize` (case + punct + diacríticos) / `containsNormalized` / `matchKey` / `downloadIdFor` / `batchDownloadIdFor` / `downloadIdVariantsFor` (plano + `batch:`, única fuente del anti-duplicado) / `buildLibraryIndex` (= `buildIndex`) / `buildIndex` / `lookupLocalSong` / `matchAgainstLibrary` / `matchMetasAgainstLibrary`; `List<MatchedRemoteTrack>.rematchLocals` en `MatchedRemoteTrack.kt` |
| Album name normalize | `domain/util/AlbumNames.kt` | `normalizeAlbumName` / `albumNamesMatch` (merge conflict + saves) |
| Album track encode | `data/util/AlbumTrackNumbers.kt` | `encodeAlbumTrack` / `albumTrackDisplayNumber` / `albumDiscNumber` / `albumTrackSortKey` / `parseCdTrackNumber` |
| Identify ranking | `domain/util/IdentifyRanking.kt` | `score` / `rank(limit)` / `appendCandidates` / `confidence` / `hasSevereConflict` / `stripTitleNoise` / `isGenericAlbum` / `isPlaceholderArtist` (única fuente: Unknown / YouTube Artist / Enlace Web / track#) / `isPreferredProvider` (incl. ListenBrainz) / `cleanIdentityTitle` / `strongVersionMarkers`; `Query.sourceArtist`/`sourceTitle`/`sourceAlbum`/`preferYear`; `IdentifyCatalogQuery.build` |
| Filename identity hints | `domain/util/FilenameIdentityHints.kt` | `parseFilenameMetadataHints` / `resolveWeakIdentityHints` (delega `IdentifyRanking.isPlaceholderArtist`) / `mergeIdentityHints` / `isTrackNumberLabel` / `stripLeadingTitleJunk` / `splitArtistTitleDash` |
| Identify album groups | `domain/util/IdentifyAlbumGroups.kt` | `IdentifyAlbumGroup` / `clusterIdentifyAlbumGroups` / `albumGroupKey` |
| `ImportListenBrainzPlaylistUseCase` | `domain/usecase/ImportListenBrainzPlaylistUseCase.kt` | create Room playlist: matched + `PlaylistPendingTrack` metadata |
| `BuildSimilarPlaylistPreviewUseCase` | `domain/usecase/BuildSimilarPlaylistPreviewUseCase.kt` | multi-seed `RadioEngine.suggestFromSeeds` + co-playlist union; `createPlaylistFromPlayables` (Local + pending Remote) |
| `FetchAndMatchCfRecommendationsUseCase` | `domain/usecase/FetchAndMatchCfRecommendationsUseCase.kt` | CF mbids → metadata → Local/Remote |
| `BrowseLocalLibraryUseCase` | `domain/usecase/BrowseLocalLibraryUseCase.kt` | snapshot local para Auto/AVRCP, álbum/artista/search y paginación |
| `createBestiaPopRadioEngine` | `domain/radio/BestiaPopRadioEngineFactory.kt` | arma el único grafo de radio compartido por runtime y preview UI |
| `RadioEngine` | `domain/radio/RadioEngine.kt` | orquesta KNOWN / NEW / BOTH; `suggest` / `suggestFromSeeds`; fill LB→CF→`SimilarTracksProvider`; `interleaveEquitable` / `roundRobinMerge`; `RadioSuggestResult` |
| `SimilarTracksProvider` | `domain/radio/SimilarTracksProvider.kt` | contrato de fill remoto (Deezer, futuros) |
| `LocalMetadataRadio` | `domain/radio/LocalMetadataRadio.kt` | score biblioteca (artista/género/año/álbum/co-playlist) |
| `ListenBrainzRadio` | `domain/radio/ListenBrainzRadio.kt` | lb-radio → Local/Remote |
| `CfRecommendationsRadio` | `domain/radio/CfRecommendationsRadio.kt` | CF pool cache → Local/Remote (fill Radio NEW/BOTH) |
| `DeezerSimilarRadio` | `domain/radio/DeezerSimilarRadio.kt` | Deezer radio/related + iTunes same-artist fill → Remote |
| `RadioMode` | `domain/radio/RadioMode.kt` | `KNOWN` / `NEW` / `BOTH` |
| Puerto | `domain/repository/IMusicRepository.kt` | contrato repositorio (`getPlaylistIdsForSong`, `getCoPlaylistSongIds`, `touchSongLastPlayed`, `LibraryScanProgress`, `proposeSongIdentity` (+ `listenBrainzToken`, `IdentifySearchFilters`, paginación), `applySongIdentity`, `identifySongMetadata`, `syncTagsToFiles`, `saveUploadedSong(Song)`, `setAlbumArtwork(albumKey, artworkUri)` solo-portada vs `updateAlbumMetadataPropagateToSongs`) |

## Data

| Concern | Archivo |
|---------|---------|
| Repo impl | `data/repository/MusicRepository.kt` (`scanMediaStore`, `resyncAppManagedMusic`, `scanFolderUri`, `proposeSongIdentity` (+ `listenBrainzToken`, `IdentifySearchFilters` / `IdentifyCatalogQuery`, soft `persistWeakIdentityCleanup`, edge LB via `fetchListenBrainzIdentifyTrack`, append page), `applySongIdentity`, `identifySongMetadata`, `maybeWriteTags` / `syncTagsToFiles`, `migrateCanonicalAudioUris`) |
| Identidad de track | `data/model/TrackIdentity.kt` (`TrackMeta`, `TrackIdentity`, `mergePreferring`, `youtubeSearchQuery`, `toCatalogTrack`, `toListenBrainzCatalogTrack`, `preferMetaFrom`, `Song.toIdentity`, `Song.withIdentity`, `OnlineCatalogTrack.withIdentity`, `DEFAULT_CATALOG_USER_AGENT`); JSON shared `data/util/TrackIdentityJson.kt` (`putInto` / `decode`, compat `artworkUrl`→`artworkUri`) |
| Modelos dominio UI | `data/model/Models.kt`: `OnlineCatalogTrack` / candidates/catalog; `ActiveDownload` (`TrackMeta`, transitions `asDownloading`/`asConflict`/`asSuccess`/`asError`, `restoredConflictPolicy`); `DownloadLane` + `ActiveDownloadSource.lane`; `CandidateDownloadState.isInFlight/isFailed`; único `DownloadPlaylistDestination` + `resolveDownloadPlaylistDestinations`; playlists/WiFi/identify models. `Song.kt` / `AlbumOverride.kt` = Room planos |
| Cola Local/Remote | `data/model/PlayableItem.kt` (`PlayableItem` : `TrackMeta`; Local y Remote llevan `queueEntryId`; `withFreshQueueEntryIds`, `indexOfQueueEntry` / `indexOfRemoteSlot`, `matchesSong` / `matchesItem`, `ResolvedStream`, `Song.toPlayable`, `remoteFrom` identity + args, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack` / `withIdentity`) |
| Room DB | `data/db/AppDatabase.kt` (v9; `MIGRATION_7_8` `lastPlayedAt`, `MIGRATION_8_9` pending `trackNumber`) |
| DAO | `data/db/MusicDao.kt` (`insertSong` / `insertSongs` = `OnConflictStrategy.IGNORE`, `getSongByUri`, `deletePlaylistRefsForSongs`, `getPlaylistIdsForSong`, `getCoPlaylistSongIds`, `updateLastPlayedAt`) |
| Song entity + MediaStore | `data/model/Song.kt` (`lastPlayedAt`); `data/db/MediaStoreSongMapper.kt` (`Cursor.toSong`) |
| Album overrides | `data/model/AlbumOverride.kt`; DAO `getAllAlbumOverridesFlow` / `upsertAlbumOverride`; repo `persistOverride` (sanitize art/name), `setAlbumArtwork` (solo portada), `insertOrUpdateByUri` (alta preservando id) |
| Album merge | `IMusicRepository.mergeAlbumInto` → `MusicRepository.mergeAlbumInto` + `MusicDao.updateSongsAlbumMetadata` / `getSongsForAlbum` |
| Playlist entities | `data/db/PlaylistEntities.kt` (`PlaylistPendingTrackEntity` plano, columnas `releaseName` + `trackNumber`); mapper `toPendingTrack` / `toEntity` en `MusicRepository.kt` (`album`/`trackNumber` ↔ columnas) |
| Catálogo / lyrics / covers web | `data/network/MetadataFetcher.kt` (`JSONObject.toDeezerTrackIdentity` / `toItunesTrackIdentity`, `parseDeezerTrackArray` → `List<TrackIdentity>`, `parseDeezerSearchTracks`, `parseItunesSongResults`, `parseCatalogGenres`, `listGenres`, `fetchChartTracks`, `searchTracksByGenre`, `fetchFullTrackMetadata` → `TrackIdentity?`, `toCatalogCandidate`, `searchDeezerArtist`, `resolveDeezerArtistId`, `fetchDeezerArtistRadio`, `fetchDeezerRelatedArtistIds`, `fetchDeezerArtistTop`, `fetchItunesArtistSongs`) |
| YouTube search + stream | `data/network/YouTubeExtractor.kt` (`YouTubeStreamResult(identity, videoId, audioUrl, userAgent)` : `TrackMeta` + invoke L2, `extractYouTubeId`, `searchYouTube`, `parseSearchContents`, `audioPreferenceScore`, `rankByAudioPreference`, `resolveYouTubeQueryOrId`) |
| Stream resolve + cache TTL | `data/stream/StreamResolver.kt` (`resolveForPlayback(item, maxCachedAgeMs)` limita edad de playback; `resolve` compat; `resolveQuery(forceRefresh)` download; `invalidate(item)` borra claves `id:` + `q:`; `withKeyLock` serializa por query con reserva ref-counted antes del lock; `pruneKeyLocksLocked` nunca poda entregados y `pruneLocked` acota cache). Instancia playback única en `BestiaPopApplication.musicRepository`; `PlaybackRuntime` usa 60s |
| Clientes HTTP compartidos | `data/network/HttpClients.kt` (`api` con `callTimeout`, `transfer` sin cap para bytes). Todos los módulos derivan con `newBuilder()`; antes había seis clientes independientes y ninguno con `callTimeout` |
| Playable factories | `data/model/PlayableItem.kt` (`remoteFrom(identity)` / `remoteFrom(artist, title, …)`, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack`, `Remote.withIdentity`) |
| Theme DataStore | `data/preferences/ThemePreferencesRepository.kt` |
| Library initial scan + UI prefs | `data/preferences/LibraryPreferencesRepository.kt` (`isInitialScanCompleted`, `displaySettingsFlow`, `navSnapshotFlow`, `setSortOptionName` / `setSortDirectionName` / `setViewModeName` / `setNavSnapshot`); codec `LibraryUiPreferences.kt` (`LibraryUiPreferencesCodec`, `UiNavSnapshot.browseFilterName` + legacy `library_tab`, `LibraryDisplaySettings` sort+direction, `sanitizeBrowseFilterName` / `sanitizeSortDirectionName`) |
| Playback / sonido + modos | `data/preferences/PlaybackPreferencesRepository.kt` (`PlaybackSettings`, `PlaybackModeRestore`, `PlaybackModeClear.afterManualPlay` / `afterSkip` / `afterRadioStart`, `parseRepeatModeName`, `MAX_VOLUME_BOOST_GAIN_MB`, `stereoLeftGain` / `stereoRightGain`, `rememberShuffleOnLaunch` / `rememberRepeatOnLaunch` / `autoplayOnLaunch`, `lastShuffleEnabled` / `lastRepeatMode`, clear-on-play/skip, `oemScreenOffCleanupHintDismissed`); writes 1-key vía `DataStorePrefs.kt` `put`; readiness process-scoped vía `PlaybackRuntime.awaitPlaybackSettings` |
| Active downloads persist | `data/preferences/ActiveDownloadsStore.kt` (`ActiveDownloadCodec`: request, todos los `DownloadPlaylistDestination`, `lookupIdentity`, `batchId`, overwrite/SaveAs, `downloadStarted`/`storageCommitted`, marker `interrupted`, compat legacy; `activeDownloadBadgeCount`) detrás del único writer `ProcessDownloadCoordinator` |
| Identify review persist | `data/preferences/IdentifyReviewStore.kt` (`IdentifyReviewCodec`, `PersistedIdentifyReviewQueue`; sin `audioUrl` CDN) |
| Last-played + cola persistida | `data/preferences/PlaybackSessionStore.kt` (`LastPlayedCodec`, `QueueSnapshotCodec`, `PlaybackHydration.hydrateQueue` — cola Remote-only aun con biblioteca vacía + ids frescos—, `saveSession`, `LastPlayedSnapshot(identity)`, `QueueSnapshot` + `shufflePlayOrder`, `PersistedQueueItem`, `HydratedQueue`) |
| Wrap / trim / shuffle índices | `data/playback/PlaybackQueueOrder.kt` (`rotateToStart`, `trimHistory`, `shufflePlayOrder`, `reshufflePlayOrder`, `insertAfterCurrent`, `appendToPlayOrder`, `removeFromPlayOrder`, `moveInPlayOrder`, `remapPlayOrder`, `MAX_QUEUE_HISTORY`) |
| Slots / snapshot shuffle | `data/playback/PlaybackQueueSlots.kt` (`capturePreShuffleOrder`, `restorePreShuffleOrder`, `projectSnapshot`; identidad por `queueEntryId`) |
| Fallback circular | `data/playback/PlaybackFallbackPlanner.kt` (`PlaybackFallbackPlanner.circularPlan`, `PlaybackFallbackStep.ReadyLocal` / `ResolveRemote`) |
| Intención latest-wins | `data/playback/PlaybackSelectionIntentGate.kt` (`PlaybackSelectionIntentGate.beginRemoteSelection` / `isCurrent` / `invalidate` / `onLocalSelected`) |
| Cambio de track | `data/playback/PlaybackTrackChangePolicy.kt` (`PlaybackChangeHint`, `PlaybackTrackChange`, `PlaybackTrackChangePolicy.resolve` / `sameIdentity`) |
| ListenBrainz prefs | `data/preferences/ListenBrainzPreferencesRepository.kt` |
| Download prefs | `data/preferences/DownloadPreferencesRepository.kt` (`DownloadSettings`, `downloadOnMeteredNetwork` default true, `addDownloadedBytes`, `totalMeteredBytes` / `totalUnmeteredBytes`) |
| Tag-write prefs | `data/preferences/LibraryTagWritePreferencesRepository.kt` (`LibraryTagWriteSettings.autoWriteTagsEnabled` default false) |
| ListenBrainz API | `data/network/ListenBrainzClient.kt` (`submitListens`, createdfor, playlist, `lookupRecordingMetadata`, `fetchLbRadioArtist`, `fetchRecordingMetadata`, `fetchCfRecordingRecommendations`, `parseCfRecommendations`) |
| LB models + sync | `data/listenbrainz/LbPlaylistModels.kt` (`LbPlaylistTrack(identity, mbid)` + invoke plano, `MatchedLbPlaylist.toPlayableItems`, `streamCount`), `data/listenbrainz/MatchedRemoteTrack.kt` (mappers/match/rematch), `data/listenbrainz/LbRadioModels.kt`, `data/listenbrainz/CfRecommendationModels.kt`, `data/listenbrainz/ListenTracker.kt` (`onTrackChanged`), `data/listenbrainz/ListenSyncCoordinator.kt` (`requestSync`) |
| Guardar al escuchar policy | `data/listenbrainz/SaveWhileListeningPolicy.kt` (`SaveWhileListeningEvent`; `SaveWhileListeningPolicy.shouldSave`) |
| Connectivity | `data/network/ConnectivityObserver.kt` (`isCurrentlyOnline`, `isMetered`, `networkTypeLabel`; override internal `configureForTest` / `resetTestOverrides` para fronteras E2E herméticas) |
| Background execution | `data/system/BackgroundExecutionStatus.kt` (`BackgroundExecutionProbe.current`; `openApplicationDetails` / `applicationDetailsIntent`; `restrictionGuidance` / `backgroundRestrictionGuidance`; `oemScreenOffCleanupIntent` / `openOemScreenOffCleanupSettings` → `unisoc.intent.action.POWER_BACKGROUND_CLEAN` + `lock_screen_battery_save`; señales `backgroundRestricted` / `runAnyInBackgroundIgnored` / `ignoringBatteryOptimizations`; `blocksBackgroundPlayback`) |
| GitHub Releases update | `data/update/GitHubReleaseParser.kt` (`parseReleases` / `parseRelease` / `parseVersionCode` / `stripVersionCodeLine`); `GitHubUpdateClient.fetchReleases`; `data/update/AppReleaseSelection.kt` (`from`, `updateTarget`); `data/update/ApkUpdateInstaller.kt` (`ApkUpdateDownloader.download`: `.part`, HTTP/length, `ApkValidator` / `PackageManagerApkValidator`, move atómico + cleanup; `ApkUpdateInstaller` FileProvider/instalador); `AppUpdateCheckStore` (`lastCheckAtMs`, `cachedNotes` / `setCachedNotes`); `data/update/AppRelease.kt` (`AppRelease`, `GitHubReleaseUrls.repoUrl` / `latestPageUrl` / `apiReleasesUrl`) |
| Pending listens Room | `data/db/PendingListenEntity.kt`, `PendingListenDao.kt` |
| Storage / transfer helpers | `data/util/MusicFileStore.kt`, `AudioPersistRef.kt`, `StorageUtils.kt` (PendingWrite/publish/list/delete), `TransferIo.kt` (`copyTransferToFile`: L1 compartido música+APK; políticas quedan arriba), `SongPathNormalizer.kt`, `UploadNameSanitizer.kt`, `AudioFileMetadata.fromPath` (un retriever para tags+embedded art, callback `persistEmbeddedArtwork`), `AudioTagWriter.kt` |
| Download conflict models | `data/model/Models.kt` (`DownloadConflictPolicy`, `DuplicateSongException`, `DownloadConflict`) |
| One-shot dedup archive | branch `archive/library-dedup-v1-migrator` (`LibraryDedupMigrator` / `LibraryDedupLogic` / prefs; not on LB) |

## Services

| Servicio | Archivo |
|----------|---------|
| Playback runtime process-scoped | `service/PlaybackRuntime.kt` (`playPlayableCollection`; `systemResumptionMetadataSnapshot` puro / `restoreSystemPlaybackSnapshot` mutante bajo mutex compartido; `stageExternalPlayableCollection` para browse; reconnect/generaciones/fallback/prefetch; writer serial; ticker/tracker/autosave/radio) |
| Frontera Media3 portable | `service/PlaybackMediaItemCodec.kt` (`encode` / `decode`, payload v1 Local/Remote; CDN solo URI); `service/PlaybackMediaMetadata.kt` (builder común desde `TrackMeta`); `service/StreamPlaybackTag.kt` (UA en extras) |
| Playback Media3 + notif/lifecycle FGS | `service/MusicService.kt` (`onUpdateNotificationAsync`; `maybeNotifyBackgroundRestriction`; `shouldResumeAfterStickyRestart`; `onTaskRemoved`; `requestResumeAfterServiceRestart` en runtime; shuffle; HTTP googlevideo; `playbackWakeMode`) + `service/RefreshingMediaNotificationProvider.kt` (artwork async vuelve por el hook para conservar Remote IDLE); `service/library/BestiaPopMediaLibraryCallback.kt` (`onConnectAsync` + `untrustedTransportPlayerCommands` para transporte-only de launchers/widgets; resumption/browse/search/set items); `PlaybackSystemResumption.kt`; `PlaybackServiceLifetimePolicy` |
| Browse Auto/AVRCP | `service/library/MediaLibraryIds.kt`, `MediaLibraryBrowseMapper.kt`, `MediaLibraryBrowseProvider.kt` (sources versionadas con `stateIn`; snapshot lazy cacheado bajo mutex y construido en Default), `BestiaPopMediaLibraryCallback.kt`; playlist ordenada `MusicDao.getPlaylistSongsOrdered`; manifest `automotive_app_desc.xml` |
| Registry de descargas process-scoped | `service/ProcessDownloadCoordinator.kt` (`execute`, claim aliases, permit 3, `DownloadPlaylistDestination`, persist, cancel; `updateProgress` coalesced + writes estructurales inmediatos; `interruptNow(lane)` / `dismissRunning(lane)`). `BestiaPopApplication` instala callback add/remove pending |
| Runtime de descargas process-scoped | `service/ProcessDownloadRuntime.kt` (`ProcessDownloadRequest`, private `DownloadExecutionContext`, submit/retry/resume/conflict, durable lease/reconcile; façade UI `isRunning` / `findClaimedDownload` / `attachPlaylistDestination` / `upsertRow`; lane settle/stop) |
| Lifetime descargas Android | `OnlineDownloadServiceLauncher.kt` (backend→lane, lease ref-counted, `settleBackend`, shared `scheduleJob`); `OnlineDownloadServiceSupport.kt` (`settleOnlineDownloadLifetime`, notification collector, stop policy); tres servicios conservan primitives UIDT/FGS/job |
| Guardar al escuchar process-scoped | `service/ProcessSaveWhileListeningCoordinator.kt` (`save` / `dismiss`; usa el registry compartido, éxito inmediato si ya existe, `SaveWhileListeningDownloadResult.InFlight` neutral si el claim pertenece a otro owner) |
| Stereo balance (PCM) | `service/StereoBalanceAudioProcessor.kt` (`queueInput`) + `MusicService.applyStereoBalance`; seam instrumentado debug `MusicServiceSettingsProbe.observe` (`MusicServiceSettingsProbe.kt`, inerte sin observer) |
| Volume boost (LoudnessEnhancer) | `MusicService.applyBoost` + `PlaybackPreferencesRepository`; el mismo `MusicServiceSettingsProbe` observa gains/target mB sin capturar audio |
| Ktor WiFi server | `service/WebServerService.kt` (`WifiSyncHttpBoundary`; `onTimeout` → `markWifiTransfersTimedOut` + stop FGS; state/transfers; `/existing-files`); identify post-upload en VM |
| Download progress notif | `service/DownloadNotificationHelper.kt` (`EXTRA_OPEN_TAB` / `TAB_DOWNLOADS`) |

## Tests de referencia

| Tipo | Archivo |
|------|---------|
| Library list items | `app/src/test/.../GetLibrarySongsUseCaseListItemsTest.kt` |
| Album track encode / catalog parse | `app/src/test/.../AlbumTrackNumbersTest.kt`, `CatalogTrackNumberParseTest.kt` |
| YouTube extraction | `app/src/test/.../YouTubeExtractionIntegrationTest.kt` |
| YouTube audio preference | `app/src/test/.../YouTubeAudioPreferenceTest.kt` |
| StreamResolver cache/TTL | `app/src/test/.../StreamResolverTest.kt` (incl. reserva ref-counted bajo poda concurrente antes de adquirir mutex) |
| Playable mediaId / queue slot | `app/src/test/.../PlayableItemRemoteMediaIdTest.kt` (`queueEntryId` Local+Remote, duplicados, `withFreshQueueEntryIds`, `indexOfQueueEntry` / `indexOfRemoteSlot`) |
| Slots / fallback / latest intent | `app/src/test/.../PlaybackQueueSlotsTest.kt`, `PlaybackFallbackPlannerTest.kt`, `PlaybackSelectionIntentGateTest.kt` |
| Queue UI key / foco | `app/src/test/.../ui/components/QueueUiIdentityTest.kt` (duplicados exactos + reorder por `queueEntryId`) |
| Tracker / guardar al escuchar | `app/src/test/.../PlaybackTrackChangePolicyTest.kt`, `ListenTrackerTest.kt`, `SaveWhileListeningPolicyTest.kt` |
| Continuidad process/runtime | `app/src/test/.../service/PlaybackRuntimeContinuityTest.kt` (facade inválido tras disconnect + restore intención/slot/posición; generaciones contra completions transition/prefetch/recovery; fallback circular >5 + anti-cascada; seek pausado serial; readiness Playback/LB; callbacks externos repeat/shuffle/timeline; pausa resolve/buffering, display-only, persistencia, lease/ticker, detach UI/tracker/save/radio), `PlaybackServiceLifetimePolicyTest.kt` (task removed + placeholder IDLE) |
| Notificación playback / controllers externos | `PlaybackNotificationContractTest.kt` (foreground policy Remote IDLE + sticky resume + `WAKE_MODE_NONE` local); `BackgroundExecutionStatusTest.kt` (`blocksBackgroundPlayback` + `backgroundRestrictionGuidance` por OEM); `BestiaPopMediaLibraryCallbackTest.kt` (policy transporte-only no confiable); `LocalPlaybackServiceFunctionalTest.kt` (provider real, acciones/FGS); `LockedScreenPlaybackFunctionalTest.kt` (host-only sin appops) |
| Codec MediaItem | `app/src/test/.../service/PlaybackMediaItemCodecTest.kt` (round-trip portable Local/Remote; CDN omitida) |
| HTTP Range googlevideo | `app/src/test/.../service/MusicServiceRangeRequestTest.kt` (`googleVideoBoundedLength`: `clen`→longitud restante; no toca requests ya acotados, agotados ni otros hosts); `UserAgentMediaSourceFactoryTest.kt` (placeholder Remote `Uri.EMPTY` no tira) |
| Radio local / engine / Deezer | `app/src/test/.../RadioEngineTest.kt`, `DeezerSimilarRadioTest.kt` |
| LB radio / CF JSON parse | `app/src/test/.../ListenBrainzRadioParseTest.kt` |
| LB Para Ti → PlayableItem | `app/src/test/.../MatchedLbPlaylistPlayableTest.kt` |
| CF match Local|Remote | `app/src/test/.../FetchAndMatchCfRecommendationsUseCaseTest.kt` |
| ActiveDownload cycle | `app/src/test/.../ActiveDownloadCycleTest.kt` |
| ActiveDownload codec / badge | `app/src/test/.../ActiveDownloadCodecTest.kt` |
| Coordinación descargas multi-owner | `app/src/test/.../service/ProcessDownloadCoordinatorTest.kt` (dos callers/id variants, targets de varias playlists exactamente una vez, cancel real, merge persist, límite global 3) |
| Runtime/lifetime descargas | `app/src/test/.../service/ProcessDownloadRuntimeTest.kt` (owner process, resume selectivo/todo, gate metered); `OnlineDownloadServiceLauncherTest.kt` (UIDT vs FGS); `CatalogDownloadFunctionalTest.downloadContinuesAfterActivityAndViewModelAreDestroyed` |
| Transfer I/O | `app/src/test/.../data/util/TransferIoTest.kt`; integración música `MusicRepositoryDownloadIntegrationTest`; APK `ApkUpdateDownloaderTest` |
| Last-played / idle / queue hydrate | `app/src/test/.../PlaybackSessionStoreTest.kt` (incl. Remote-only + biblioteca vacía + duplicados) |
| System resumption | `PlaybackSystemResumptionTest.kt`; mutex/idempotencia en `PlaybackRuntimeContinuityTest.kt`; manifest `MediaButtonReceiver` |
| Browse Auto/AVRCP | `MediaLibraryIdsTest.kt`, `MediaLibraryBrowseMapperTest.kt`, `BrowseLocalLibraryUseCaseTest.kt`; `MediaLibraryBrowseInstrumentedTest.kt` |
| Queue wrap / trim / shuffle índices | `app/src/test/.../PlaybackQueueOrderTest.kt` |
| Queue snapshot codec | `app/src/test/.../QueueSnapshotCodecTest.kt` |
| Shuffle/repeat restore | `app/src/test/.../PlaybackModeRestoreTest.kt` |
| Shuffle/repeat clear-on-play | `app/src/test/.../PlaybackModeClearTest.kt` |
| Library UI prefs codec / nav | `app/src/test/.../LibraryUiPreferencesCodecTest.kt`, `PlaylistDetailNavTest.kt` |
| Audio persist canonicalize | `app/src/test/.../AudioPersistRefTest.kt` |
| Import LB playlist | `app/src/test/.../ImportListenBrainzPlaylistUseCaseTest.kt` |
| Path normalize | `app/src/test/.../SongPathNormalizerTest.kt` |
| Filename metadata hints | `app/src/test/.../FilenameMetadataHintsTest.kt` (`parse` rip formats + `resolveWeak` + `looksLikeStoragePath`) |
| Identify ranking | `app/src/test/.../IdentifyRankingTest.kt` |
| Audio tag writer | `app/src/test/.../AudioTagWriterTest.kt` |
| Identify album groups | `app/src/test/.../IdentifyAlbumGroupsTest.kt` |
| Identify review codec / hydrate | `app/src/test/.../IdentifyReviewCodecTest.kt` |
| GitHub release parser | `app/src/test/.../GitHubReleaseParserTest.kt` |
| Update download / VM | `app/src/test/.../data/update/ApkUpdateDownloaderTest.kt` (HTTP 500, truncado, validación/publicación) y `app/src/test/.../ui/update/AppUpdateViewModelTest.kt` (throttle, available, cache offline, error) |
| TrackIdentity JSON | `app/src/test/.../TrackIdentityJsonTest.kt` |
| TrackIdentity merge / toIdentity / youtubeSearchQuery / preferMetaFrom | `app/src/test/.../TrackIdentityTest.kt` |
| Pending mapper album/trackNumber↔entity | `app/src/test/.../PlaylistPendingTrackMapperTest.kt` |
| Match LB tracks / similar preview / download use case | `MatchListenBrainzTracksUseCaseTest.kt`, `BuildSimilarPlaylistPreviewUseCaseTest.kt`, `DownloadAudioTrackUseCaseTest.kt` |
| Listen sync queue | `ListenSyncCoordinatorTest.kt` |
| WiFi upload name sanitize | `UploadNameSanitizerTest.kt` |
| WiFi FGS timeout / Job stop | `WebServerServiceTimeoutTest.kt`, `OnlineDownloadServiceSupportTest.kt` |
| Stereo PCM pipeline | `StereoBalanceAudioProcessorTest.kt` |
| Library execute filter/sort / album override projection | `GetLibrarySongsUseCaseExecuteTest.kt`, `AlbumCoverVsPlaylistCoverTest.kt` |
| Owners UI tipados | `ui/state/UiStateFlowsTest.kt`, `LibraryProjectionStateTest.kt`, `LoadableUiStateTest.kt`, `CatalogUiStateTest.kt`, `UiNavigationStateTest.kt` |
| UI functional library | `app/src/androidTest/.../LibraryScreenFunctionalTest.kt`, `LibraryBrowseFunctionalTest.kt` |
| UI functional Settings / update | `app/src/androidTest/.../ui/settings/SettingsRuntimeFunctionalTest.kt` (tema recreate, modos persistidos, UI sonido→servicio real); `ui/update/AppUpdateFunctionalTest.kt` (GitHub mock→descarga→boundary instalador; truncado sin intent) y `PackageManagerApkValidatorInstrumentedTest.kt` (APK instalado del mismo package vs bytes inválidos) |
| UI functional downloads / remote row / cola duplicada | `DownloadsUiFunctionalTest.kt`, `PlaylistRemoteRowFunctionalTest.kt`, `QueueIdentityFunctionalTest.kt`; `ui/download/CatalogDownloadFunctionalTest.kt` (happy path + Overwrite/SaveAs/Cancel + cancel parcial + 403/retry + metered tras permit) |
| SAF / permisos instrumentados | Provider solo-debug `src/debug/AndroidManifest.xml` + `src/debug/java/.../testutil/TestAudioDocumentsProvider.kt` (mismo UID target; nunca entra al release); `data/repository/SafImportFunctionalTest.kt` (import/dedupe/playback + portada efímera), `ui/SafFolderPickerFunctionalTest.kt`, `ui/LibraryPermissionFunctionalTest.kt`; denied host-only `ui/LibraryPermissionDeniedHostE2ETest.kt` + `scripts/run-library-permission-denied-e2e.sh` |
| Discover / Radio instrumentados | `ui/discover/DiscoverE2EFunctionalTest.kt` + `DiscoverE2ETestFixture.kt` (LB/CF Local+Remote, descarga/rematch, guardar al escuchar→Room); `RadioModeControlFunctionalTest.kt`; runtime stop/refill/NEW en `PlaybackRuntimeContinuityTest.kt` |
| Continuidad instrumentada | `PlaybackContinuityFunctionalTest.kt` (pause durante resolve) |
| Lifecycle host / audio | `testutil/PlaybackDeviceProbe.kt` centraliza controller/notif/FGS/shell/main; `PlaybackProcessDeathE2ETest` + host script; `PlaybackTaskRemovalE2ETest`; `LockedScreenPlaybackFunctionalTest`; `PlaybackAudioInterruptionFunctionalTest`; `BackupRestoreE2ETest`; `DatabaseDowngradeWarningFunctionalTest` |
| E2E playlists / Identify / servicios concurrentes | `app/src/androidTest/.../ui/playlist/PlaylistCrudE2ETest.kt`, `PlaylistPendingDownloadE2ETest.kt`; `ui/identify/IdentifyE2EFunctionalTest.kt`; `service/concurrent/ConcurrentServiceOperationsE2ETest.kt` |

## Símbolos ViewModel frecuentes

Mantener esta lista alineada con `MusicPlayerViewModel.kt`:

- Biblioteca/nav: `libraryProjection.{songs,albums,artists,genres}`, `navigation` (`UiNavigationState`), `searchQuery`, `sortOption`, `setSortOption`, `sortDirection`, `setSortDirection` / `toggleSortDirection`, `libraryViewMode`, `setLibraryViewMode`, `toggleLibraryViewMode`, `setLibraryBrowseFilter`, `openLibraryAlbum`, `openLibraryArtist`, `openLibraryGenre`, `popLibraryNested`, `setSelectedNavIndex`, `openDownloadsTabTransient`, `openLocalPlaylist`, `openListenBrainzPlaylistDetail`, `openCfRecommendationsDetail`, `closePlaylistDetail`, `dismissDiscoverDetails`, `buildLibraryListItems`, `sortSongsWithinAlbum`, `songsForAlbum`, `songsFromLibraryListItems`, `songsForBrowseProjection`, `libraryJobProgress`, `importFolder`, `ensureInitialLibraryImport`, `identifySongs`, `identifySongForReview`, `identifyReview`, `previewIdentifyLocalSong`, `previewIdentifyCandidate`, `applySelectedIdentifyCandidate`, `skipIdentifyReviewItem`, `searchIdentifyCandidates`, `loadMoreIdentifyCandidates`, `dismissIdentifyReview`, `showIdentifyReview`, `applyRemainingIdentifySuggestions`, `skipAllIdentifyReview`, `applyIdentifyAlbumGroup`, `startIdentifyItemReview`, `returnIdentifyReviewOverview`; lifecycle/settings `backgroundExecutionStatus`, `onAppForeground`, `openPlaybackSettings`, `openDownloadSettings`
- Playback façade: `playSong`, `playCollection`, `playPlayableCollection`, `playMatchedCollection`, `shufflePlayableCollection`, `shuffleCollection`, `enqueueCollection`, `playNextInQueue`, `playNextBatch`, `skipToQueueIndex`, `moveDisplayQueueItem`, `togglePlayPause`, `toggleShuffle`, `toggleRepeatMode`; `currentItem`, `currentSong`, `queue`, `displayQueue`, `discoverPlaybackOrigin`, `resolvingRemote`, `repeatMode`, `isShuffle`, `queueFocusEpoch` son flows del runtime; VM conserva sonido/flags de preferencias y `playlistsContainingSong`
- Radio façade: `startRadio` / `stopRadio` / `setRadioPreferredMode`, `radioMode`, `radioStatusLabel`; implementación/refill/auto en `PlaybackRuntime`
- Artwork: `setAlbumArtwork`, `requestSaveAlbumMetadata`, `saveAlbumOverride` (private), `confirmPendingAlbumMerge`, `dismissPendingAlbumMerge`, `mergeAlbumInto`
- Online: `catalogSearch` / `catalogCollection`; `searchCatalog` / `searchOnlineCatalog` (latest-wins), categorías/colecciones, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `enqueueTrackedBatch`, `downloadFromUrl`, `downloadOnlineTrack`; `activeDownloads`/`downloadConflict` vienen de `app.processDownloadRuntime`; acciones `retryActiveDownload` / `resumeAllDownloads` / cycle/preview/play/dismiss; conflicto delega runtime; `runTrackedDownload` adapta request. Ajustes/deep-links/preview permanecen en VM
- ListenBrainz: `tokenValidation`, `lbDiscover`, `lbPlaylistDetail`, `cfRecommendations` (`LoadableUiState`); `listenBrainzSettings`, `setListenBrainzEnabled`, `setListenBrainzDiscoverEnabled`, `setListenBrainzSaveWhileListening`, `setListenBrainzSaveWhileListeningPercent`, `refreshListenBrainzDiscoverPlaylists`, `openListenBrainzPlaylist`, `openListenBrainzPlaylistDetail`, `playMatchedTracks`, `shuffleMatchedTracks`, `saveListenBrainzPlaylistAsLocal`, `importListenBrainzPlaylistWithDownloads`, `downloadPlaylistPendingTracks`, `downloadRemoteItem`, `getPlaylistPendingTracksFlow`, `refreshCfRecommendations`, `openCfRecommendations`, `openCfRecommendationsDetail`, `closePlaylistDetail`, `navigation.playlistDetail`, `discoverPlaybackOrigin`

## Cómo actualizar este mapa

Tras crear/renombrar/mover un archivo o API pública relevante:

1. Editar la fila correspondiente (o añadir sección).
2. Si el change afecta un feature, actualizar también `bestiapop-features`.
3. Si cambia capas/paquetes/stack, actualizar `bestiapop-architecture`.
