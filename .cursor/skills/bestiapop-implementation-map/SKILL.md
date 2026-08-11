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
| Application + Crashlytics init | `BestiaPopApplication.kt` |
| Crash non-fatals / keys | `data/util/CrashReporter.kt` (`setKey` / `log` / `recordNonFatal`; identify batch keys `identify_high`/`identify_medium`/`identify_low`/`identify_none`/`identify_skipped`/`identify_lb_hits`) |
| Activity | `MainActivity.kt` (`enableEdgeToEdge` — targetSdk 36; unknown-sources launcher → `AppUpdateViewModel.onReturnedFromUnknownSources`) |
| Manifest / permisos / services | `app/src/main/AndroidManifest.xml` (`AD_ID` + `ACCESS_ADSERVICES_*` `tools:node="remove"`; meta `google_analytics_adid_collection_enabled` / `firebase_analytics_collection_deactivated`; `REQUEST_INSTALL_PACKAGES`; FileProvider `${applicationId}.fileprovider` + `@xml/file_paths`) |
| Gradle app | `app/build.gradle.kts` (`signingConfigs.release`, debug+release same cert si hay keystore, `versionCode`/`versionName` desde `version.properties`, `BuildConfig.GITHUB_REPOSITORY` desde `github-release.properties`, `targetSdk` 36, release R8 + `ndk.debugSymbolLevel`, Firebase Crashlytics sin Analytics) |
| R8 keep rules | `app/proguard-rules.pro` (keep `com.bestiapop.android.**`, Ktor/Room; mapping en AAB) |
| Versión release | `version.properties` (`VERSION_CODE` / `VERSION_NAME`; bump en `release.sh` vía `scripts/version.sh`) |
| Repo GitHub Releases | `github-release.properties` (`GITHUB_REPOSITORY` → `BuildConfig.GITHUB_REPOSITORY`) |
| Deploy dispositivo | `install.sh` (`--debug` default, `--release`; `adb install -r -d`, fallback `cmd package uninstall -k`; `appops RUN_ANY_IN_BACKGROUND allow`) |
| Deploy GitHub Releases | `release.sh` (bump, `assembleRelease`, `dist/BestiaPop-*.apk`, `gh release create` con `versionCode` en notes; default notes: `--notes-file` / `--notes` / `CHANGELOG.release-notes.md`) |
| Deploy Play Console | `deploy-play.sh` (legacy; track default `alpha`, bump vía `scripts/version.sh`, ícono `play/icon.png` → mipmap, `bundleRelease`, `dist/*.aab`, `--upload --rollout`) |
| Ícono launcher / Play hi-res | `play/icon.png` (PNG 512×512; placeholder; `AndroidManifest` `@mipmap/ic_launcher`) |
| Play feature graphic | `play/feature-graphic.png` (1024×500 RGB, ficha Console; no va en el AAB) |
| Release keystore template | `keystore.properties.example` |
| Play API service account | `play-service-account.json.example` → JSON descargado de Cloud (IAM → Keys), no de Play Console UI |

## UI — screens

| Pantalla | Archivo |
|----------|---------|
| Shell + bottom nav | `ui/screens/MainScreen.kt` (`ConfirmMergeAlbumsDialog` vía `pendingAlbumMerge`; `AppUpdateDialogs` + check al abrir) |
| System back (exit doble + orquestación) | `MainScreen` `BackHandler` + `SnackbarHost`; nested en screens abajo; identify review overlay |
| Biblioteca | `ui/screens/LibraryScreen.kt` (`BackHandler`: multi-select / addition / album-artist-genre / search; `LibraryFilterChipRow` + `LibrarySongListHost` + `rememberSongActionDialogs`) |
| Identify review | `ui/screens/IdentifyReviewScreen.kt` (`IdentifyReviewOverview`, `IdentifyAlbumGroupCard`, `IdentifyCandidateRow`, `IdentifySearchBlock`; Aplicar automático / Omitir todas / Aplicar grupo; overlay en `MainScreen`) |
| Lista canciones / álbumes / artistas / géneros | `ui/screens/library/LibrarySongList.kt`, `LibrarySongListHost.kt` (`LibrarySongListActions`, `rememberSongActionDialogs` / `SongActionDialogsHost`, `AlbumEditDialogsHost`), `LibraryFilterChipRow.kt`, `LibraryAlbumBrowseList.kt` (`TauonAlbumHeader`), `LibraryAlbumGrid.kt` (legacy grid; browse usa browse list), `LibraryArtistList.kt`, `LibraryGenreList.kt`, `LibraryDialogs.kt` (`EditSongMetadataDialog` Nº de pista, `EditAlbumMetadataDialog`, `ConfirmMergeAlbumsDialog`, `SetAlbumArtworkDialog`), `LibraryProgressBanner.kt` + `IdentifyPendingBanner` |
| Playlists | `ui/screens/PlaylistsScreen.kt` (`BackHandler`: CF → LB → local detail; play/shuffle vía `LabeledPlayShuffleButtons` + `playCollection`/`shuffleCollection`; `MatchedTrackLazyColumn`; `matchedStreamCountLabel`; `rememberSongQueueActions`) |
| Ajustes / ListenBrainz | `ui/screens/SettingsScreen.kt` (`BackHandler` sección; home **Buscar actualización** + **Invitar amigos** → `/releases/latest`; `VERSION_NAME`), `ListenBrainzSettingsScreen.kt` |
| App update UI | `ui/update/AppUpdateViewModel.kt` (`maybeCheckOnLaunch` / `checkNow` / `confirmUpdate`); `ui/update/AppUpdateDialogs.kt` |
| Ajustes / Reproducción | `ui/screens/PlaybackSettingsScreen.kt` (`SettingsScreen` sección `Playback`) |
| Ajustes / Sonido | `ui/screens/VolumeBoostSettingsScreen.kt` (`SettingsScreen` sección `Sound`) |
| Ajustes / Descargas | `ui/screens/DownloadSettingsScreen.kt` (`SettingsScreen` sección `Downloads`; metered + path) |
| Now playing | `ui/screens/NowPlayingScreen.kt` (`BackHandler` → `onDismiss`; cola `displayQueue` vía `QueueLazyList`; ⋮ `NowPlayingActionsMenu`; remoto `NowPlayingRemoteDownloadAction`; hero `ArtworkHero`) |
| Cola | `ui/screens/QueueScreen.kt` (`QueueLazyList` + `displayQueue`, drag → `moveDisplayQueueItem`) |
| WiFi sync | `ui/screens/WebServerScreen.kt` (`WebServerScreen(viewModel)` + transferencias + botón conflictos + `rememberSongActionDialogs`) |
| Descargas | `ui/screens/DownloadsScreen.kt` (`DownloadsScreen(viewModel)` + `ActiveDownloadRow` + `dismissAllActiveDownloads`) |
| Temas | `ui/screens/ThemeSettingsScreen.kt` |

## UI — components / state / theme

| Concern | Archivo |
|---------|---------|
| ViewModel central | `ui/MusicPlayerViewModel.kt` (`previewSimilarFromSelection` / `dismissSimilarPreview` / `confirmSimilarPreviewAsPlaylist` / `playSimilarPreview` / `enqueueSimilarPreview`; `similarPlaylistPreview`) |
| Mini player | `ui/components/BottomPlayerBar.kt` (`statusLabel`, Previous/Next/Play) |
| Active download row | `ui/components/ActiveDownloadRow.kt` |
| Download conflict dialog | `ui/components/DownloadConflictDialog.kt` |
| Add / download music | `ui/components/AddMusicDialog.kt` (banners vía `activeDownloads` + `ActiveDownloadsSummaryBanner`; `BackHandler` step-back colección) |
| Song row | `ui/components/SongListItem.kt` (`SongOverflowMenuItems`) |
| Track meta row | `ui/components/TrackMetaRow.kt` (`joinMeta`, `TrackMeta.artistAlbumLabel`, `playingRowColors` / `playingTitleStyle`, `TrackTextColumn`, `TrackMetaRow`) |
| Song queue actions | `ui/components/SongQueueActions.kt` (`SongQueueActions`, `rememberSongQueueActions`) |
| Matched local/remote row | `ui/components/MatchedTrackRow.kt` (`isCurrentPlaying`, `isMatchedTrackPlaying`, `MatchedTrackRow` + overflow opcional); `MatchedTrackLazyColumn.kt` |
| Remote placeholder row | `ui/components/RemoteTrackPlaceholderRow.kt` |
| Queue list / row | `ui/components/QueueLazyList.kt`; `ui/components/QueueItemRow.kt` (`PlayableItemRowContent`, `QueueItemRow` + drag handle) |
| Empty / back header | `ui/components/EmptyListHint.kt`; `ui/components/ScreenBackHeader.kt` (`backContentDescription`, default `"Volver"`) |
| Playback chrome | `ui/components/PlaybackUi.kt` (`playPauseVector`, `playbackProgressFraction`, `previewProgressFraction`, `previewFlags`) |
| Artwork UI | `ui/components/ArtworkThumbnail.kt` (`rememberArtworkRequest`, `ArtworkThumbnail`, `ArtworkHero`); `ui/components/ArtworkPicker.kt` (`ArtworkPickerBlock`, `rememberImagePicker`) |
| Play / shuffle icons | `ui/components/PlayShuffleButtons.kt` (`PlayIconButton`, `ShuffleIconButton`, `PlayShuffleIconPair`, `LabeledPlayShuffleButtons`) |
| Settings switch row | `ui/components/SettingsSwitchRow.kt` (`SettingsSwitchRow`, `SettingsScrollColumn`) |
| Download action widgets | `ui/components/DownloadActionWidgets.kt` (`DownloadStateTrailing` + `successLabel`/`successContent`/`idleContent`/`onSuccessPlay`, `downloadStateStatusLabel`, `DownloadSuccessReadyLabel`, `List<ActiveDownload>.findByTrack` (id / `batch:` / identity fallback), `DownloadProgressPercent`, `DownloadQueuedLabel`, `RetryCycleDismissActions`, `PreviewPlayPauseButton(enabled)`) |
| Matched tracks UI | `ui/components/MatchedTrackLazyColumn.kt` (`MatchedTrackListItem.meta: TrackMeta`); `MatchedTrackRow` (L2 `meta`, L1 flat title/artist) |
| Multi-select bar | `ui/components/MultiSelectActionBar.kt` (`onIdentifySelected`, `onSimilarSelected`) |
| Similar preview dialog | `ui/components/SimilarPlaylistPreviewDialog.kt`; state `ui/state/SimilarPlaylistPreviewState.kt` |
| Sort helper UI | `ui/components/SortEmphasis.kt` (`sortEmphasisFor`); aggregate rows still `SortRelevantInfo.kt` |
| Color picker | `ui/components/ColorPickerDialog.kt` |
| Library list model | `ui/state/LibraryListItem.kt`, `LibraryUiState.kt` |
| Playlist / nav detail | `ui/state/PlaylistDetailNav.kt` (`None` / `Local` / `ListenBrainz` / `CfRecommendations`) |
| Discover playback origin | `ui/state/DiscoverPlaybackOrigin.kt` (`None` / `ListenBrainz` / `CfRecommendations`); VM `discoverPlaybackOrigin` |
| NP song/album actions | `ui/screens/NowPlayingActions.kt` (`NowPlayingActionsMenu`) |
| Identify review state | `ui/state/IdentifyReviewState.kt` (`IdentifyReviewItem`, `IdentifyReviewPhase`, `IdentifyReviewState` `isVisible` / `pendingCount` / `albumGroups` / `canApplyRemaining` / `identifyReviewFromPersisted`) |
| Theme Compose | `ui/theme/Theme.kt`, `ThemePresets.kt`, `ListDensity.kt` (row/artwork/chip density tokens) |

## Domain

| Use case | Archivo | Responsabilidad |
|----------|---------|-----------------|
| `GetLibrarySongsUseCase` | `domain/usecase/GetLibrarySongsUseCase.kt` | filter, sort (`SortOption` + `SortDirection`), album groups (`compareSongsWithinAlbum` / `sortSongsWithinAlbum` / `songsFromListItems`), `extractAlbums` / `extractArtists` / `extractGenres`, `songsForBrowseProjection` |
| `LibraryBrowseFilter` | `ui/state/LibraryBrowseFilter.kt` | chips SONGS/ALBUMS/ARTISTS/GENRES/RECENT |
| `DownloadAudioTrackUseCase` | `domain/usecase/DownloadAudioTrackUseCase.kt` | wrap download Result |
| `MatchListenBrainzTracksUseCase` | `domain/usecase/MatchListenBrainzTracksUseCase.kt` | match LB tracks → local `Song` (delega a `TrackMatchKeys`) |
| `TrackMatchKeys` | `domain/util/TrackMatchKeys.kt` | `normalize` / `matchKey` / `downloadIdFor` / `batchDownloadIdFor` / `buildLibraryIndex` (= `buildIndex`) / `buildIndex` / `lookupLocalSong` / `matchAgainstLibrary` / `matchMetasAgainstLibrary`; `List<MatchedRemoteTrack>.rematchLocals` en `MatchedRemoteTrack.kt` |
| Album name normalize | `domain/util/AlbumNames.kt` | `normalizeAlbumName` / `albumNamesMatch` (merge conflict + saves) |
| Album track encode | `data/util/AlbumTrackNumbers.kt` | `encodeAlbumTrack` / `albumTrackDisplayNumber` / `albumDiscNumber` / `albumTrackSortKey` / `parseCdTrackNumber` |
| Identify ranking | `domain/util/IdentifyRanking.kt` | `score` / `rank` / `confidence` / `hasSevereConflict` / `stripTitleNoise` / `isGenericAlbum` / `isPlaceholderArtist` / `isPreferredProvider` (incl. ListenBrainz) / `cleanIdentityTitle` / `strongVersionMarkers`; `Query.sourceArtist`/`sourceTitle`/`sourceAlbum` |
| Identify album groups | `domain/util/IdentifyAlbumGroups.kt` | `IdentifyAlbumGroup` / `clusterIdentifyAlbumGroups` / `albumGroupKey` |
| `ImportListenBrainzPlaylistUseCase` | `domain/usecase/ImportListenBrainzPlaylistUseCase.kt` | create Room playlist: matched + `PlaylistPendingTrack` metadata |
| `BuildSimilarPlaylistPreviewUseCase` | `domain/usecase/BuildSimilarPlaylistPreviewUseCase.kt` | multi-seed `RadioEngine.suggestFromSeeds` + co-playlist union; `createPlaylistFromPlayables` (Local + pending Remote) |
| `FetchAndMatchCfRecommendationsUseCase` | `domain/usecase/FetchAndMatchCfRecommendationsUseCase.kt` | CF mbids → metadata → Local/Remote |
| `RadioEngine` | `domain/radio/RadioEngine.kt` | orquesta KNOWN / NEW / BOTH; `suggest` / `suggestFromSeeds`; fill LB→CF→`SimilarTracksProvider`; `interleaveEquitable` / `roundRobinMerge`; `RadioSuggestResult` |
| `SimilarTracksProvider` | `domain/radio/SimilarTracksProvider.kt` | contrato de fill remoto (Deezer, futuros) |
| `LocalMetadataRadio` | `domain/radio/LocalMetadataRadio.kt` | score biblioteca (artista/género/año/álbum/co-playlist) |
| `ListenBrainzRadio` | `domain/radio/ListenBrainzRadio.kt` | lb-radio → Local/Remote |
| `CfRecommendationsRadio` | `domain/radio/CfRecommendationsRadio.kt` | CF pool cache → Local/Remote (fill Radio NEW/BOTH) |
| `DeezerSimilarRadio` | `domain/radio/DeezerSimilarRadio.kt` | Deezer radio/related + iTunes same-artist fill → Remote |
| `RadioMode` | `domain/radio/RadioMode.kt` | `KNOWN` / `NEW` / `BOTH` |
| Puerto | `domain/repository/IMusicRepository.kt` | contrato repositorio (`getPlaylistIdsForSong`, `getCoPlaylistSongIds`, `LibraryScanProgress`, `proposeSongIdentity` (+ `listenBrainzToken`), `applySongIdentity`, `identifySongMetadata`, `saveUploadedSong(Song)`) |

## Data

| Concern | Archivo |
|---------|---------|
| Repo impl | `data/repository/MusicRepository.kt` (`scanMediaStore`, `resyncAppManagedMusic`, `scanFolderUri`, `proposeSongIdentity` (+ `listenBrainzToken`, edge LB via `fetchListenBrainzIdentifyTrack`), `applySongIdentity`, `identifySongMetadata`, `migrateCanonicalAudioUris`) |
| Identidad de track | `data/model/TrackIdentity.kt` (`TrackMeta`, `TrackIdentity`, `mergePreferring`, `youtubeSearchQuery`, `toCatalogTrack`, `toListenBrainzCatalogTrack`, `preferMetaFrom`, `Song.toIdentity`, `Song.withIdentity`, `OnlineCatalogTrack.withIdentity`, `DEFAULT_CATALOG_USER_AGENT`); JSON shared `data/util/TrackIdentityJson.kt` (`putInto` / `decode`, compat `artworkUrl`→`artworkUri`) |
| Modelos dominio UI | `data/model/Song.kt` (`Song` : `TrackMeta` plano + `@Entity songs`), `data/model/AlbumOverride.kt` (`@Entity album_overrides`), `data/model/Models.kt` (`OnlineCatalogTrack` (`identity` + id/provider/audioUrl + invoke plano), `CatalogGenre`, `CatalogCategory` (+ `GENRES` / `CHARTS`), `CatalogTrackCandidate` (`identity` catálogo + `candidates` YT; `TrackMeta by identity`), `DownloadStatus`, `ActiveDownload` (`TrackMeta` vía `currentTrack`; `displayLabel` / `titleOverride`), `DownloadConflict.lookupIdentity`, `TrackedBatchItem.lookupIdentity`, `ActiveDownloadSource` incl. `LB_IMPORT` / `DISCOVER`, `CandidateDownloadState` incl. `QUEUED`, `PlaylistPendingTrack(identity, id, playlistId, mbid, position)` + `toOnlineCatalogTrack` → `TrackIdentity.toListenBrainzCatalogTrack`, `WifiTransferItem` / `WifiTransferState`, `Album.displayName`, `LibraryJobProgress` / `LibraryJobKind`, `IdentifyResult.Updated(songId)`, `IdentifyCandidate(track, score, reasons)`, `IdentifyConfidence`, `IdentifyProposal` (`usedListenBrainz`)) |
| Cola Local/Remote | `data/model/PlayableItem.kt` (`PlayableItem` : `TrackMeta`, `matchesSong` / `matchesItem`, `Remote(identity, mbid, youtubeQuery, resolved)`, `ResolvedStream`, `Song.toPlayable`, `remoteFrom` identity + args, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack` / `withIdentity`) |
| Room DB | `data/db/AppDatabase.kt` (v6) |
| DAO | `data/db/MusicDao.kt` (`getPlaylistIdsForSong`, `getCoPlaylistSongIds`) |
| Song entity + MediaStore | `data/model/Song.kt`; `data/db/MediaStoreSongMapper.kt` (`Cursor.toSong`) |
| Album overrides | `data/model/AlbumOverride.kt`; DAO `getAllAlbumOverridesFlow` / `upsertAlbumOverride`; repo `persistOverride` (sanitize art/name) |
| Album merge | `IMusicRepository.mergeAlbumInto` → `MusicRepository.mergeAlbumInto` + `MusicDao.updateSongsAlbumMetadata` / `getSongsForAlbum` |
| Playlist entities | `data/db/PlaylistEntities.kt` (`PlaylistPendingTrackEntity` plano, columna `releaseName`); mapper `toPendingTrack` / `toEntity` en `MusicRepository.kt` (`album` ↔ `releaseName`) |
| Catálogo / lyrics / covers web | `data/network/MetadataFetcher.kt` (`JSONObject.toDeezerTrackIdentity` / `toItunesTrackIdentity`, `parseDeezerTrackArray` → `List<TrackIdentity>`, `parseDeezerSearchTracks`, `parseItunesSongResults`, `parseCatalogGenres`, `listGenres`, `fetchChartTracks`, `searchTracksByGenre`, `fetchFullTrackMetadata` → `TrackIdentity?`, `toCatalogCandidate`, `searchDeezerArtist`, `resolveDeezerArtistId`, `fetchDeezerArtistRadio`, `fetchDeezerRelatedArtistIds`, `fetchDeezerArtistTop`, `fetchItunesArtistSongs`) |
| YouTube search + stream | `data/network/YouTubeExtractor.kt` (`YouTubeStreamResult(identity, videoId, audioUrl, userAgent)` : `TrackMeta` + invoke L2, `extractYouTubeId`, `searchYouTube`, `parseSearchContents`, `audioPreferenceScore`, `rankByAudioPreference`, `resolveYouTubeQueryOrId`) |
| Stream resolve + cache TTL | `data/stream/StreamResolver.kt` (instancia única en `MusicRepository.streamResolver`; `resolve` playback cache; `resolveQuery(forceRefresh)` download) |
| Playable factories | `data/model/PlayableItem.kt` (`remoteFrom(identity)` / `remoteFrom(artist, title, …)`, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack`, `Remote.withIdentity`) |
| Theme DataStore | `data/preferences/ThemePreferencesRepository.kt` |
| Library initial scan + UI prefs | `data/preferences/LibraryPreferencesRepository.kt` (`isInitialScanCompleted`, `displaySettingsFlow`, `navSnapshotFlow`, `setSortOptionName` / `setSortDirectionName` / `setViewModeName` / `setNavSnapshot`); codec `LibraryUiPreferences.kt` (`LibraryUiPreferencesCodec`, `UiNavSnapshot.browseFilterName` + legacy `library_tab`, `LibraryDisplaySettings` sort+direction, `sanitizeBrowseFilterName` / `sanitizeSortDirectionName`) |
| Playback / sonido + modos | `data/preferences/PlaybackPreferencesRepository.kt` (`PlaybackSettings`, `PlaybackModeRestore`, `PlaybackModeClear.afterManualPlay` / `afterSkip`, `parseRepeatModeName`, `MAX_VOLUME_BOOST_GAIN_MB`, `stereoLeftGain` / `stereoRightGain`, `rememberShuffleOnLaunch` / `rememberRepeatOnLaunch` / `autoplayOnLaunch`, `lastShuffleEnabled` / `lastRepeatMode`, `clearShuffleOnManualPlay` / `clearRepeatAllOnManualPlay` / `clearRepeatOneOnManualPlay`, `clearShuffleOnSkip` / `clearRepeatOneOnSkip`); writes 1-key vía `DataStorePrefs.kt` `put` |
| Active downloads persist | `data/preferences/ActiveDownloadsStore.kt` (`ActiveDownloadCodec` encode/decode `trackNumber` en OCT, `activeDownloadBadgeCount`); track JSON `data/util/CatalogTrackJson.kt` (usa `TrackIdentityJson`); progreso tipado `data/model/DownloadProgress.kt` (`DownloadPhase`, `DownloadMessages`) |
| Identify review persist | `data/preferences/IdentifyReviewStore.kt` (`IdentifyReviewCodec`, `PersistedIdentifyReviewQueue`; sin `audioUrl` CDN) |
| Last-played + cola persistida | `data/preferences/PlaybackSessionStore.kt` (`LastPlayedCodec`, `QueueSnapshotCodec`, `PlaybackHydration.hydrateQueue`, `saveSession`, `LastPlayedSnapshot(identity)`, `QueueSnapshot` + `shufflePlayOrder`, `PersistedQueueItem.Local(songId, uri, identity)`, `HydratedQueue`) |
| Wrap / trim / shuffle índices | `data/playback/PlaybackQueueOrder.kt` (`rotateToStart`, `trimHistory`, `shufflePlayOrder`, `reshufflePlayOrder`, `insertAfterCurrent`, `appendToPlayOrder`, `removeFromPlayOrder`, `moveInPlayOrder`, `remapPlayOrder`, `MAX_QUEUE_HISTORY`) |
| ListenBrainz prefs | `data/preferences/ListenBrainzPreferencesRepository.kt` |
| Download prefs | `data/preferences/DownloadPreferencesRepository.kt` (`DownloadSettings`, `downloadOnMeteredNetwork` default true, byte counters stubs) |
| ListenBrainz API | `data/network/ListenBrainzClient.kt` (`submitListens`, createdfor, playlist, `lookupRecordingMetadata`, `fetchLbRadioArtist`, `fetchRecordingMetadata`, `fetchCfRecordingRecommendations`, `parseCfRecommendations`) |
| LB models + sync | `data/listenbrainz/LbPlaylistModels.kt` (`LbPlaylistTrack(identity, mbid)` + invoke plano, `MatchedLbPlaylist.toPlayableItems`, `streamCount`), `MatchedRemoteTrack.kt` (`TrackIdentity.toMatchedRemote`, `toPlayableItem` / `toOnlineCatalogTrack` / `List.toPlayableItems` / `matchedCount` / `streamCount` / `rematchLocals` / `unmatchedCatalogTracks`; CF `score` opcional), `LbRadioModels.kt` (`LbRecordingMetadata(identity, mbid)` + invoke plano), `CfRecommendationModels.kt` (`MatchedCfRecommendations`), `ListenTracker.kt`, `ListenSyncCoordinator.kt` |
| Connectivity | `data/network/ConnectivityObserver.kt` (`isCurrentlyOnline`, `isMetered`, `networkTypeLabel`) |
| GitHub Releases update | `data/update/GitHubReleaseParser.kt` (`parseReleaseApi` / `parseVersionCode` / `stripVersionCodeLine` desde body); `GitHubUpdateClient.fetchLatest`; `ApkUpdateInstaller` (download + FileProvider → instalador del sistema); `AppUpdateCheckStore`; `GitHubReleaseUrls`; `AppUpdateInfo` |
| Pending listens Room | `data/db/PendingListenEntity.kt`, `PendingListenDao.kt` |
| Storage helpers | `data/util/MusicFileStore.kt` (`canonicalize`, `playableUri`, `openRead`, `applyDataSource`, `prepareWrite`, `delete`, `listManaged`), `data/util/AudioPersistRef.kt` (`canonicalize`), `data/util/StorageUtils.kt` (`RELATIVE_MUSIC_DIR`, `userVisibleMusicDirLabel`, `getPublicMusicDirectory`, `prepareWrite`, `listAudioFileNames`, `listManagedAudioFiles`, `deleteManagedAudio`), `data/util/SongPathNormalizer.kt` (`toAbsolutePath`, `safTreeDocumentToAbsolutePath`, `fileName`, `hasUsableArtwork`, path normalize / app-owned checks), `data/util/JsonExt.kt` (`optNullableString`), `data/util/AudioFileMetadata.kt` (`identity` + genre, `fromPath` / `applyFilenameHints` / `toSong` / `withIdentity`, `parseFilenameMetadataHints`, `looksLikeStoragePath`) |
| Download conflict models | `data/model/Models.kt` (`DownloadConflictPolicy`, `DuplicateSongException`, `DownloadConflict`) |
| One-shot dedup archive | branch `archive/library-dedup-v1-migrator` (`LibraryDedupMigrator` / `LibraryDedupLogic` / prefs; not on LB) |

## Services

| Servicio | Archivo |
|----------|---------|
| Playback Media3 + UA HTTP | `service/MusicService.kt` (`promotePlaybackForeground`, `isPlaybackEngaged`, `setWakeMode(NETWORK)`, `applyShuffleOrder` size-guard, `PLAYBACK_CHANNEL_ID`, `setSessionActivity`, `ACTION_SET_SHUFFLE_ORDER`); VM `playWithForegroundService` → `MediaController.play()`, `syncShuffleToPlayerWhenReady`, `playCollectionJob`; `service/StreamPlaybackTag.kt` |
| Stereo balance (PCM) | `service/StereoBalanceAudioProcessor.kt` + `MusicService.applyStereoBalance` |
| Volume boost (LoudnessEnhancer) | `MusicService.applyBoost` + `PlaybackPreferencesRepository` |
| Ktor WiFi server | `service/WebServerService.kt` (`serverState`, `transfers`, `dismissTransfer`, `/existing-files` Room+BestiaPop); identify post-upload en VM vía `transfers` |
| Download progress notif | `service/DownloadNotificationHelper.kt` (`EXTRA_OPEN_TAB` / `TAB_DOWNLOADS`) |

## Tests de referencia

| Tipo | Archivo |
|------|---------|
| Library list items | `app/src/test/.../GetLibrarySongsUseCaseListItemsTest.kt` |
| Album track encode / catalog parse | `app/src/test/.../AlbumTrackNumbersTest.kt`, `CatalogTrackNumberParseTest.kt` |
| YouTube extraction | `app/src/test/.../YouTubeExtractionIntegrationTest.kt` |
| YouTube audio preference | `app/src/test/.../YouTubeAudioPreferenceTest.kt` |
| StreamResolver cache/TTL | `app/src/test/.../StreamResolverTest.kt` |
| Radio local / engine / Deezer | `app/src/test/.../RadioEngineTest.kt`, `DeezerSimilarRadioTest.kt` |
| LB radio / CF JSON parse | `app/src/test/.../ListenBrainzRadioParseTest.kt` |
| LB Para Ti → PlayableItem | `app/src/test/.../MatchedLbPlaylistPlayableTest.kt` |
| CF match Local|Remote | `app/src/test/.../FetchAndMatchCfRecommendationsUseCaseTest.kt` |
| ActiveDownload cycle | `app/src/test/.../ActiveDownloadCycleTest.kt` |
| ActiveDownload codec / badge | `app/src/test/.../ActiveDownloadCodecTest.kt` |
| Last-played / idle / queue hydrate | `app/src/test/.../PlaybackSessionStoreTest.kt` |
| Queue wrap / trim / shuffle índices | `app/src/test/.../PlaybackQueueOrderTest.kt` |
| Queue snapshot codec | `app/src/test/.../QueueSnapshotCodecTest.kt` |
| Shuffle/repeat restore | `app/src/test/.../PlaybackModeRestoreTest.kt` |
| Shuffle/repeat clear-on-play | `app/src/test/.../PlaybackModeClearTest.kt` |
| Library UI prefs codec / nav | `app/src/test/.../LibraryUiPreferencesCodecTest.kt`, `PlaylistDetailNavTest.kt` |
| Audio persist canonicalize | `app/src/test/.../AudioPersistRefTest.kt` |
| Import LB playlist | `app/src/test/.../ImportListenBrainzPlaylistUseCaseTest.kt` |
| Path normalize | `app/src/test/.../SongPathNormalizerTest.kt` |
| Filename metadata hints | `app/src/test/.../FilenameMetadataHintsTest.kt` (`looksLikeStoragePath`) |
| Identify ranking | `app/src/test/.../IdentifyRankingTest.kt` |
| Identify album groups | `app/src/test/.../IdentifyAlbumGroupsTest.kt` |
| Identify review codec / hydrate | `app/src/test/.../IdentifyReviewCodecTest.kt` |
| GitHub release parser | `app/src/test/.../GitHubReleaseParserTest.kt` |
| TrackIdentity JSON | `app/src/test/.../TrackIdentityJsonTest.kt` |
| TrackIdentity merge / toIdentity / youtubeSearchQuery / preferMetaFrom | `app/src/test/.../TrackIdentityTest.kt` |
| Pending mapper album↔releaseName | `app/src/test/.../PlaylistPendingTrackMapperTest.kt` |
| UI functional library | `app/src/androidTest/.../LibraryScreenFunctionalTest.kt` |

## Símbolos ViewModel frecuentes

Mantener esta lista alineada con `MusicPlayerViewModel.kt`:

- Biblioteca: `songsState`, `albumsState`, `artistsState`, `genresState`, `searchQuery`, `sortOption`, `setSortOption`, `sortDirection`, `setSortDirection` / `toggleSortDirection`, `libraryViewMode`, `setLibraryViewMode`, `toggleLibraryViewMode`, `libraryBrowseFilter`, `setLibraryBrowseFilter`, `libraryArtistName`, `libraryAlbumName`, `libraryGenreName`, `openLibraryAlbum`, `openLibraryArtist`, `openLibraryGenre`, `popLibraryNested`, `selectedNavIndex`, `setSelectedNavIndex`, `openDownloadsTabTransient`, `playlistDetail`, `openLocalPlaylist`, `openListenBrainzPlaylistDetail`, `openCfRecommendationsDetail`, `closePlaylistDetail`, `dismissDiscoverDetails`, `buildLibraryListItems`, `sortSongsWithinAlbum`, `songsForAlbum`, `songsFromLibraryListItems`, `songsForBrowseProjection`, `libraryJobProgress`, `importFolder`, `ensureInitialLibraryImport`, `refreshLibraryFromDisk`, `identifySongs`, `identifySongForReview`, `identifyReview`, `previewIdentifyLocalSong`, `previewIdentifyCandidate`, `applySelectedIdentifyCandidate`, `skipIdentifyReviewItem`, `searchIdentifyCandidates`, `dismissIdentifyReview`, `showIdentifyReview`, `applyRemainingIdentifySuggestions`, `skipAllIdentifyReview`, `applyIdentifyAlbumGroup`, `startIdentifyItemReview`, `returnIdentifyReviewOverview`
- Playback: `playSong`, `playCollection`, `playPlayableCollection` (`rotate`, `applyManualModes`, `startShuffled`, `origin`), `playMatchedCollection` (valida índice + origin), `shufflePlayableCollection` (`origin`), `shuffleCollection`, `enqueueCollection`, `enqueueRemoteDownload`, `playNextInQueue`, `playNextBatch`, `skipToQueueIndex`, `moveDisplayQueueItem`, `currentItem`, `currentSong`, `queue`, `displayQueue`, `resolvingRemote`, `repeatMode`, `isShuffle`, `syncUiFromController`, `maybeSeedIdlePlayer`, `applyHydratedQueue`, `maybeAutoplayAfterIdleSeed`, `togglePlayPause`, `toggleShuffle`, `toggleRepeatMode`, `restorePlaybackModes`, `applyManualPlayModes`, `applySkipModes`, `volumeLevel`, `volumeBoostEnabled`, `setVolume`, `setVolumeBoostEnabled`, `stereoLeftGain`, `stereoRightGain`, `setStereoLeftGain`, `setStereoRightGain`, `resetStereoBalance`, `rememberShuffleOnLaunch`, `rememberRepeatOnLaunch`, `autoplayOnLaunch`, `setRememberShuffleOnLaunch`, `setRememberRepeatOnLaunch`, `setAutoplayOnLaunch`, `clearShuffleOnManualPlay`, `clearRepeatAllOnManualPlay`, `clearRepeatOneOnManualPlay`, `clearShuffleOnSkip`, `clearRepeatOneOnSkip`, `setClearShuffleOnManualPlay`, `setClearRepeatAllOnManualPlay`, `setClearRepeatOneOnManualPlay`, `setClearShuffleOnSkip`, `setClearRepeatOneOnSkip`, `discoverPlaybackOrigin`, `playlistsContainingSong`
- Radio: `startRadio` / `stopRadio` / `setRadioPreferredMode`, `suggestRadioWithRetry`, `radioMode`, `radioStatusLabel`, `RADIO_LOADING_LABEL`, `radioModeLabel`, `replaceUpcomingWithRadio`, `maybeAutoStartRadioOnQueueEnd`
- Artwork: `setAlbumArtwork`, `requestSaveAlbumMetadata`, `saveAlbumOverride` (private), `confirmPendingAlbumMerge`, `dismissPendingAlbumMerge`, `mergeAlbumInto`
- Online: `searchCatalog`, `searchOnlineCatalog`, `setCatalogCategory`, `catalogGenres`, `selectGenreForInspection`, `selectAlbumForInspection`, `selectPlaylistForInspection`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `enqueueTrackedBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `activeDownloads`, `downloadConflict`, `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict`, `retryActiveDownload`, `cycleActiveDownload`, `previewActiveDownload`, `playActiveDownload`, `dismissActiveDownload`, `dismissAllActiveDownloads`, `requestOpenDownloads` / `pendingOpenDownloads`, `playOnlineCatalogTrackAsStream`, `expandCandidates`, `launchCycleYouTubeMatch`, `cycleSongCatalogResult`, `cycleTrackCandidate`, `catalogPreviewKey`, `toastSongInLibrary`, `toastDownloadsQueued`, `downloadOnMeteredNetwork` / `setDownloadOnMeteredNetwork`, `ensureDownloadNetworkAllowed` (gate en `runTrackedDownload`)
- ListenBrainz: `listenBrainzSettings`, `setListenBrainzEnabled`, `setListenBrainzDiscoverEnabled`, `setListenBrainzSaveWhileListening`, `setListenBrainzSaveWhileListeningPercent`, `refreshListenBrainzDiscoverPlaylists`, `openListenBrainzPlaylist`, `openListenBrainzPlaylistDetail`, `playMatchedTracks`, `shuffleMatchedTracks`, `saveListenBrainzPlaylistAsLocal`, `importListenBrainzPlaylistWithDownloads`, `downloadPlaylistPendingTracks`, `downloadRemoteItem`, `getPlaylistPendingTracksFlow`, `refreshCfRecommendations`, `openCfRecommendations`, `openCfRecommendationsDetail`, `closeCfRecommendations`, `closePlaylistDetail`, `cfRecommendations`, `cfListState`, `cfDetailOpen`, `playlistDetail`, `discoverPlaybackOrigin`

## Cómo actualizar este mapa

Tras crear/renombrar/mover un archivo o API pública relevante:

1. Editar la fila correspondiente (o añadir sección).
2. Si el change afecta un feature, actualizar también `bestiapop-features`.
3. Si cambia capas/paquetes/stack, actualizar `bestiapop-architecture`.
