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
| Crash non-fatals / keys | `data/util/CrashReporter.kt` |
| Activity | `MainActivity.kt` |
| Manifest / permisos / services | `app/src/main/AndroidManifest.xml` |
| Gradle app | `app/build.gradle.kts` (`signingConfigs.release`, debug+release same cert si hay keystore, `versionCode`/`versionName`) |
| Deploy script | `install.sh` (`--debug` default, `--release`; `adb install -r -d`, fallback `cmd package uninstall -k`; `appops RUN_ANY_IN_BACKGROUND allow`) |
| Firebase config template | `app/google-services.json.example` |
| Release keystore template | `keystore.properties.example` |

## UI — screens

| Pantalla | Archivo |
|----------|---------|
| Shell + bottom nav | `ui/screens/MainScreen.kt` |
| System back (exit doble + orquestación) | `MainScreen` `BackHandler` + `SnackbarHost`; nested en screens abajo; identify review overlay |
| Biblioteca | `ui/screens/LibraryScreen.kt` (`BackHandler`: multi-select / addition / album-artist / search; `LibrarySongListHost` + `SongActionDialogsHost`) |
| Identify review | `ui/screens/IdentifyReviewScreen.kt` (`IdentifyCandidateRow`, `IdentifySearchBlock` above candidates; Aplicar automático / Omitir todas; overlay en `MainScreen`) |
| Lista canciones / álbumes / artistas | `ui/screens/library/LibrarySongList.kt`, `LibrarySongListHost.kt` (`LibrarySongListActions`, `SongActionDialogsHost`), `LibraryAlbumGrid.kt` (`AlbumEditCoverMenuItems`), `LibraryArtistList.kt`, `LibraryDialogs.kt` (`EditSongMetadataDialog` Nº de pista, `EditAlbumMetadataDialog`, `ConfirmMergeAlbumsDialog`, `SetAlbumArtworkDialog`), `LibraryProgressBanner.kt` |
| Playlists | `ui/screens/PlaylistsScreen.kt` (`BackHandler`: CF → LB → local detail; play/shuffle vía `LabeledPlayShuffleButtons` + `playCollection`/`shuffleCollection`; `MatchedTrackRow`; `matchedStreamCountLabel`; `rememberSongQueueActions`) |
| Ajustes / ListenBrainz | `ui/screens/SettingsScreen.kt` (`BackHandler` sección), `ListenBrainzSettingsScreen.kt` |
| Ajustes / Reproducción | `ui/screens/PlaybackSettingsScreen.kt` (`SettingsScreen` sección `Playback`) |
| Ajustes / Sonido | `ui/screens/VolumeBoostSettingsScreen.kt` (`SettingsScreen` sección `Sound`) |
| Now playing | `ui/screens/NowPlayingScreen.kt` (`BackHandler` → `onDismiss`; cola vía `QueueItemRow`) |
| Cola | `ui/screens/QueueScreen.kt` (`QueueItemRow`) |
| WiFi sync | `ui/screens/WebServerScreen.kt` (`WebServerScreen(viewModel)` + transferencias + botón conflictos + `SongActionDialogsHost`) |
| Descargas | `ui/screens/DownloadsScreen.kt` (`DownloadsScreen(viewModel)` + `ActiveDownloadRow` + `dismissAllActiveDownloads`) |
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
| Song queue actions | `ui/components/SongQueueActions.kt` (`SongQueueActions`, `rememberSongQueueActions`) |
| Matched local/remote row | `ui/components/MatchedTrackRow.kt` (`isMatchedTrackPlaying`, `MatchedTrackRow` + `SongQueueActions`) |
| Remote placeholder row | `ui/components/RemoteTrackPlaceholderRow.kt` |
| Queue row | `ui/components/QueueItemRow.kt` (`PlayableItemRowContent`, `QueueItemRow`) |
| Artwork UI | `ui/components/ArtworkThumbnail.kt`; `ui/components/ArtworkPicker.kt` (`ArtworkPickerBlock`, `rememberImagePicker`) |
| Play / shuffle icons | `ui/components/PlayShuffleButtons.kt` (`PlayIconButton`, `ShuffleIconButton`, `PlayShuffleIconPair`, `LabeledPlayShuffleButtons`) |
| Settings switch row | `ui/components/SettingsSwitchRow.kt` (`SettingsSwitchRow`, `SettingsScrollColumn`) |
| Download action widgets | `ui/components/DownloadActionWidgets.kt` (`DownloadProgressPercent`, `DownloadQueuedLabel`, `RetryCycleDismissActions`, `PreviewPlayPauseButton(enabled)`) |
| Multi-select bar | `ui/components/MultiSelectActionBar.kt` (`onIdentifySelected`) |
| Sort helper UI | `ui/components/SortRelevantInfo.kt` |
| Color picker | `ui/components/ColorPickerDialog.kt` |
| Library list model | `ui/state/LibraryListItem.kt`, `LibraryUiState.kt` |
| Playlist / nav detail | `ui/state/PlaylistDetailNav.kt` (`None` / `Local` / `ListenBrainz` / `CfRecommendations`) |
| Identify review state | `ui/state/IdentifyReviewState.kt` (`IdentifyReviewItem`, `IdentifyReviewState` `isVisible` / `pendingCount` / `canApplyRemaining`) |
| Theme Compose | `ui/theme/Theme.kt`, `ThemePresets.kt` |

## Domain

| Use case | Archivo | Responsabilidad |
|----------|---------|-----------------|
| `GetLibrarySongsUseCase` | `domain/usecase/GetLibrarySongsUseCase.kt` | filter, sort, album groups (`compareSongsWithinAlbum` / `sortSongsWithinAlbum` / `songsFromListItems`), extract albums/artists |
| `DownloadAudioTrackUseCase` | `domain/usecase/DownloadAudioTrackUseCase.kt` | wrap download Result |
| `MatchListenBrainzTracksUseCase` | `domain/usecase/MatchListenBrainzTracksUseCase.kt` | match LB tracks → local `Song` (delega a `TrackMatchKeys`) |
| `TrackMatchKeys` | `domain/util/TrackMatchKeys.kt` | `normalize` / `matchKey` / `downloadIdFor` / `buildLibraryIndex` / `buildIndex` (shared) |
| Album name normalize | `domain/util/AlbumNames.kt` | `normalizeAlbumName` / `albumNamesMatch` (merge conflict + saves) |
| Album track encode | `data/util/AlbumTrackNumbers.kt` | `encodeAlbumTrack` / `albumTrackDisplayNumber` / `albumDiscNumber` / `albumTrackSortKey` / `parseCdTrackNumber` |
| Identify ranking | `domain/util/IdentifyRanking.kt` | `score` / `rank` / `confidence` / `hasSevereConflict` / `stripTitleNoise` / `isGenericAlbum` / `isPlaceholderArtist` / `cleanIdentityTitle` / `strongVersionMarkers`; `Query.sourceArtist`/`sourceTitle`/`sourceAlbum` |
| `ImportListenBrainzPlaylistUseCase` | `domain/usecase/ImportListenBrainzPlaylistUseCase.kt` | create Room playlist: matched + `PlaylistPendingTrack` metadata |
| `FetchAndMatchCfRecommendationsUseCase` | `domain/usecase/FetchAndMatchCfRecommendationsUseCase.kt` | CF mbids → metadata → Local/Remote |
| `RadioEngine` | `domain/radio/RadioEngine.kt` | orquesta KNOWN / NEW / BOTH; fill LB→CF→`SimilarTracksProvider`; `interleaveEquitable`; `RadioSuggestResult` |
| `SimilarTracksProvider` | `domain/radio/SimilarTracksProvider.kt` | contrato de fill remoto (Deezer, futuros) |
| `LocalMetadataRadio` | `domain/radio/LocalMetadataRadio.kt` | score biblioteca (artista/género/año/álbum/co-playlist) |
| `ListenBrainzRadio` | `domain/radio/ListenBrainzRadio.kt` | lb-radio → Local/Remote |
| `CfRecommendationsRadio` | `domain/radio/CfRecommendationsRadio.kt` | CF pool cache → Local/Remote (fill Radio NEW/BOTH) |
| `DeezerSimilarRadio` | `domain/radio/DeezerSimilarRadio.kt` | Deezer radio/related + iTunes same-artist fill → Remote |
| `RadioMode` | `domain/radio/RadioMode.kt` | `KNOWN` / `NEW` / `BOTH` |
| Puerto | `domain/repository/IMusicRepository.kt` | contrato repositorio (`getCoPlaylistSongIds`, `LibraryScanProgress`, `proposeSongIdentity`, `applySongIdentity`, `identifySongMetadata`) |

## Data

| Concern | Archivo |
|---------|---------|
| Repo impl | `data/repository/MusicRepository.kt` (`scanMediaStore`, `resyncAppManagedMusic`, `scanFolderUri`, `proposeSongIdentity`, `applySongIdentity`, `identifySongMetadata`, `migrateCanonicalAudioUris`) |
| Identidad de track | `data/model/TrackIdentity.kt` (`TrackMeta`, `TrackIdentity`, `mergePreferring`, `Song.toIdentity`, `OnlineCatalogTrack.withIdentity`, `DEFAULT_CATALOG_USER_AGENT`) |
| Modelos dominio UI | `data/model/Models.kt` (`Song` : `TrackMeta` plano, `OnlineCatalogTrack` (`identity` + id/provider/audioUrl + invoke plano), `CatalogTrackCandidate`, `DownloadStatus`, `ActiveDownload` + factories `queued`/`downloading`/`conflict`/`success`/`error` + `targetPlaylistId` / `resultSongId`, `ActiveDownloadSource` incl. `LB_IMPORT` / `DISCOVER`, `CandidateDownloadState` incl. `QUEUED`, `PlaylistPendingTrack(identity, id, playlistId, mbid, position)` + `toOnlineCatalogTrack`, `AlbumOverride`, `WifiTransferItem` / `WifiTransferState`, `Album.displayName`, `LibraryJobProgress` / `LibraryJobKind`, `IdentifyResult`, `IdentifyCandidate(track, score, reasons)`, `IdentifyConfidence`, `IdentifyProposal`) |
| Cola Local/Remote | `data/model/PlayableItem.kt` (`PlayableItem` : `TrackMeta`, `Remote(identity, mbid, youtubeQuery, resolved)`, `ResolvedStream`, `Song.toPlayable`, `remoteFrom` identity + args, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack` / `withIdentity`) |
| Room DB | `data/db/AppDatabase.kt` (v6) |
| DAO | `data/db/MusicDao.kt` (`getCoPlaylistSongIds`) |
| Song entity + mappers | `data/db/SongEntity.kt` |
| Album overrides | `data/db/AlbumOverrideEntity.kt` |
| Album merge | `IMusicRepository.mergeAlbumInto` → `MusicRepository.mergeAlbumInto` + `MusicDao.updateSongsAlbumMetadata` / `getSongsForAlbum` |
| Playlist entities | `data/db/PlaylistEntities.kt` (`PlaylistPendingTrackEntity` plano, columna `releaseName`); mapper `toPendingTrack` / `toEntity` en `MusicRepository.kt` (`album` ↔ `releaseName`) |
| Catálogo / lyrics / covers web | `data/network/MetadataFetcher.kt` (`JSONObject.toDeezerTrackIdentity` / `toItunesTrackIdentity`, `parseDeezerTrackArray` → `List<TrackIdentity>`, `parseDeezerSearchTracks`, `parseItunesSongResults`, `fetchFullTrackMetadata` → `TrackIdentity?`, `toCatalogCandidate`, `searchDeezerArtist`, `resolveDeezerArtistId`, `fetchDeezerArtistRadio`, `fetchDeezerRelatedArtistIds`, `fetchDeezerArtistTop`, `fetchItunesArtistSongs`) |
| YouTube search + stream | `data/network/YouTubeExtractor.kt` (`searchYouTube`, `parseSearchContents`, `audioPreferenceScore`, `rankByAudioPreference`, `resolveYouTubeQueryOrId`) |
| Stream resolve + cache TTL | `data/stream/StreamResolver.kt` (`resolve`, `resolveQuery` — playback + download) |
| Playable factories | `data/model/PlayableItem.kt` (`remoteFrom(identity)` / `remoteFrom(artist, title, …)`, `fromLibraryOrRemote(identity)` / args, `Remote.toOnlineCatalogTrack`, `Remote.withIdentity`) |
| Theme DataStore | `data/preferences/ThemePreferencesRepository.kt` |
| Library initial scan + UI prefs | `data/preferences/LibraryPreferencesRepository.kt` (`isInitialScanCompleted`, `displaySettingsFlow`, `navSnapshotFlow`, `setSortOptionName` / `setViewModeName` / `setNavSnapshot`); codec `LibraryUiPreferences.kt` (`LibraryUiPreferencesCodec`, `UiNavSnapshot`, `LibraryDisplaySettings`) |
| Playback / sonido + modos | `data/preferences/PlaybackPreferencesRepository.kt` (`PlaybackSettings`, `PlaybackModeRestore`, `parseRepeatModeName`, `MAX_VOLUME_BOOST_GAIN_MB`, `stereoLeftGain` / `stereoRightGain`, `rememberShuffleOnLaunch` / `rememberRepeatOnLaunch`, `lastShuffleEnabled` / `lastRepeatMode`); writes 1-key vía `DataStorePrefs.kt` `put` |
| Active downloads persist | `data/preferences/ActiveDownloadsStore.kt` (`ActiveDownloadCodec` encode/decode `trackNumber` en OCT, `activeDownloadBadgeCount`) |
| Last-played / idle seed | `data/preferences/PlaybackSessionStore.kt` (`LastPlayedCodec`, `PlaybackHydration`, `LastPlayedSnapshot`, `matchesLastPlayed`) |
| ListenBrainz prefs | `data/preferences/ListenBrainzPreferencesRepository.kt` |
| ListenBrainz API | `data/network/ListenBrainzClient.kt` (`submitListens`, createdfor, playlist, `lookupRecordingMetadata`, `fetchLbRadioArtist`, `fetchRecordingMetadata`, `fetchCfRecordingRecommendations`, `parseCfRecommendations`) |
| LB models + sync | `data/listenbrainz/LbPlaylistModels.kt` (`LbPlaylistTrack(identity, mbid)` + invoke plano, `MatchedLbTrack.toPlayableItem` → `fromLibraryOrRemote(identity)`, `MatchedLbPlaylist.toPlayableItems`, `streamCount`), `LbRadioModels.kt` (`LbRecordingMetadata(identity, mbid)` + invoke plano), `CfRecommendationModels.kt` (`MatchedCfTrack(identity, mbid, score, localSong)`, `MatchedCfRecommendations`), `ListenTracker.kt`, `ListenSyncCoordinator.kt` |
| Connectivity | `data/network/ConnectivityObserver.kt` |
| Pending listens Room | `data/db/PendingListenEntity.kt`, `PendingListenDao.kt` |
| Storage helpers | `data/util/MusicFileStore.kt` (`canonicalize`, `playableUri`, `openRead`, `applyDataSource`, `prepareWrite`, `delete`, `listManaged`), `data/util/AudioPersistRef.kt` (`canonicalize`), `data/util/StorageUtils.kt` (`getPublicMusicDirectory`, `prepareWrite`, `listAudioFileNames`, `listManagedAudioFiles`, `deleteManagedAudio`), `data/util/SongPathNormalizer.kt` (`toAbsolutePath`, `safTreeDocumentToAbsolutePath`, `fileName`, `hasUsableArtwork`, path normalize / app-owned checks), `data/util/JsonExt.kt` (`optNullableString`), `data/util/AudioFileMetadata.kt` (`identity` + genre, `fromPath` / `applyFilenameHints` / `toSongEntity` / `withIdentity`, `parseFilenameMetadataHints`, `looksLikeStoragePath`) |
| Download conflict models | `data/model/Models.kt` (`DownloadConflictPolicy`, `DuplicateSongException`, `DownloadConflict`) |
| One-shot dedup archive | branch `archive/library-dedup-v1-migrator` (`LibraryDedupMigrator` / `LibraryDedupLogic` / prefs; not on LB) |

## Services

| Servicio | Archivo |
|----------|---------|
| Playback Media3 + UA HTTP | `service/MusicService.kt` (`promotePlaybackForeground`, `PLAYBACK_CHANNEL_ID`, `setSessionActivity`); VM `playWithForegroundService` → `MediaController.play()`; `service/StreamPlaybackTag.kt` |
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
| Last-played / idle hydration | `app/src/test/.../PlaybackSessionStoreTest.kt` |
| Shuffle/repeat restore | `app/src/test/.../PlaybackModeRestoreTest.kt` |
| Library UI prefs codec / nav | `app/src/test/.../LibraryUiPreferencesCodecTest.kt`, `PlaylistDetailNavTest.kt` |
| Audio persist canonicalize | `app/src/test/.../AudioPersistRefTest.kt` |
| Import LB playlist | `app/src/test/.../ImportListenBrainzPlaylistUseCaseTest.kt` |
| Path normalize | `app/src/test/.../SongPathNormalizerTest.kt` |
| Filename metadata hints | `app/src/test/.../FilenameMetadataHintsTest.kt` (`looksLikeStoragePath`) |
| Identify ranking | `app/src/test/.../IdentifyRankingTest.kt` |
| TrackIdentity merge / toIdentity | `app/src/test/.../TrackIdentityTest.kt` |
| Pending mapper album↔releaseName | `app/src/test/.../PlaylistPendingTrackMapperTest.kt` |
| UI functional library | `app/src/androidTest/.../LibraryScreenFunctionalTest.kt` |

## Símbolos ViewModel frecuentes

Mantener esta lista alineada con `MusicPlayerViewModel.kt`:

- Biblioteca: `songsState`, `albumsState`, `artistsState`, `searchQuery`, `sortOption`, `setSortOption`, `libraryViewMode`, `setLibraryViewMode`, `toggleLibraryViewMode`, `libraryTab`, `setLibraryTab`, `libraryArtistName`, `libraryAlbumName`, `openLibraryAlbum`, `openLibraryArtist`, `popLibraryNested`, `selectedNavIndex`, `setSelectedNavIndex`, `openDownloadsTabTransient`, `playlistDetail`, `openLocalPlaylist`, `openListenBrainzPlaylistDetail`, `openCfRecommendationsDetail`, `closePlaylistDetail`, `dismissDiscoverDetails`, `buildLibraryListItems`, `sortSongsWithinAlbum`, `songsForAlbum`, `songsFromLibraryListItems`, `libraryJobProgress`, `importFolder`, `ensureInitialLibraryImport`, `refreshLibraryFromDisk`, `identifySongs`, `identifySongForReview`, `identifyReview`, `previewIdentifyLocalSong`, `previewIdentifyCandidate`, `applySelectedIdentifyCandidate`, `skipIdentifyReviewItem`, `searchIdentifyCandidates`, `dismissIdentifyReview`, `showIdentifyReview`, `applyRemainingIdentifySuggestions`, `skipAllIdentifyReview`
- Playback: `playSong`, `playCollection`, `playPlayableCollection`, `applyShuffledQueue`, `playMatchedCollection`, `shuffleMatchedCollection`, `shuffleCollection`, `enqueueCollection`, `playNextInQueue`, `playNextBatch`, `currentItem`, `currentSong`, `queue`, `resolvingRemote`, `repeatMode`, `isShuffle`, `syncUiFromController`, `maybeSeedIdlePlayer`, `togglePlayPause`, `toggleShuffle`, `toggleRepeatMode`, `restorePlaybackModes`, `volumeLevel`, `volumeBoostEnabled`, `setVolume`, `setVolumeBoostEnabled`, `stereoLeftGain`, `stereoRightGain`, `setStereoLeftGain`, `setStereoRightGain`, `resetStereoBalance`, `rememberShuffleOnLaunch`, `rememberRepeatOnLaunch`, `setRememberShuffleOnLaunch`, `setRememberRepeatOnLaunch`
- Radio: `startRadio` / `stopRadio` / `setRadioPreferredMode`, `suggestRadioWithRetry`, `radioMode`, `radioStatusLabel`, `RADIO_LOADING_LABEL`, `radioModeLabel`, `replaceUpcomingWithRadio`, `maybeAutoStartRadioOnQueueEnd`
- Artwork: `setAlbumArtwork`, `saveAlbumMetadata`, `requestSaveAlbumMetadata`, `confirmPendingAlbumMerge`, `dismissPendingAlbumMerge`, `mergeAlbumInto`
- Online: `searchCatalog`, `searchOnlineCatalog`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `enqueueTrackedBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `activeDownloads`, `downloadConflict`, `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict`, `retryActiveDownload`, `cycleActiveDownload`, `previewActiveDownload`, `playActiveDownload`, `dismissActiveDownload`, `dismissAllActiveDownloads`, `requestOpenDownloads` / `pendingOpenDownloads`, `playOnlineCatalogTrackAsStream`, `expandCandidates`, `launchCycleYouTubeMatch`, `cycleSongCatalogResult`, `cycleTrackCandidate`, `catalogPreviewKey`, `toastSongInLibrary`, `toastDownloadsQueued`
- ListenBrainz: `listenBrainzSettings`, `setListenBrainzEnabled`, `setListenBrainzDiscoverEnabled`, `setListenBrainzSaveWhileListening`, `setListenBrainzSaveWhileListeningPercent`, `refreshListenBrainzDiscoverPlaylists`, `openListenBrainzPlaylist`, `openListenBrainzPlaylistDetail`, `playListenBrainzPlaylist`, `shuffleListenBrainzPlaylist`, `playListenBrainzPlaylistAt`, `saveListenBrainzPlaylistAsLocal`, `importListenBrainzPlaylistWithDownloads`, `downloadPlaylistPendingTracks`, `downloadRemoteItem`, `getPlaylistPendingTracksFlow`, `refreshCfRecommendations`, `openCfRecommendations`, `openCfRecommendationsDetail`, `closeCfRecommendations`, `closePlaylistDetail`, `playCfRecommendations`, `shuffleCfRecommendations`, `playCfAt`, `cfRecommendations`, `cfListState`, `cfDetailOpen`, `playlistDetail`

## Cómo actualizar este mapa

Tras crear/renombrar/mover un archivo o API pública relevante:

1. Editar la fila correspondiente (o añadir sección).
2. Si el change afecta un feature, actualizar también `bestiapop-features`.
3. Si cambia capas/paquetes/stack, actualizar `bestiapop-architecture`.
