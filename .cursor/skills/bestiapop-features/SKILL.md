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

**Invariante:** Listas, álbumes, artistas, playlists locales y colas usan el mismo pipeline de reproducción (`playCollection` / `shuffleCollection`). UI: `LabeledPlayShuffleButtons` en library header, detalle de playlist local, CF y LB.

| Acción | ViewModel | Pipeline |
|--------|-----------|----------|
| Reproducir colección | `playCollection(songs, startIndex)` / `playCollection(songs, startSong)` | `playPlayableCollection` (`rotate=true`: tap queda índice 0, prefijo al final) |
| Reproducir Local\|Remote | `playPlayableCollection(items, startIndex, rotate, origin)` | ViewModel + `StreamResolver`; `origin` marca Discover (CF/LB) en un solo sitio |
| Shuffle | `shuffleCollection(songs)` / `shufflePlayableCollection(items, origin)` | `playPlayableCollection(..., startShuffled=true)` + permutación current-first |
| Encolar | `enqueueCollection(songs)` | append a cola `PlayableItem` |
| Una canción | `playSong(song, playlistOrQueue)` | (arma cola + MediaController) |
| Tap en Cola / NP | `skipToQueueIndex(index)` | índice **display**; seek timeline; **no** rota ni apaga shuffle |

Archivos: `ui/MusicPlayerViewModel.kt` (`playPlayableCollection` / `toggleShuffle` / `displayQueue` / `moveDisplayQueueItem` / `skipToQueueIndex`); `data/playback/PlaybackQueueOrder.kt` (`shufflePlayOrder`); `service/MusicService.kt` (`ACTION_SET_SHUFFLE_ORDER`); `ui/components/PlayShuffleButtons.kt`.

## 2. Búsqueda online y descarga de audio

**Invariante:** Catálogo/metadatos pueden venir de iTunes/Deezer; el stream se resuelve con YouTube. Re-extraer URL CDN antes de descargar (evitar HTTP 403).

| Paso | Dónde |
|------|--------|
| Search catálogo tracks | `MetadataFetcher.searchOnlineCatalog` / `YouTubeExtractor.searchYouTube` (`parseSearchContents` + `audioPreferenceScore` / `rankByAudioPreference`: prioriza Topic / Official Audio sobre music video) |
| Query YT desde catálogo | `YouTubeExtractor.resolveYouTubeQueryOrId` (ignora ids Deezer/iTunes; usa `audioUrl` o `artist title`) |
| Álbumes / playlists online | `MetadataFetcher.searchAlbums` / `searchPlaylists` + `fetchAlbumTrackCandidates` / `fetchPlaylistTrackCandidates` |
| Extraer stream | `YouTubeExtractor.extractAudioStream` / `extractAudioStreamDetailed` |
| Descargar + persistir | `DownloadAudioTrackUseCase.execute` → `IMusicRepository.downloadAndSaveOnlineTrack` (`onProgress: DownloadPhase`; persiste `OnlineCatalogTrack.trackNumber` / `TrackIdentity.trackNumber` de `fetchFullTrackMetadata`); copy/UI labels `DownloadMessages` |
| UI diálogo | `ui/components/AddMusicDialog.kt` |
| Centro de descargas | `DownloadsScreen` + `ActiveDownloadRow`; persistencia `ActiveDownloadsStore` / `ActiveDownloadCodec`; notif `DownloadNotificationHelper`; badge `activeDownloadBadgeCount` en tab Descargas (`MainScreen`) |
| Orquestación VM | `enqueueTrackedBatch` → `runTrackedDownload` ← `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `downloadRemoteItem`, `maybeEnqueueSaveWhileListening`; candidatos vía `expandCandidates`; acciones `retryActiveDownload` / `cycleActiveDownload` / `previewActiveDownload` / `playActiveDownload` / `dismissActiveDownload` / `dismissAllActiveDownloads`; deep-link `requestOpenDownloads` / `pendingOpenDownloads` |

Modelo clave: `TrackIdentity` / `TrackMeta`, `OnlineCatalogTrack` (`identity` + id/provider/audioUrl), `CatalogTrackCandidate` (`TrackMeta` vía `identity` estable de catálogo; YT en `candidates`/`currentTrack`; chrome de descarga **derivado** de `activeDownloads.findByTrack`, no del campo embebido), `DownloadStatus` (legacy Idle), `ActiveDownload` (`TrackMeta` vía `currentTrack`; `displayLabel` / `titleOverride` solo UI; Save As vía `DownloadConflictPolicy` sin mutar candidatos), conflicto/batch lookup = `lookupIdentity` (catálogo) no el hit YT, `ActiveDownloadSource` (`CATALOG`, `LINK`, `SAVE_WHILE_LISTENING`, `BATCH`, `LB_IMPORT`, `DISCOVER`), cola `activeDownloads` (+ `targetPlaylistId` opcional, `resultSongId` en SUCCESS). Batch ids = `TrackMatchKeys.batchDownloadIdFor`. JSON viejo: `displayTitle` blank-fill identity; si difiere → `titleOverride`.

**Invariante cola:** todas las descargas online se registran en `activeDownloads` (estado `QUEUED` → `DOWNLOADING` → `SUCCESS`/`ERROR`); éxito **se mantiene** con play/limpiar hasta `dismissActiveDownload`. Fallo deja `ERROR`. Concurrencia global `Semaphore(3)` en `runTrackedDownload`. Tras kill: `ActiveDownloadCodec.forPersistence` restaura SUCCESS; DOWNLOADING/QUEUED → ERROR “Interrumpida”. Badge = DOWNLOADING + ERROR. Add Music banners leen `activeDownloads`. `LB_IMPORT` y batch de **playlist del catálogo** añaden a playlist al éxito vía `targetPlaylistId` (`ensureCatalogPlaylistForBatch`).

## 3. Biblioteca: filtro, orden y vistas

**Invariante:** `songsState` filtra por título/artista/álbum/género y ordena con `SortOption`. Orden, vista, tab Canciones/Álbumes/Artistas y pila artista→álbum **persisten** entre sesiones (`LibraryPreferencesRepository`). Con `ALBUM_GROUPS`, canciones **dentro de cada álbum** van por `trackNumber` (0 al final + título); play/shuffle/tap usan ese orden visual (`songsFromListItems`). Detalle de álbum siempre por pista.

| Capacidad | API |
|-----------|-----|
| Query | `MusicPlayerViewModel.searchQuery` (no se persiste) |
| Sort | `SortOption`: TITLE, ARTIST, ALBUM, GENRE, DATE_ADDED → `setSortOption` (DataStore); menú marca la activa con check |
| Filtrado/orden | `GetLibrarySongsUseCase.execute` |
| Vista plana vs grupos álbum | `LibraryViewMode.FLAT` / `ALBUM_GROUPS` → `setLibraryViewMode` / `toggleLibraryViewMode`; `buildLibraryListItems` / `buildListItems`; within-album `compareSongsWithinAlbum` / `sortSongsWithinAlbum` / `songsFromListItems` |
| Tab + pila | `libraryTab`, `openLibraryAlbum(fromArtist)`, `openLibraryArtist`, `popLibraryNested` (álbum encima de artista) |
| Colapsar álbum / todos | `collapsedAlbumNames` + toggle por header (`onToggleCollapseAlbum`); expandir/colapsar todo en `LibraryScreen` (vista grupos; no persistido) |
| Derivados | `extractAlbums`, `extractArtists` → `albumsState`, `artistsState` |

UI: `LibraryScreen`, `LibrarySongList` (`onOpenAlbum`), `LibraryAlbumGrid`, `LibraryArtistList`.
Estado: `ui/state/LibraryUiState.kt`, `LibraryListItem.kt`. Prefs: `LibraryDisplaySettings` + `UiNavSnapshot` / `LibraryUiPreferencesCodec`.

## 4. Portadas y metadata: álbum ≠ playlist ≠ canción

| Tipo | Comportamiento | Entry points |
|------|----------------|--------------|
| **Álbum (override)** | Tabla `album_overrides`; UI lee override si existe. **Guardar para álbum** = solo override; **Guardar para álbum y canciones** = override + bulk update de songs. Ambos pasan por `saveAlbumOverride(propagateToSongs)` | `requestSaveAlbumMetadata` → `saveAlbumOverride` → `upsertAlbumOverride` / `updateAlbumMetadataPropagateToSongs`; UI `EditAlbumMetadataDialog` |
| **Álbum menú** | Header de grupos (`TauonAlbumHeader` ⋮) y grilla (`AlbumGridCard` ⋮) → Editar / Cambiar portada vía `AlbumEditCoverMenuItems`; detalle de álbum también tiene IconButton Edit | `LibrarySongList` / `LibraryAlbumGrid` / `LibraryScreen` |
| **Álbum merge** | Renombrar a un álbum existente → `ConfirmMergeAlbumsDialog`; al confirmar, canciones de A adoptan metadata de B. Match con `normalizeAlbumName` (trim, `…`/`â€¦` → `...`, ignoreCase) vía Room en `requestSaveAlbumMetadata`. `mergeAlbumInto` también pliega otras keys equivalentes (mojibake) | `requestSaveAlbumMetadata` / `confirmPendingAlbumMerge` / `findAlbumMergeTarget` / `AlbumNames.kt` |
| **Álbum portada** | `setAlbumArtwork` → `saveAlbumOverride(..., propagateToSongs = true)` | `MusicPlayerViewModel.setAlbumArtwork` |
| **Playlist** | `Playlist.coverUri` / `PlaylistEntity.coverUri` es de la lista; **no** pisa artwork de canciones | `createPlaylist` / `updatePlaylist`, `savePlaylistCoverImage` |
| **Canción** | Editar una canción **no** reescribe el álbum ni siblings | `updateSongMetadata` (incluye `year` + `trackNumber`); UI `EditSongMetadataDialog` (Nº de pista; encoding MediaStore `disc*1000+track` vía `encodeAlbumTrack`) |
| **Persistencia local** | Copiar imagen a `context.filesDir` (`album_covers` / playlist covers); URI unificada `file.toURI()` vía `persistUserCover` | `saveAlbumCoverImage`, `savePlaylistCoverImage`, `extractAndSaveEmbeddedArtwork` |

Herencia visual en lista: `GetLibrarySongsUseCase.execute` unifica artwork faltante desde otras canciones del mismo álbum; `extractAlbums(songs, overrides)` aplica `AlbumOverride`.

## 5. Playlists locales

CRUD + membresía vía `IMusicRepository`:
`createPlaylist`, `updatePlaylist`, `deletePlaylist`, `addSongToPlaylist`, `removeSongFromPlaylist`.
Import LB: matched + `PlaylistPendingTrack` (`identity` + mbid/playlist extras; `getPlaylistPendingTracksFlow` / `downloadPlaylistPendingTracks`). Entity Room plana: columna `releaseName` ↔ `identity.album`.
Flows: `playlistsFlow`, `getPlaylistSongsFlow`, `getPlaylistDetailsFlow`.
UI: `PlaylistsScreen`. Detalle abierto = `PlaylistDetailNav` persistido (`openLocalPlaylist` / `closePlaylistDetail`); id inválido al restore → lista general.

## 6. Importación / biblioteca local

**Invariantes:**
- Unicidad lógica por `matchKey(artist, title)` (además del índice Room `uriString`).
- `Music/BestiaPop` es app-managed: `scanMediaStore` **no** reinserta esos archivos (evita duplicar `file:`/path vs `content://`). Tras reinstall, `resyncAppManagedMusic()` reindexa esos archivos por path absoluto.
- Import disco (MediaStore + BestiaPop) solo en **primer arranque** / post-uninstall (`LibraryPreferencesRepository.initial_library_scan_completed`); updates no re-escanean (Room migraciones sí).
- Audio local: un solo API `MusicFileStore` + `AudioPersistRef.canonicalize` (escribir/abrir/borrar/playableUri). BestiaPop se persiste como **path absoluto** en `Music/BestiaPop`; música ajena de MediaStore queda `content://media`. Callers no ramifican por scheme. Arranque: `migrateCanonicalAudioUris` (SAF/cache → abs; colisión remapea playlists). WiFi `/existing-files` = union Room basename + `listManagedNames`.
- Escritura BestiaPop: `MusicFileStore.prepareWrite` → File si el dir es writable; si no (UID viejo), MediaStore al mismo relative path (`StorageUtils`). Debug y release (mismo `applicationId`) usan esa carpeta.
- Import SAF (`scanFolderUri`) guarda `AudioPersistRef` (abs si el document id mapea a filesystem). Reproducción: `playableUri` (SAF viejo → `file://`). Fallo local → toast «No se pudo reproducir».
- Playlists/overrides viven en Room (app-private): **no** sobreviven uninstall (solo los archivos de audio en `Music/BestiaPop`).
- Descarga con conflicto → `DuplicateSongException` / `DownloadConflict` → diálogo Sobrescribir | Crear nueva | Cancelar (`DownloadConflictPolicy`).
- One-shot migrator histórico: branch `archive/library-dedup-v1-migrator` (no compila en LB).
- Tags Unknown en reimport: `AudioFileMetadata.applyFilenameHints` / `parseFilenameMetadataHints` recuperan artist/title de `Artist_Title` (sin inventar álbum).
- Identificar online (manual, multi-select, ⋮ o WiFi): Fase 1 lookup + score (`IdentifyRanking`); tags actuales de la canción son **fuente predominante** (`sourceArtist`/`sourceTitle`/`sourceAlbum`); auto-aplica solo **HIGH**; conflicto grave (artista/álbum/título distinto, versión, YouTube) → nunca HIGH. Si LB `enabled` + token y confianza ≠ HIGH (sin `customQuery`), enriquece con `lookupRecordingMetadata` → `fetchRecordingMetadata` → `toListenBrainzCatalogTrack` y re-rank (`IdentifyProposal.usedListenBrainz`). MEDIUM/LOW/NONE van a cola persistida `IdentifyReviewStore` (DataStore; cold start hidrata, **no** abre overlay). UI `IdentifyReviewScreen`: overview por álbum sugerido (`clusterIdentifyAlbumGroups`, solo MEDIUM + álbum no genérico, size ≥ 2) o cola canción a canción (Usar / Omitir / Buscar otro / Aplicar automático a restantes = solo MEDIUM con suggested / Omitir todas / Aplicar grupo). Cerrar oculta overlay y **conserva** la cola (`pendingCount`); banner biblioteca + botón WiFi retoman. Re-identificar lote/WiFi **omite** songIds ya pendientes (cero red); ⋮ sobre pending abre ese ítem. Progreso en `libraryJobProgress` + toast resumen. Telemetría lote (sin PII): `CrashReporter.setKey`/`log` keys `identify_high`/`identify_medium`/`identify_low`/`identify_none`/`identify_skipped`/`identify_lb_hits`.
- Álbum genérico (`IdentifyRanking.isGenericAlbum`: `YouTube`, `YouTube Music`, `Unknown Album`, `Single`, `Álbum`/`Album`) **sí** entra a identify; no se omite como ya identificado. Provider YouTube y versiones extra (live / letra / remix / cover…) nunca son HIGH → review. `cleanIdentityTitle` no persiste `+ letra` / `(Original Mix)`.
- Descarga online: álbum genérico dispara `fetchFullTrackMetadata` → `TrackIdentity?`; no se guarda `"YouTube"` — fallback `"$artist - Single"`.
- Import/resync/identify reportan progreso vía `LibraryScanProgress` / `LibraryJobProgress` (banner en biblioteca).

| Acción | API |
|--------|-----|
| Scan MediaStore | `scanMediaStore(onProgress?)` (skip BestiaPop + path/matchKey conocidos) |
| Reindex app music | `resyncAppManagedMusic(onProgress?)` → `Music/BestiaPop` filesystem walk; VM `ensureInitialLibraryImport` (1ª vez) / `refreshLibraryFromDisk` (force) |
| Scan carpeta SAF | `scanFolderUri(treeUri, onProgress?): Int` (incluye BestiaPop; guarda abs path si se puede resolver; toast en `importFolder`) |
| Metadata archivo → Room | `AudioFileMetadata.fromPath` / `toSong` (`identity` + genre; filename hints si Unknown; `parseCdTrackNumber` de tags CD_TRACK/DISC) |
| Upload WiFi → DB | `saveUploadedSong` (`AudioPersistRef`; merge por matchKey); VM observa `DONE` → `identifySongs(force=true, showReview=false)` |
| Canonicalizar URIs | `MusicRepository.migrateCanonicalAudioUris` (startup, junto a `migrateLegacyYouTubeMusicSongs`) |
| Lookup duplicado | `findSongByArtistTitle` |
| Descarga + política | `downloadAndSaveOnlineTrack(..., conflictPolicy)` |
| Conflicto UI | `downloadConflict` / `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict` + `DownloadConflictDialog` |
| Borrar app / dispositivo | `deleteSongsFromApp` / `deleteSongsFromDevice` |
| Enriquecer meta/letras | `enhanceSongMetadataAndLyrics` (portada/letras/duración; **no** artist/álbum) |
| Proponer identidad | `proposeSongIdentity(song, customQuery?, force?, listenBrainzToken?)` → `IdentifyProposal` (`usedListenBrainz`) |
| Aplicar candidato | `applySongIdentity` → `candidate.identity.mergePreferring(entity)` (+ clean title / fallback album) → `Song.withIdentity` → `IdentifyResult` |
| Identificar lote | VM `identifySongs(songs, force, showReview)` → auto HIGH + cola review; skip `pendingSongIds` |
| Identificar una | VM `identifySongForReview` (menú ⋮; si pending → abre ítem, si no `force=true`); UI `IdentifyReviewScreen` |
| Review preview | Local `previewIdentifyLocalSong`; candidato stream `previewIdentifyCandidate` → `candidate.track` → `playOnlineCatalogTrackAsStream` (`PreviewPlayPauseButton`) |
| Review acciones | `applySelectedIdentifyCandidate` / `skipIdentifyReviewItem` / `searchIdentifyCandidates` / `dismissIdentifyReview` (oculta) / `showIdentifyReview` / `applyRemainingIdentifySuggestions` (MEDIUM) / `skipAllIdentifyReview` / `applyIdentifyAlbumGroup` / `startIdentifyItemReview` / `returnIdentifyReviewOverview`; “Buscar otro” arriba de candidatos, draft = artista+título (no path SAF) |
| Review persist | `IdentifyReviewStore` + `IdentifyReviewCodec` (sin `audioUrl` CDN); hydrate `identifyReviewFromPersisted` (huérfanos fuera); prune al borrar canciones |
| Ranking | `IdentifyRanking.score` / `rank` / `confidence` / `hasSevereConflict` / `isGenericAlbum` / `isPlaceholderArtist` / `isPreferredProvider` (Deezer/iTunes/Catalog/ListenBrainz) / `cleanIdentityTitle`; `Query.sourceArtist`/`sourceTitle`/`sourceAlbum`; grupos `clusterIdentifyAlbumGroups` |
| Progreso biblioteca | `libraryJobProgress` (`LibraryJobKind.IMPORT` \| `IDENTIFY`) + `LibraryProgressBanner`; pending `IdentifyPendingBanner` |

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

## 7c. Aleatorio y repetición entre sesiones

**Invariantes:**
- Último `_isShuffle` + `RepeatMode` se persisten siempre (`lastShuffleEnabled` / `lastRepeatMode`).
- Shuffle es flag de VM + permutación de índices (`shufflePlayOrder`) sobre la cola fuente; UI observa `displayQueue`. Toggle **no** llama `setMediaItems`. Player: `shuffleModeEnabled` + `DefaultShuffleOrder` vía `MusicService.ACTION_SET_SHUFFLE_ORDER` tras timeline listo (`syncShuffleToPlayerWhenReady`; servicio ignora order si `length ≠ mediaItemCount`). Play/Mezclar de colección se serializa (`playCollectionJob`). Drag en cola visual: shuffle ON → solo permutación; OFF → `moveQueueItem` timeline. `queue_json` persiste `shufflePlayOrder`; hydrate remapea si caen locales.
- Arranque en frío (sin timeline): restaurar según `rememberShuffleOnLaunch` / `rememberRepeatOnLaunch` (on por defecto). Off → ese modo arranca apagado.
- **Autoplay al abrir** (`autoplayOnLaunch`, off por defecto): mismo flag para Local y Remote. Off → mini player / cola hidratada sin `play()`. On → `maybeAutoplayAfterIdleSeed` → `togglePlayPause`. Sesión FGS viva que ya suena no se toca.
- Sesión viva: repeat del `MediaController`; shuffle desde prefs (única fuente). Switches de Ajustes no cambian la sesión actual.
- Play/tap manual (`playCollection` / `playSong`) aplica `PlaybackModeClear.afterManualPlay`. Default: apaga shuffle + Repeat One; Repeat All se mantiene. Tap en cola (`skipToQueueIndex`) **no** apaga shuffle (índice display→timeline). Next/prev in-app: `afterSkip` (default: solo Repeat One; shuffle intacto). Resume / radio no aplican; radio apaga shuffle al armar cola. `shuffleCollection` enciende shuffle (permutación) y aplica clears de repeat de manual play.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `PlaybackSettings` + `PlaybackModeRestore.resolve` / `PlaybackModeClear.afterManualPlay` / `afterSkip` / `parseRepeatModeName`; writes 1-key `DataStore.put` |
| Settings UI | `PlaybackSettingsScreen` vía `SettingsScreen` sección Reproducción |
| Restore | `MusicPlayerViewModel.restorePlaybackModes` tras `syncUiFromController` |
| Persist | `setShuffleEnabled` / `setRepeatMode` / `toggleShuffle` / `toggleRepeatMode` / `finishPlayPlayableCollection` (`startShuffled`) / `QueueSnapshot.shufflePlayOrder` |
| Remember flags | `setRememberShuffleOnLaunch` / `setRememberRepeatOnLaunch` / `setAutoplayOnLaunch` |
| Clear-on-play | `setClearShuffleOnManualPlay` / `setClearRepeatAllOnManualPlay` / `setClearRepeatOneOnManualPlay` / `applyManualPlayModes` |
| Clear-on-skip | `setClearShuffleOnSkip` / `setClearRepeatOneOnSkip` / `applySkipModes` / `skipToNext` / `skipToPrevious` |

## 8. WiFi Sync

`WebServerService` (Ktor) + `WebServerScreen(viewModel)`.

| Capacidad | Entry point |
|-----------|-------------|
| Servidor local | `WebServerService` + toggle en `WebServerScreen` |
| Omitir existentes | `GET /existing-files` = Room basename ∪ `MusicFileStore.listManagedNames` (`Music/BestiaPop`) |
| Transferencias en app | `WebServerService.transfers` (`WifiTransferItem` / `WifiTransferState`); lista en `WebServerScreen` (progreso + `SongListItem` al completar) |
| Identify al importar | Tags embebidos (ID3) → Room → mismo `identifySongs(force=true, showReview=false)`; conflictos en cola persistida compartida (omite ya pending) |
| Revisar conflictos | Botón `Revisar conflictos de información (N)` → `showIdentifyReview()`; N = `identifyReview.pendingCount` (sobrevive process death) |
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
- Match local por artist+title normalizado (`TrackMatchKeys.matchMetasAgainstLibrary` / `matchAgainstLibrary`; L1 `buildLibraryIndex` + `lookupLocalSong` para radio); faltantes = `PlayableItem.Remote`. Rematch LB/CF tras descarga = `List.rematchLocals`. Query YT / id catálogo = `TrackMeta.youtubeSearchQuery` / `TrackIdentity.toCatalogTrack`.
- Reproducción: cola mixta `Local|Remote` vía `playPlayableCollection` (prefetch / 403 retry de stream).
- **Descarga manual** de un Remote en detalle Para Ti / Recomendados o Now Playing: icono Descargar en `RemoteTrackPlaceholderRow` / CTA `NowPlayingRemoteDownloadAction` → `downloadRemoteItem` (`ActiveDownloadSource.DISCOVER`); progreso en Descargas (+ estados en NP); al éxito rematch LB + CF.
- **Guardar al escuchar** (`saveWhileListening` + `saveWhileListeningPercent`): al alcanzar ≥N% de la duración (o fin) de un Remote, encola en `activeDownloads` vía `runTrackedDownload` (sin reemplazar el MediaItem). Fallo → `ERROR` en el centro + Toast; quita la key de `saveWhileListeningAttempted` para permitir reintento manual/auto.
- **Import a Room:** “Guardar” crea playlist local con matched + metadata pendiente de faltantes (`playlist_pending_tracks`); “Descargar faltantes” / detalle local encola vía `runTrackedDownload` (`LB_IMPORT` + `targetPlaylistId`). Progreso en tab Descargas; nunca CDN en Room.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `ListenBrainzPreferencesRepository` / `ListenBrainzSettings` (`saveWhileListening`, `saveWhileListeningPercent`) |
| Settings UI | `ListenBrainzSettingsScreen` — registrar + **Mostrar Para Ti** + **Guardar al escuchar** (+ slider %) |
| Submit listens | `ListenBrainzClient.submitListens`, `ListenTracker`, `ListenSyncCoordinator` |
| List Discover | `ListenBrainzClient.fetchCreatedForPlaylists` → `MusicPlayerViewModel.refreshListenBrainzDiscoverPlaylists` |
| Abrir playlist | `openListenBrainzPlaylist` + `MatchListenBrainzTracksUseCase` |
| Map a cola | `MatchedLbPlaylist.toPlayableItems` / `MatchedRemoteTrack.toPlayableItem` |
| Play / shuffle / índice | `playMatchedTracks` / `shuffleMatchedTracks` → `playMatchedCollection` / `shufflePlayableCollection` con `DiscoverPlaybackOrigin` (`MatchedLbPlaylist.toDiscoverOrigin`) |
| Import locales + pendientes | `saveListenBrainzPlaylistAsLocal` → `ImportListenBrainzPlaylistUseCase.createLocalFromMatched` (`PlaylistPendingTrack(identity = track.identity)`; unmatched → `OnlineCatalogTrack(identity, provider = ListenBrainz)`) |
| Import + descarga ya | `importListenBrainzPlaylistWithDownloads` / `downloadPlaylistPendingTracks` → `runTrackedDownload` (`LB_IMPORT`) |
| Descarga manual Remote | `downloadRemoteItem` → `runTrackedDownload` (`DISCOVER`); UI `RemoteTrackPlaceholderRow.onDownload` en detalle LB/CF; NP `NowPlayingRemoteDownloadAction` |
| CF Recomendados | `ListenBrainzClient.fetchCfRecordingRecommendations` → `FetchAndMatchCfRecommendationsUseCase` → `refreshCfRecommendations` / `openCfRecommendations` / `playMatchedTracks` / `shuffleMatchedTracks` |
| UI sección | `PlaylistsScreen` — "Para Ti" + "Recomendados"; Guardar / Descargar faltantes / descarga por track; detalle local muestra pendientes |
| Restore sesión | `playlistDetail` `ListenBrainz` / `CfRecommendations` + `selectedNavIndex`; fetch al hidratar/abrir tab Playlists; fallo (sin red, Discover off, API) → lista general + toast (`restoreDiscoverDetailOrFallback`) |

## 10. Stream remoto (playback sin descarga)

**Invariantes:**
- Cola unificada `List<PlayableItem>` (`Local` | `Remote`); APIs `Song` se adaptan con `Song.toPlayable()`.
- Re-extraer stream YouTube just-in-time (`MusicRepository.streamResolver` → `YouTubeExtractor`); cache memoria TTL ~4 min para **playback**; download llama `resolveQuery(forceRefresh = true)` (no reusa CDN cacheado); **no** guardar `audioUrl` CDN en Room.
- ExoPlayer usa UA del extract vía `StreamPlaybackTag` en `MusicService`.
- Prefetch índices N+1 / N+2; un reintento en 403/IO luego `seekToNext`.
- Descarga explícita (“Agregar”) sigue download-then-play; stream no la reemplaza.

| Capacidad | Entry point |
|-----------|-------------|
| Modelo | `PlayableItem` (`TrackMeta`; `Remote` guarda `identity` + mbid/stream), `ResolvedStream` en `data/model/PlayableItem.kt` |
| Resolver | `MusicRepository.streamResolver` (`StreamResolver.resolve` / `prefetch` en `data/stream/StreamResolver.kt`) |
| UA ExoPlayer | `StreamPlaybackTag` + `MusicService` `UserAgentMediaSourceFactory` |
| FGS background | Canal `playback_channel` + `promotePlaybackForeground` (`Service.startForeground` tipo `mediaPlayback`; try/catch + Crashlytics). VM solo `controller.play()`. ExoPlayer `WAKE_MODE_NETWORK` + permiso `WAKE_LOCK`. FGS se mantiene con `playWhenReady` aunque el state sea IDLE breve (resolve Remote); se suelta en pause/`STATE_ENDED`. Play Store: FGS basta. Sideload OEM (Moto): `install.sh` alinea `adaptive_bucket` + `RUN_ANY_IN_BACKGROUND allow` vía adb — no viaja en el APK |
| Cola / play | `playPlayableCollection`, `currentItem`, `resolvingRemote` en `MusicPlayerViewModel` |
| Stream desde catálogo | `playOnlineCatalogTrackAsStream` + preview in-dialog (`CatalogTrackItem` / `CandidateTrackCard` + `CatalogPreviewBar`); `cycleSongCatalogResult` / `cycleTrackCandidate` (“Buscar otro”) |
| UI player | `BottomPlayerBar` / `NowPlayingScreen` / `QueueScreen` observan `PlayableItem` |

## 10b. Continuidad del mini player

**Invariantes:**
- Tras reconnect de `MediaController`, `syncUiFromController` rehidrata `_queue` / `currentItem` / `isPlaying` / posición desde la sesión viva (prioridad sobre snapshot persistido).
- Sin sesión viva: hidratar cola persistida (`queue_json`: current + upcoming + last `MAX_QUEUE_HISTORY` = 20). Locals rematch por id/uri; Remotes identity+mbid+query/`videoId` **sin** CDN. Si current se borró, avanzar al siguiente (posición 0). Si no hay cola usable: last-played local o aleatoria. Autoplay solo si `autoplayOnLaunch` (Local = Remote). `ensureRemoteReadyAt(..., startPlaying = playWhenReady)` en sync y `onMediaItemTransition` — hydrate/`prepare` no dispara `play()` en remoto.
- Idle play: si el controller ya tiene items → play/pause. Si current Remote necesita resolve o `mediaItemCount == 0` con `_queue` hidratada → `playPlayableCollection(queue, index, rotate = false)` (no reconstruir biblioteca).
- Biblioteca vacía y sin sesión → `BottomPlayerBar` oculto (`currentItem == null`).
- Con playback activo (`playWhenReady`, items en timeline, no `ENDED`), `MusicService` permanece FGS `mediaPlayback` (notif Now playing + `setSessionActivity`) aunque la Activity esté en segundo plano o el player esté un instante en IDLE al resolver Remote; sin FGS el proceso queda cached y LMK lo mata al abrir otras apps. Wake: `WAKE_LOCK` + `ExoPlayer.setWakeMode(NETWORK)`.
- No persistir CDN de `Remote`. Mini bar: Previous + status (`Resolviendo…` / `Armando radio…` / `radioStatusLabel`).

| Capacidad | Entry point |
|-----------|-------------|
| Resync sesión | `MusicPlayerViewModel.syncUiFromController` / `mediaItemToPlayable` / `loadHydratedQueueIntoController` |
| Last-played + cola | `PlaybackSessionStore`, `LastPlayedCodec`, `QueueSnapshotCodec`, `PlaybackHydration.hydrateQueue` en `data/preferences/PlaybackSessionStore.kt` |
| Wrap / trim | `PlaybackQueueOrder.rotateToStart` / `trimHistory` (+ remap `shufflePlayOrder`) en `data/playback/PlaybackQueueOrder.kt` |
| Seed idle | `maybeSeedIdlePlayer` / `applyHydratedQueue` / `maybeAutoplayAfterIdleSeed` |
| Mini bar UI | `BottomPlayerBar` (`statusLabel`, Previous); wiring en `MainScreen` |

## 10c. Acciones de canción/álbum en Now Playing

**Invariante:** ⋮ junto al título (radio sigue en el header). Nav a biblioteca/playlist **cierra** NP + limpia search. Editar / añadir a playlist / identificar / radio no cierran NP.

| Acción | Cuándo | Entry point |
|--------|--------|-------------|
| Ir al álbum / artista | Match en `albumsState` / `artistsState` | `openLibraryAlbum` / `openLibraryArtist` + `setSelectedNavIndex(0)` |
| Ir a playlist local | Membresía Room (`getPlaylistIdsForSong`) | `openLocalPlaylist` + tab Playlists |
| Ir a Para Ti / Recomendados | `DiscoverPlaybackOrigin` (sesión; no persistido) si play/shuffle desde LB/CF | `openListenBrainzPlaylistDetail` / `openCfRecommendationsDetail` |
| Añadir a playlist / Identificar / Editar canción | Solo `PlayableItem.Local` | `SongActionDialogsHost` / `identifySongForReview` |
| Editar álbum | Local + álbum en biblioteca | `AlbumEditDialogsHost`; merge único en `MainScreen` (`pendingAlbumMerge`) |
| Descargar ahora | Solo `PlayableItem.Remote` (visible bajo título) | `NowPlayingRemoteDownloadAction` → `downloadRemoteItem`; estados vía `activeDownloads` |
| Iniciar radio | Siempre | `startRadio()` (mismo que icono header) |

Origen Discover: se setea en `playPlayableCollection(..., origin)` (wrappers CF/LB pasan `CfRecommendations` / `ListenBrainz`); `None` al armar cola local / radio que muta cola / `applyHydratedQueue`.

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
| UI | Now Playing (tap/long-press + ⋮ “Iniciar radio”); mini bar `statusLabel` (radio / resolving); menú canción biblioteca “Iniciar radio” |

## 12. System back (jerarquía UI)

**Invariante:** un gesto atrás = un paso atrás. Paridad con ArrowBack / chevron / Cancel. No detiene reproducción. Sin Navigation Compose: `BackHandler` por capa.

| Prioridad | Comportamiento | Entry point |
|-----------|----------------|-------------|
| Diálogos / menús | Framework `onDismissRequest` | `Dialog` / `AlertDialog` / `DropdownMenu` (NP ⋮ + merge álbum en `MainScreen`) |
| Identify review | ITEM+overview → vuelve overview (`returnIdentifyReviewOverview`); si no, oculta overlay y conserva cola (`dismissIdentifyReview`); `skipAllIdentifyReview` vacía | `IdentifyReviewScreen` `BackHandler` |
| Add Music colección | `clearSelectedCollection` antes de cerrar | `AddMusicDialog` `BackHandler` + `onDismissRequest` |
| Now Playing | `dismissFullPlayer` | `NowPlayingScreen` `BackHandler` |
| Library nested | multi-select → cancel addition → álbum (`closeLibraryAlbum`, conserva artista) → artista → clear search | `LibraryScreen` `BackHandler` / `popLibraryNested` |
| Playlists nested | un detalle a la vez (local / LB / CF) → lista | `PlaylistsScreen` `BackHandler` → `closePlaylistDetail` |
| Settings nested | Temas / LB / Reproducción / Sonido → home | `SettingsScreen` `BackHandler` |
| Raíz de tab | Doble atrás (~2s) + snackbar “Pulsa otra vez para salir” | `MainScreen` `BackHandler` + `SnackbarHost` |

Manifest: `android:enableOnBackInvokedCallback="true"` en `MainActivity`.

## 13. Beta / Crashlytics

**Invariante:** builds para testers = `release` firmado (`./install.sh --release`), no debug. Crashes/non-fatals → Firebase Crashlytics (colección deshabilitada en `BuildConfig.DEBUG`). Sin Firebase Analytics ni advertising ID (`AD_ID` se quita del manifest mergeado).

| Acción | Entry point |
|--------|-------------|
| Init | `BestiaPopApplication.onCreate` |
| Non-fatal + keys | `CrashReporter.recordNonFatal` / `setKey` / `log` |
| Call sites | `YouTubeExtractor.extractAudioStreamDetailed`, `MusicService` `onPlayerError`, `WebServerService` start/transfer, `MusicPlayerViewModel.runTrackedDownloadLocked` onFailure; identify batch `reportIdentifyBatchTelemetry` (`setKey`/`log`, no PII) |
| Config Firebase | `app/google-services.json` (gitignored) |
| Firma release | `keystore.properties` + `.jks` (gitignored; plantilla `keystore.properties.example`) |
| Versión | `version.properties` (`VERSION_CODE` / `VERSION_NAME`; bump en `./release.sh`) |
| GitHub Releases | `./release.sh` → APK `BestiaPop-{VERSION_NAME}.apk`; tag `v{VERSION_NAME}`; notas con `versionCode: N` (el update in-app lo lee del body); repo `github-release.properties` `GITHUB_REPOSITORY` → `BuildConfig.GITHUB_REPOSITORY` |
| Invitar amigos | Ajustes → Invitar amigos: `ACTION_SEND` con `https://github.com/{repo}/releases/latest` (`GitHubReleaseUrls.latestPageUrl`) |
| Update in-app | Al abrir (release, máx. 1/12h) + Ajustes → Buscar actualización: `GitHubUpdateClient.fetchLatest` vs `VERSION_CODE`; descarga APK + `FileProvider` + `REQUEST_INSTALL_PACKAGES`. UI `AppUpdateViewModel` / `AppUpdateDialogs` (no `MusicPlayerViewModel`) |
| Play Console AAB | `./deploy-play.sh --upload --rollout` path legacy (no distribución de producto) |

## Relacionado

- Capas y stack → `bestiapop-architecture`
- Paths exactos → `bestiapop-implementation-map`
