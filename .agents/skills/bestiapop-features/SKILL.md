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
| Reproducir Local\|Remote | `MusicPlayerViewModel.playPlayableCollection(items, startIndex, rotate, origin)` | Façade → `PlaybackRuntime.playPlayableCollection` + `StreamResolver`; `origin` queda process-scoped en el runtime para Discover (CF/LB) |
| Shuffle | `shuffleCollection(songs)` / `shufflePlayableCollection(items, origin)` | `playPlayableCollection(..., startShuffled=true)` + permutación current-first |
| Encolar | `enqueueCollection(songs)` | append a cola `PlayableItem` |
| Una canción | `MusicPlayerViewModel.playSong(song, playlistOrQueue)` | adapta a colección y delega en `PlaybackRuntime.playPlayableCollection` |
| Tap en Cola / NP | `skipToQueueIndex(index)` | display == físico (`PlaybackRuntime.displayQueue`); **no** rota ni apaga shuffle. `PlaybackSelectionIntentGate` + job process-scoped hacen latest-tap-wins; antes de aplicar recalcula por `queueEntryId`. Compose usa ese mismo id como key/foco/scroll, así duplicados exactos siguen siendo slots distintos. Si un Remote falla, `PlaybackFallbackPlanner.circularPlan` prueba la cola circularmente e incluye Local |

Archivos: `ui/MusicPlayerViewModel.kt` (façade pública); `service/PlaybackRuntime.kt` (`playPlayableCollection` / `toggleShuffle` / `displayQueue` / `moveQueueItem` / `skipToQueueIndex`); `data/playback/PlaybackQueueOrder.kt` (`shufflePlayOrder`); `data/playback/PlaybackQueueSlots.kt`; `data/playback/PlaybackFallbackPlanner.kt`; `data/playback/PlaybackSelectionIntentGate.kt`; `service/MusicService.kt` (`ACTION_SET_SHUFFLE_ORDER` legacy); `ui/components/PlayShuffleButtons.kt`.

## 2. Búsqueda online y descarga de audio

**Invariante:** Catálogo/metadatos pueden venir de iTunes/Deezer; el stream se resuelve con YouTube. Re-extraer URL CDN antes de descargar (evitar HTTP 403).

**Invariante búsqueda:** `MusicPlayerViewModel.searchCatalog` cancela la consulta anterior y solo
publica la generación más reciente; la carga inicial vacía del diálogo nunca puede borrar una
búsqueda escrita por el usuario.

**Invariante bytes (`downloadAndSaveOnlineTrack`):** solo se publica el archivo si la descarga terminó completa. Se exige `downloadSuccess` **y** el total contra `Content-Length` (EOF corto = truncado, no éxito); se concatena solo con `206`; el stream va en `use`; ante `403`/`410` se re-extrae y reinicia con backoff. Al fallar se borra el parcial. El loop L1 `copyTransferToFile` se comparte con APK update; Range/retry/validación/publicación siguen en cada dominio.

| Paso | Dónde |
|------|--------|
| Search catálogo tracks | `MetadataFetcher.searchOnlineCatalog` / `YouTubeExtractor.searchYouTube` (`parseSearchContents` + `audioPreferenceScore` / `rankByAudioPreference`: prioriza Topic / Official Audio sobre music video) |
| Géneros / charts | `MetadataFetcher.listGenres` / `searchTracksByGenre` / `fetchChartTracks` (`parseCatalogGenres` + `parseDeezerSearchTracks`); UI chips `CatalogCategory.GENRES` / `CHARTS` en `AddMusicDialog`; drill-down género → `selectGenreForInspection` (mismo BackHandler / batch que álbum) |
| Query YT desde catálogo | `YouTubeExtractor.resolveYouTubeQueryOrId` (ignora ids Deezer/iTunes; usa `audioUrl` o `artist title`) |
| Álbumes / playlists online | `MetadataFetcher.searchAlbums` / `searchPlaylists` + `fetchAlbumTrackCandidates` / `fetchPlaylistTrackCandidates` |
| Extraer stream | `YouTubeExtractor.extractAudioStream` / `extractAudioStreamDetailed` |
| Descargar + persistir | `DownloadAudioTrackUseCase.execute` → `IMusicRepository.downloadAndSaveOnlineTrack` (`onProgress: DownloadPhase`; persiste `OnlineCatalogTrack.trackNumber` / `TrackIdentity.trackNumber` de `fetchFullTrackMetadata`); copy/UI labels `DownloadMessages` |
| UI diálogo | `ui/components/AddMusicDialog.kt`; estado agrupado `CatalogSearchUiState` (con `searchFilters` artista/álbum/año y UI compacta) / `CatalogCollectionUiState` vía `MusicPlayerViewModel.catalogSearch` / `catalogCollection`; consulta construida con `IdentifyCatalogQuery.build` |
| Centro de descargas | `DownloadsScreen` + `ActiveDownloadRow`; persistencia `ActiveDownloadsStore` / `ActiveDownloadCodec`; notif `DownloadNotificationHelper`; badge `activeDownloadBadgeCount` en tab Descargas (`MainScreen`) |
| Orquestación | Manual: VM `runTrackedDownload` adapta a `ProcessDownloadRequest` → `ProcessDownloadRuntime.submit` desde `enqueueTrackedBatch`, `downloadSingleCandidate`, `downloadSelectedCandidatesBatch`, `downloadFromUrl`, `downloadOnlineTrack`, `downloadRemoteItem`; autosave: `ProcessSaveWhileListeningCoordinator.save`; ambos llaman `ProcessDownloadCoordinator.execute`; gate metered tras permit (`DownloadPreferencesRepository.downloadOnMeteredNetwork` default true); acciones `retryActiveDownload` / `resumeAllDownloads` / cycle/preview/play/dismiss; deep-link `requestOpenDownloads` |

Modelo clave: `TrackIdentity` / `TrackMeta`, `OnlineCatalogTrack`, `CatalogTrackCandidate`; chrome UI derivado de `activeDownloads.findUiDownloadByTrack`, mientras claims usan `ProcessDownloadRuntime.findClaimedDownload`. `ActiveDownload` conserva `currentTrack`, policy/stages y transiciones (`asDownloading`/`asConflict`/`asSuccess`/`asError`); `DownloadLane` separa EXPLICIT/AUTOSAVE; `DownloadPlaylistDestination` es el único target playlist+identity. `ActiveDownloadSource`: CATALOG/LINK/SAVE_WHILE_LISTENING/BATCH/LB_IMPORT/DISCOVER. JSON legacy mantiene `displayTitle`/artwork compat. Copy: `DownloadMessages`.

**Invariante cola:** todas las descargas online se registran en `ProcessDownloadCoordinator.downloads` (`QUEUED` → `DOWNLOADING` → `SUCCESS`/`ERROR`); éxito se retiene hasta dismiss. `ProcessDownloadRuntime` posee jobs fuera del VM; coordinator reclama plain/`batch:` y comparte `Semaphore(3)`. Cada row persiste destinos, lookup, batch/policy y stages; `updateProgress` coalesce snapshots de porcentaje, mientras altas/cancelaciones/conflictos/terminales siguen con persist inmediata y `flushDurably` ocurre antes del lifetime/bytes y tras commit storage. Tras kill o stop de Job, active → ERROR interrumpido; `resumeInterrupted` reencola tanto en cold start como en `MainActivity.onStart` warm salvo salida `REASON_USER_REQUESTED`. Explícitas: UIDT API34+ / FGS dataSync API26–33; Guardar al escuchar: constrained job. `blocksBackgroundPlayback` solo si AppOps `RUN_ANY_IN_BACKGROUND` está IGNORE/ERRORED (`isRunAnyInBackgroundBlocked`; no basta `isBackgroundRestricted`). `backgroundRestrictionGuidance(manufacturer)` elige el copy. Aviso OEM `lock_screen_battery_save` solo si el setting lee ON (`oemScreenOffCleanupActive`); `MainScreen` abre Batería vía `openOemScreenOffCleanupSettings`.

## 3. Biblioteca: filtro, orden y vistas

**Invariante:** `libraryProjection.songs` filtra por título/artista/álbum/género y ordena con `SortOption` + `SortDirection` (ASC/DESC). Orden, dirección, vista, chip de browse (`LibraryBrowseFilter`) y pila artista→álbum / género→álbum **persisten** entre sesiones (`LibraryPreferencesRepository`). Al cambiar `SortOption`, la dirección vuelve al default (DATE_ADDED → DESC; resto → ASC). Con `ALBUM_GROUPS`, los **bloques de álbum** se ordenan como `extractAlbums` (nombre / artista / género / `max(dateAdded)`); **dentro** de cada álbum las canciones van por `trackNumber` (0 al final + título). Play/shuffle/tap usan ese orden visual (`songsFromListItems`). Detalle de álbum siempre por pista. Un chip a la vez proyecta el pool (estilo YouTube Music); search no cambia el chip.

| Capacidad | API |
|-----------|-----|
| Query | `MusicPlayerViewModel.searchQuery` (no se persiste) |
| Sort | `SortOption`: TITLE, ARTIST, ALBUM, GENRE, DATE_ADDED → `setSortOption` (DataStore; resetea dirección); labels en sheet vía `SortOption.sortLabel(browseFilter)` |
| Dirección | `SortDirection` ASC/DESC → `setSortDirection` / `toggleSortDirection`; toggle **dentro** del sheet (no en header). ASC = flecha ↓ + `A–Z` (fecha: `Antiguo → reciente`); DESC = flecha ↑ + `Z–A` (fecha: `Reciente → antiguo`) vía `sortDirectionLabel` |
| Vista+orden UI | Chips = forma (`LibraryBrowseFilter`); botón Tune → `LibraryBrowseSortSheet` (“Ver como” + “Ordenar por”); summary a11y `libraryOrderSummary` / `libraryTuneContentDescription`; play/shuffle header = `PlayShuffleIconPair`. Con headers de álbum: título “Ordenar álbumes por” + icono `ViewAgenda` + texto de que el orden es por álbum (`albumHeadersActive`) |
| Énfasis fila | `sortEmphasisFor(song, sortOption)` / `sortEmphasisForLastPlayed` (chip RECENT) → title/subtitle/trailing; sort key trailing con color primary (`SongListItem`) |
| Filtrado/orden | `GetLibrarySongsUseCase.execute(songs, query, sortOption, sortDirection)` — query vía `TrackMatchKeys.containsNormalized` (case + tildes; blank needle = match all; punctuation-only → no match) |
| Vista plana vs grupos álbum | `LibraryViewMode.FLAT` / `ALBUM_GROUPS` → `setLibraryViewMode` / `toggleLibraryViewMode`; solo UI cuando chip = `SONGS`; `buildLibraryListItems` / `buildListItems(sortOption, sortDirection)` (bloques = `extractAlbums`); within-album `compareSongsWithinAlbum` / `sortSongsWithinAlbum` / `songsFromListItems` |
| Browse chip + pila | `MusicPlayerViewModel.navigation` (`UiNavigationState.libraryBrowseFilter` + `LibraryBrowseStack`) / `setLibraryBrowseFilter`; `openLibraryAlbum(fromNestedParent)`, `openLibraryArtist`, `openLibraryGenre`, `popLibraryNested` (álbum encima de artista\|género) |
| Multi-select | La selección **se mantiene** al buscar: los ids se resuelven contra `rawSongs` (sin filtrar), así que buscar acota lo que podés tildar sin perder lo ya tildado. La fila de chips se oculta mientras hay selección: con chips de agregados, «Seleccionar todo» tomaba toda la biblioteca |
| Colapsar álbum / todos | `collapsedAlbumNames` + toggle por header (`onToggleCollapseAlbum`); expandir/colapsar todo en `LibraryScreen` (vista grupos; no persistido) |
| Derivados | `LibraryProjectionState` usa `extractAlbums` / `extractArtists` / `extractGenres` (`sortOption` + `sortDirection`) → `MusicPlayerViewModel.libraryProjection.{songs,albums,artists,genres}`; play-all `songsForBrowseProjection` |
| Énfasis agregados | Álbumes: `TauonAlbumHeader.sortHint` vía `formatSortRelevantInfo`; artistas/géneros: subtítulo con mismo helper |
| RECENT | Chip label **Recientes**; canciones con `lastPlayedAt > 0` DESC; stamp `setCurrentItem` → `maybeTouchSongLastPlayed` (dedupe por songId / mismo mediaId; sin enhance extra en re-set); `emphasizeLastPlayed`; sección orden del sheet deshabilitada en RECENT (también con nested) |

UI: `LibraryScreen` (`NestedLibraryBrowse` para detalle álbum/artista/género), `LibraryFilterChipRow`, `LibraryBrowseSortSheet`, `LibrarySongList` (`onOpenAlbum`), `LibraryAlbumBrowseList` (`TauonAlbumHeader`), `LibraryAggregateListItem` + `LibraryArtistList` / `LibraryGenreList`.
Estado: `ui/state/LibraryProjectionState.kt`, `UiNavigationState.kt`, `LibraryBrowseFilter.kt`, `LibraryListItem.kt` y `LibraryUiState.kt` (solo `LibraryViewMode`). Prefs: `LibraryDisplaySettings` (`sortOptionName` / `sortDirectionName`) + `UiNavSnapshot.browseFilterName` / `LibraryUiPreferencesCodec` (legacy `library_tab` 0/1/2 → SONGS/ALBUMS/ARTISTS).

## 4. Portadas y metadata: álbum ≠ playlist ≠ canción

| Tipo | Comportamiento | Entry points |
|------|----------------|--------------|
| **Álbum (override)** | Tabla `album_overrides`; UI lee override si existe. **Guardar para álbum** = solo override; **Guardar para álbum y canciones** = override + bulk update de songs. Ambos pasan por `saveAlbumOverride(propagateToSongs)` | `requestSaveAlbumMetadata` → `saveAlbumOverride` → `upsertAlbumOverride` / `updateAlbumMetadataPropagateToSongs`; UI `EditAlbumMetadataDialog` |
| **Álbum menú** | Header de grupos y browse (`TauonAlbumHeader` ⋮) → Editar / Cambiar portada vía `AlbumEditCoverMenuItems`; detalle de álbum también tiene IconButton Edit | `LibrarySongList` / `LibraryAlbumBrowseList` / `AlbumEditCoverMenuItems` / `LibraryScreen` |
| **Álbum merge** | Renombrar a un álbum existente → `ConfirmMergeAlbumsDialog`; al confirmar, canciones de A adoptan metadata de B. Match con `normalizeAlbumName` (trim, `…`/`â€¦` → `...`, ignoreCase) vía Room en `requestSaveAlbumMetadata`. `mergeAlbumInto` también pliega otras keys equivalentes (mojibake) | `requestSaveAlbumMetadata` / `confirmPendingAlbumMerge` / `findAlbumMergeTarget` / `AlbumNames.kt` |
| **Álbum portada** | Solo portada: `IMusicRepository.setAlbumArtwork(albumKey, artworkUri)` = override + `MusicDao.setAlbumArtwork` (artwork de las canciones). **No** toca artist/genre/year (no es una edición de metadata) | `MusicPlayerViewModel.setAlbumArtwork` → `MusicRepository.setAlbumArtwork` |
| **Playlist** | `Playlist.coverUri` / `PlaylistEntity.coverUri` es de la lista; **no** pisa artwork de canciones | `createPlaylist` / `updatePlaylist`, `savePlaylistCoverImage` |
| **Canción** | Editar una canción **no** reescribe el álbum ni siblings | `updateSongMetadata` (incluye `year` + `trackNumber`); UI `EditSongMetadataDialog` (Nº de pista; encoding MediaStore `disc*1000+track` vía `encodeAlbumTrack`) |
| **Letra** | Room `Song.lyrics` (LRC o texto). ⋮ **Editar letra** (`EditLyricsDialog`): tab **Texto** (letra plana) + **Sincronizar** (tap para stamp; icono quita tiempo). Barra play/pausa + scrubber; Play arranca esa canción si no es la actual. Líneas con tiempo se resaltan con `currentLineIndex`. En NP tab Letra, tap en una línea con tiempo → `seekToAndPlay`. Fetch online no persiste hasta Guardar | `SongOverflowMenuItems` / `SongActionDialogsHost`; parse `SyncedLyrics` (`plainText` / `realignByText`); display NP: `NowPlayingLyricsPanel` |
| **Persistencia local** | Copiar imagen a `context.filesDir` (`album_covers` / playlist covers); URI unificada `file.toURI()` vía `persistUserCover` | `saveAlbumCoverImage`, `savePlaylistCoverImage`, `extractAndSaveEmbeddedArtwork` |

Headers de grupo: `LibraryListItem.AlbumHeader` lleva `albumName` (clave) **y** `displayName` (override) — `TauonAlbumHeader(title = …)` muestra el segundo, así que Canciones y Álbumes no divergen tras renombrar sin propagar.

Herencia visual en lista: `GetLibrarySongsUseCase.execute` unifica artwork faltante desde otras canciones del mismo álbum (**omite álbumes genéricos**: «Unknown Album» es el literal de todas las canciones sin álbum, así que heredar ahí estampaba una portada sobre temas no relacionados); `extractAlbums(songs, overrides, sortOption, sortDirection)` aplica `AlbumOverride` y ordena agregados.

## 5. Playlists locales

CRUD + membresía vía `IMusicRepository`:
`createPlaylist`, `updatePlaylist`, `deletePlaylist`, `addSongToPlaylist`, `removeSongFromPlaylist`.
Import LB: matched + `PlaylistPendingTrack` (`identity` + mbid/playlist extras; `getPlaylistPendingTracksFlow` / `downloadPlaylistPendingTracks`). Entity Room plana: columnas `releaseName` ↔ `identity.album` y `trackNumber` ↔ `identity.trackNumber`, para que la descarga conserve el orden de pista.
Similares multi-select: `BuildSimilarPlaylistPreviewUseCase.createPlaylistFromPlayables` (locales + pending remotos, mismo patrón LB; no CDN en Room).
Flows: `playlistsFlow`, `getPlaylistSongsFlow`, `getPlaylistDetailsFlow`.
UI: `PlaylistsScreen`. Detalle abierto = `PlaylistDetailNav` persistido (`openLocalPlaylist` / `closePlaylistDetail`); id inválido al restore → lista general.

## 6. Importación / biblioteca local

**Invariantes:**
- Unicidad lógica por `matchKey(artist, title)` (además del índice Room `uriString`).
- **Dedupe por path indexa las dos escrituras:** `libraryDedupSets` guarda el path absoluto resuelto **y** el `uriString` crudo, porque los scans consultan el `uriString` canónico (un `content://…/documents/…` en imports SAF) y `resolveFilePath` solo devuelve paths absolutos.
- **Insertar no clobbea:** `MusicDao.insertSong` / `insertSongs` usan `OnConflictStrategy.IGNORE` (no `REPLACE`: borraba la fila y la reinsertaba con id nuevo, dejando huérfanas las filas de `playlist_song_cross_ref` — sin FK/cascade — y perdiendo `lyrics` / `lastPlayedAt` / `dateAdded`). Colisión de `uriString` en alta de una canción → `MusicRepository.insertOrUpdateByUri` conserva el id y actualiza en el lugar. Borrar canciones limpia sus cross-refs (`MusicDao.deletePlaylistRefsForSongs`).
- `Music/BestiaPop` es app-managed: `scanMediaStore` **no** reinserta esos archivos (evita duplicar `file:`/path vs `content://`). Tras reinstall, `resyncAppManagedMusic()` reindexa esos archivos por path absoluto.
- Import disco (MediaStore + BestiaPop) solo en **primer arranque** / post-uninstall (`LibraryPreferencesRepository.initial_library_scan_completed`); updates no re-escanean (Room migraciones sí).
- **Fecha de adición real del dispositivo:** `scanMediaStore` lee `DATE_ADDED` / `DATE_MODIFIED` de MediaStore (o fecha del archivo); el escaneo de carpetas SAF (`scanFolderUri`) y de la carpeta app-managed (`indexAudioFiles`) toma `file.lastModified()`, asignando a `Song.dateAdded` la fecha real en el dispositivo en lugar de la hora de escaneo. Migración one-shot `migrateDateAddedFromDevice()` (`device_date_added_migrated`) actualiza en segundo plano las canciones ya indexadas previamente.
- **Pases de fondo con memoria de intentos:** el enriquecido de portadas/letras y las fotos de artista colectan `rawSongs` (no `libraryProjection.songs`, que re-emite con cada tecla del buscador y cada cambio de orden) y marcan lo ya intentado (`metadataEnhanceAttempted` / `artistPhotoAttempted`); si no, un artista sin foto o una canción sin portada online se re-consultaban para siempre. `migrateLegacyYouTubeMusicSongs` es one-shot (`legacy_ytm_album_migrated`).
- Audio local: un solo API `MusicFileStore` + `AudioPersistRef.canonicalize` (escribir/abrir/borrar/playableUri). BestiaPop se persiste como **path absoluto** en `Music/BestiaPop`; música ajena de MediaStore queda `content://media`. Callers no ramifican por scheme. Arranque: `migrateCanonicalAudioUris` (SAF/cache → abs; colisión remapea playlists). WiFi `/existing-files` = union Room basename + `listManagedNames`.
- Escritura BestiaPop: `MusicFileStore.prepareWrite` → File si el dir es writable; si no (UID viejo), MediaStore al mismo relative path (`StorageUtils`). Debug y release (mismo `applicationId`) usan esa carpeta.
- Import SAF (`scanFolderUri`) enumera el árbol una sola vez (conserva total/progreso exactos), guarda `AudioPersistRef` (abs si el document id mapea a filesystem) y comparte un solo `MediaMetadataRetriever` por archivo para tags+artwork. Reproducción: `playableUri` (SAF viejo → `file://`). Fallo local → toast «No se pudo reproducir».
- Playlists/overrides viven en Room (app-private): **no** sobreviven uninstall (solo los archivos de audio en `Music/BestiaPop`).
- Descarga con conflicto → `DuplicateSongException` / `DownloadConflict` → diálogo Sobrescribir | Crear nueva | Cancelar (`DownloadConflictPolicy`).
- One-shot migrator histórico: branch `archive/library-dedup-v1-migrator` (no compila en LB).
- Tags Unknown / rip numérico: `AudioFileMetadata.applyFilenameHints` + `parseFilenameMetadataHints` / `resolveWeakIdentityHints`; al `proposeSongIdentity` persiste limpieza soft (`01`→Unknown Artist, `- Title`→Title, trackNumber) antes de buscar.
- Identificar online (manual, multi-select, ⋮ o WiFi): Fase 1 lookup + score (`IdentifyRanking`); tags actuales de la canción son **fuente predominante** (`sourceArtist`/`sourceTitle`/`sourceAlbum`); auto-aplica solo **HIGH**; conflicto grave (artista/álbum/título distinto, versión, YouTube) → nunca HIGH. Si LB `enabled` + token y confianza ≠ HIGH (sin `customQuery`/filtros), enriquece con `lookupRecordingMetadata` → `fetchRecordingMetadata` → `toListenBrainzCatalogTrack` y re-rank (`IdentifyProposal.usedListenBrainz`). MEDIUM/LOW/NONE van a cola persistida `IdentifyReviewStore` (DataStore; cold start hidrata, **no** abre overlay). UI `IdentifyReviewScreen`: overview por álbum sugerido (`clusterIdentifyAlbumGroups`, solo MEDIUM + álbum no genérico, size ≥ 2) o cola canción a canción (Usar / Omitir / Buscar otro + filtros artista·álbum·año / Mostrar más candidatos / Aplicar automático a restantes = solo MEDIUM con suggested / Omitir todas / Aplicar grupo). Cerrar oculta overlay y **conserva** la cola (`pendingCount`); banner biblioteca + botón WiFi retoman. Re-identificar lote/WiFi **omite** songIds ya pendientes (cero red); ⋮ sobre pending abre ese ítem. Progreso en `libraryJobProgress` + toast resumen. Telemetría lote (sin PII): `CrashReporter.setKey`/`log` keys `identify_high`/`identify_medium`/`identify_low`/`identify_none`/`identify_skipped`/`identify_lb_hits`.
- «Título distinto» se mide contra el tag propio de la canción (`sourceTitle` vs candidato), no contra `titleSim` (que compara la *consulta*): si no, un query armado desde el nombre de archivo dejaba pasar a HIGH un candidato que contradecía el título real.
- `applySongIdentity` **conserva la duración local** cuando es > 0: `mergePreferring` prioriza el receptor (el candidato del catálogo), así que una remasterización o el default de 180000ms de iTunes reescribían el largo real.
- Álbum genérico (`IdentifyRanking.isGenericAlbum`: `YouTube`, `YouTube Music`, `Unknown Album`, `Single`, `Álbum`/`Album`) **sí** entra a identify; no se omite como ya identificado. Provider YouTube y versiones extra (live / letra / remix / cover…) nunca son HIGH → review. `cleanIdentityTitle` no persiste `+ letra` / `(Original Mix)`.
- Descarga online: álbum genérico dispara `fetchFullTrackMetadata` → `TrackIdentity?`; no se guarda `"YouTube"` — fallback `"$artist - Single"`.
- Import/resync/identify reportan progreso vía `LibraryScanProgress` / `LibraryJobProgress` (banner en biblioteca).

| Acción | API |
|--------|-----|
| Scan MediaStore | `scanMediaStore(onProgress?)` (skip BestiaPop + path/matchKey conocidos) |
| Reindex app music | `resyncAppManagedMusic(onProgress?)` → `Music/BestiaPop` filesystem walk; VM `ensureInitialLibraryImport` (1ª vez) |
| Scan carpeta SAF | `scanFolderUri(treeUri, onProgress?): Int` (incluye BestiaPop; guarda abs path si se puede resolver; toast en `importFolder`) |
| Metadata archivo → Room | `AudioFileMetadata.fromPath` / `toSong` (`identity` + genre; filename hints si Unknown; `parseCdTrackNumber` de tags CD_TRACK/DISC) |
| Upload WiFi → DB | `saveUploadedSong` (`AudioPersistRef`; merge por matchKey); VM observa `DONE` → `identifySongs(force=true, showReview=false)` |
| Canonicalizar URIs | `MusicRepository.migrateCanonicalAudioUris` (startup, junto a `migrateLegacyYouTubeMusicSongs`) |
| Lookup duplicado | `findSongByArtistTitle` |
| Descarga + política | `downloadAndSaveOnlineTrack(..., conflictPolicy)` |
| Conflicto UI | `downloadConflict` / `resolveDownloadConflictOverwrite` / `resolveDownloadConflictSaveAs` / `cancelDownloadConflict` + `DownloadConflictDialog` |
| Borrar app / dispositivo | `deleteSongsFromApp` / `deleteSongsFromDevice` |
| Enriquecer meta/letras | `enhanceSongMetadataAndLyrics` (portada/letras/duración; **no** artist/álbum) |
| Proponer identidad | `proposeSongIdentity(song, customQuery?, force?, listenBrainzToken?, filters?, catalogIndex?, existingCandidates?)` → `IdentifyProposal` (`usedListenBrainz`, `nextCatalogIndex`, `catalogMayHaveMore`) |
| Aplicar candidato | `applySongIdentity` → `candidate.identity.mergePreferring(entity)` (+ clean title / fallback album) → `Song.withIdentity` → `IdentifyResult` |
| Identificar lote | VM `identifySongs(songs, force, showReview)` → auto HIGH + cola review; skip `pendingSongIds` |
| Identificar una | VM `identifySongForReview` (menú ⋮; si pending → abre ítem, si no `force=true`); UI `IdentifyReviewScreen` |
| Review preview | Local `previewIdentifyLocalSong`; candidato stream `previewIdentifyCandidate` → `candidate.track` → `playOnlineCatalogTrackAsStream` (`PreviewPlayPauseButton`) |
| Review acciones | `applySelectedIdentifyCandidate` / `skipIdentifyReviewItem` / `searchIdentifyCandidates` / `loadMoreIdentifyCandidates` / `dismissIdentifyReview` (oculta) / `showIdentifyReview` / `applyRemainingIdentifySuggestions` (MEDIUM) / `skipAllIdentifyReview` / `applyIdentifyAlbumGroup` / `startIdentifyItemReview` / `returnIdentifyReviewOverview`; “Buscar otro” + filtros artista/álbum/año (`IdentifySearchFilters` / `IdentifyCatalogQuery`); “Mostrar más” revela pool local o pagina catálogo (`appendCandidates`, sin reordenar visibles) |
| Review persist | `IdentifyReviewStore` + `IdentifyReviewCodec` (sin `audioUrl` CDN); hydrate `identifyReviewFromPersisted` (huérfanos fuera); prune al borrar canciones |
| Ranking | `IdentifyRanking.score` / `rank` / `appendCandidates` / `confidence` / `hasSevereConflict` / `isGenericAlbum` / `isPlaceholderArtist` (única fuente de artista débil; usada por filename hints + `AudioFileMetadata`) / `isPreferredProvider` (Deezer/iTunes/Catalog/ListenBrainz) / `cleanIdentityTitle`; `Query.sourceArtist`/`sourceTitle`/`sourceAlbum`/`preferYear`; query refine `IdentifyCatalogQuery`; grupos `clusterIdentifyAlbumGroups` |
| Progreso biblioteca | `libraryJobProgress` (`LibraryJobKind.IMPORT` \| `IDENTIFY` \| `TAG_WRITE`) + `LibraryProgressBanner`; pending `IdentifyPendingBanner` |

## 6b. Escribir tags a archivos locales

**Invariante:** Room es la fuente de verdad en app. Escribir al disco es **opt-in** (off por defecto). Solo paths absolutos writable (típicamente `Music/BestiaPop`); `content://media` se omite. Formatos: mp3 / m4a / flac / ogg.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `LibraryTagWritePreferencesRepository` / `LibraryTagWriteSettings` (`autoWriteTagsEnabled`) |
| Settings UI | `LibraryTagWriteSettingsScreen` vía `SettingsScreen` sección Archivos |
| Writer | `AudioTagWriter.write` + `MusicFileStore.writableFile` |
| Auto al guardar | `MusicRepository.maybeWriteTags` tras `applySongIdentity` / `updateSongMetadata` / album propagate+merge / download / enhance (si cambia artwork) |
| Batch | VM `syncLibraryTagsToFiles` → `syncTagsToFiles` + toast resumen; progreso `LibraryJobKind.TAG_WRITE` |

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
| UI / persistir boost | `MusicPlayerViewModel.setVolume` / `setVolumeBoostEnabled` / `restoreVolumeBoostIfNeeded`; restore espera `PlaybackRuntime.awaitPlaybackSettings` (primera emisión real, no defaults) |
| UI / persistir balance | `setStereoLeftGain` / `setStereoRightGain` / `resetStereoBalance` |
| UI slider general | `NowPlayingScreen` (`volumeBoostEnabled`, `valueRange` 0…2) |

## 7c. Aleatorio y repetición entre sesiones

**Invariantes:**
- Último `_isShuffle` + `RepeatMode` se persisten siempre (`lastShuffleEnabled` / `lastRepeatMode`).
- **Orden pre-shuffle = `preShuffleOrder` (lista efímera de `queueEntryId`), no un snapshot de items ni `mediaId`.** `PlaybackQueueSlots.restorePreShuffleOrder` reconcilia contra la cola viva: removidos siguen fuera, copias resueltas conservan datos y lo agregado mientras estaba mezclado queda al final. Duplicados Local/Remote son ocurrencias distintas.
- **Apagar aleatorio restaura el orden** vía `disableShuffleRestoringOrder` (L2); `setShuffleEnabled` (L1) solo cambia el flag. `applyResolvedModes` usa el L2, así que clear-on-skip / clear-on-play ya no dejan la cola mezclada para siempre.
- **Persistir con shuffle recorta por posición de reproducción**, no por el índice original: `queueSnapshotForPersist` delega en `PlaybackQueueSlots.projectSnapshot`, que proyecta sobrevivientes y `shufflePlayOrder` por ocurrencia; `queueEntryId` y CDN nunca se serializan.
- **Restaurar shuffle es coherente con el toggle:** `applyHydratedQueue(hydrated, restoreShuffle)` recibe el mismo valor que calcula `PlaybackModeRestore.resolve`; si no se restaura shuffle, la cola vuelve en orden original.
- Shuffle: `PlaybackRuntime` reescribe su cola + timeline; toggle en NP usa `rebuildPlayerQueueAroundCurrent` (cirugía remove/add alrededor de la ocurrencia actual, sin `setMediaItems`/`prepare`) y fallback `reloadPlayerTimeline`. En hidratación display-only solo reordena memoria. Media3 conserva `shuffleModeEnabled` para interoperar con controllers externos, pero `MediaControllerFacade` instala por custom command un orden identidad: el recorrido real sigue siendo la cola física. `mutateMaterializedTimeline` reaplica ese orden después de append, Reproducir siguiente, refill, remove o move porque Media3 regenera `ShuffleOrder` al mutar la playlist. Callbacks `onShuffleModeEnabledChanged` / `onRepeatModeChanged` / `onTimelineChanged` reconcilian cambios de auriculares, Android Auto u otro controller. `displayQueue` = cola física; drag = `moveQueueItem`.
- Arranque en frío (sin timeline): restaurar según `rememberShuffleOnLaunch` / `rememberRepeatOnLaunch` (on por defecto). Off → ese modo arranca apagado.
- **Autoplay al abrir** (`autoplayOnLaunch`, off por defecto): mismo flag para Local y Remote. Restore espera `playbackSettingsReady` (primera emisión real de DataStore; nunca decide con el default de `stateIn`). Off → hidratación solo visual: no carga timeline, `prepare`, resolve ni prefetch hasta Play. On → `maybeSeedIdlePlayer` llama `togglePlayPause`. Sesión FGS viva que ya suena no se toca.
- Sesión viva: `PlaybackRuntime` sincroniza repeat desde el controller que posee; shuffle viene de prefs (única fuente). Switches de Ajustes no cambian la sesión actual.
- Play/tap manual (`playCollection` / `playSong`) aplica `PlaybackModeClear.afterManualPlay`. Default: apaga shuffle + Repeat One; Repeat All se mantiene. Tap en cola (`skipToQueueIndex`) **no** apaga shuffle. Next/prev: `afterSkip`. Radio aplica `afterRadioStart` **solo tras obtener sugerencias** en ambos caminos: restaura/apaga shuffle y apaga Repeat One; Repeat All queda. `shuffleCollection` enciende shuffle y aplica clears de repeat de manual play.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `PlaybackSettings` + `PlaybackModeRestore.resolve` / `PlaybackModeClear.afterManualPlay` / `afterSkip` / `afterRadioStart` / `parseRepeatModeName`; writes 1-key `DataStore.put` |
| Settings UI | `PlaybackSettingsScreen` vía `SettingsScreen` sección `Playback`; slider online; Batería separa `blocksBackgroundPlayback` vs Doze; copy vía `backgroundRestrictionGuidance`; aviso OEM solo si `oemScreenOffCleanupActive`; botón `openOemScreenOffCleanupSettings` si el intent resuelve |
| Restore | `PlaybackRuntime.restorePlaybackModes` tras conectar/sincronizar el controller |
| Persist | `PlaybackRuntime.setShuffleEnabled` / `setRepeatMode` / `toggleShuffle` (`applyQueueReorder` / `rebuildPlayerQueueAroundCurrent`) / `toggleRepeatMode` / `finishPlayPlayableCollection` (`startShuffled`) / `persistPlaybackSession` → `queueSnapshotForPersist` / `QueueSnapshot.shufflePlayOrder` |
| Remember flags | `setRememberShuffleOnLaunch` / `setRememberRepeatOnLaunch` / `setAutoplayOnLaunch` |
| Clear-on-play | `setClearShuffleOnManualPlay` / `setClearRepeatAllOnManualPlay` / `setClearRepeatOneOnManualPlay` / `applyManualPlayModes` |
| Clear-on-skip | `setClearShuffleOnSkip` / `setClearRepeatOneOnSkip` / `applySkipModes` / `skipToNext` / `skipToPrevious` |

## 8. WiFi Sync

`WebServerService` (Ktor) + `WebServerScreen(viewModel)`.

| Capacidad | Entry point |
|-----------|-------------|
| Servidor local | `WebServerService` + toggle en `WebServerScreen` |
| Omitir existentes | `GET /existing-files` = Room basename ∪ `MusicFileStore.listManagedNames`, **saneados con `UploadNameSanitizer.sanitize`** (`data/util/UploadNameSanitizer.kt`) igual que el dashboard y que el nombre con el que se guarda (si no, acentos y espacios nunca matcheaban y el archivo se volvía a subir) |
| Guard de subida | `WifiSyncHttpBoundary.isAllowedHost` (Host+puerto) + `WIFI_SYNC_MAX_UPLOAD_BYTES` (Content-Length y stream); al fallar persistencia responde 500 y borra el parcial/publicado, pero una desconexión posterior al commit no borra archivo ni fila Room |
| Transferencias en app | `WebServerService.transfers` (`WifiTransferItem` / `WifiTransferState`); lista en `WebServerScreen` (progreso + `SongListItem` al completar) |
| Identify al importar | Tags embebidos (ID3) → Room → mismo `identifySongs(force=true, showReview=false)`; conflictos en cola persistida compartida (omite ya pending) |
| Revisar conflictos | Botón `Revisar conflictos de información (N)` → `showIdentifyReview()`; N = `identifyReview.pendingCount` (sobrevive process death) |
| Dismiss | `WebServerService.dismissTransfer` |

Centro de descargas online → sección 2 (`DownloadsScreen`, tab Descargas).

## 8b. Centro de descargas (tab Descargas)

| Capacidad | Entry point |
|-----------|-------------|
| UI cola | `DownloadsScreen` + `DownloadsHeader` + `ActiveDownloadRow`. **Toda fila cancelable:** `DownloadStateTrailing` muestra cancelar en QUEUED/DOWNLOADING y retry+dismiss en IDLE. Header: `resumeAllDownloads` aparece como «Reanudar todo» si hay ERROR; omite SUCCESS, activos e IDLE; `dismissAllActiveDownloads` = «Limpiar todo». Wired también en detalle playlist y Now Playing |
| Prefs descarga | `DownloadPreferencesRepository` / `DownloadSettings` (`download_settings`; `downloadOnMeteredNetwork` default **true**; `totalMeteredBytes` / `totalUnmeteredBytes` vía `addDownloadedBytes`) |
| Settings UI | `DownloadSettingsScreen` vía `SettingsScreen` sección Descargas (path `Música/BestiaPop` + switch datos + totales bytes); deep-link `openDownloadSettings` / `pendingSettingsSection` / `consumePendingSettingsSection` |
| Gate red | `ConnectivityObserver.isMetered` + `ensureDownloadNetworkAllowed` **dentro** de `withPermit` en `runTrackedDownload` → ERROR `DownloadMessages.blockedOnMetered`. Antes del semáforo no sirve: un batch que pasó el chequeo en WiFi esperaba minutos en cola y bajaba por celular. Al éxito `addDownloadedBytes` con `isMetered()` muestreado en completion (no al start) |
| Anti-duplicado | `TrackMatchKeys.downloadIdVariantsFor` alimenta `ProcessDownloadCoordinator.execute`; `MusicPlayerViewModel` usa la façade `ProcessDownloadRuntime.isRunning` / `findClaimedDownload` / `attachPlaylistDestination`, sin mutar el registry directo. UI usa `findUiDownloadByTrack` con semántica visual separada |
| Cancelar | `ProcessDownloadCoordinator` mapea cada alias al `Job` dueño; `dismiss` / `dismissAll` cancelan ese job y quitan su fila; `cancelAndJoin` espera al writer anterior antes de retry. Updates terminales son `update` (no resucitan una fila descartada). `DownloadAudioTrackUseCase` re-lanza `CancellationException` |
| Totales UI | Labels “Con límite de datos” / “Sin límite” en `DownloadsScreen` + `DownloadSettingsScreen` (`formatByteCount`) |
| Persistencia cola | `ProcessDownloadCoordinator` único writer `ActiveDownloadsStore` + `ActiveDownloadCodec` (request, targets, `batchId`, policy, stages, marker `interrupted`; compat mensajes legacy); `flushDurably` antes del lease |
| Lifetime/notif | `OnlineDownloadServiceLauncher.acquire` entrega lease ref-counted: UIDT `OnlineDownloadJobService` API 34+; FGS `OnlineDownloadForegroundService` API 26–33; job regular `OnlineAutomaticDownloadJobService` para autosave. Explícitas usan `DownloadNotificationHelper` (tap → Descargas) |
| Badge tab | `activeDownloadBadgeCount` en `MainScreen` NavigationBar Descargas |
| Deep-link | `requestOpenDownloads` / `pendingOpenDownloads` / `consumeOpenDownloads`; tab UI path + CTA ajustes en `DownloadsScreen` |
| Límite | `ProcessDownloadCoordinator.MAX_CONCURRENT_DOWNLOADS = 3`, global para todos los callers |

## 9. ListenBrainz (scrobbling + Para Ti)

**Invariantes:**
- Scrobbling solo si `ListenBrainzSettings.enabled` + token válido; offline encola en `pending_listens`. Runtime espera la primera emisión real de `ListenBrainzPreferencesRepository.settingsFlow` antes de decidir autosave o pedir Radio; nunca usa el objeto default del cold start.
- Sección **Para Ti** / **Recomendados** en Playlists solo si `showDiscoverPlaylists` (`enabled && discoverEnabled && username`).
- Playlists Discover = `GET /1/user/{user}/playlists/createdfor`; detalle = `GET /1/playlist/{mbid}`.
- CF Recomendados = `GET /1/cf/recommendation/user/{user}/recording` + metadata → match Local|Remote.
- Match local por artist+title normalizado (`TrackMatchKeys.normalize` pliega case/puntuación/tildes; `matchMetasAgainstLibrary` / `matchAgainstLibrary`; L1 `buildLibraryIndex` + `lookupLocalSong` para radio); faltantes = `PlayableItem.Remote`. Rematch LB/CF tras descarga = `List.rematchLocals`. Query YT / id catálogo = `TrackMeta.youtubeSearchQuery` / `TrackIdentity.toCatalogTrack`.
- Reproducción: cola mixta `Local|Remote` vía `playPlayableCollection` (prefetch / 403 retry de stream).
- **Descarga manual** de un Remote en detalle Para Ti / Recomendados o Now Playing: icono Descargar en `RemoteTrackPlaceholderRow` / CTA `NowPlayingRemoteDownloadAction` → `downloadRemoteItem` (`ActiveDownloadSource.DISCOVER`); progreso en Descargas (+ estados en NP); al éxito rematch LB + CF.
- **Guardar al escuchar** (`saveWhileListening` + `saveWhileListeningPercent`): `SaveWhileListeningPolicy` evalúa progreso; `STATE_ENDED` y transición automática guardan el Remote saliente aunque faltara el último sample; un salto manual nunca finge completado. `PlaybackRuntime` dispara `ProcessSaveWhileListeningCoordinator` sin reemplazar MediaItem; ambos sobreviven `MusicPlayerViewModel.onCleared`. Autosave comparte claim/permiso/cola con manual, devuelve éxito si ya existe y `SaveWhileListeningDownloadResult.InFlight` si otro owner ya lo descarga (neutral: sin `ERROR`, evento de fallo ni cooldown); contabiliza bytes metered/unmetered al guardar y un SUCCESS retenido vuelve a disparar rematch LB/CF cuando retorna la UI. Un fallo real → `ERROR` + evento UI; cooldown 10 min process-scoped.
- `ListenTracker.onTrackChanged(..., PlaybackChangeHint)` vive en el scope del runtime: mutaciones de metadata/cola conservan progreso y una transición Media3 real usa `NEW_PLAYBACK`, incluso Repeat One o duplicados; detach de UI no cancela ticks ni sync.
- **Import a Room:** “Guardar” crea playlist local con matched + metadata pendiente de faltantes (`playlist_pending_tracks`); “Descargar faltantes” / detalle local encola vía `runTrackedDownload` (`LB_IMPORT` + `targetPlaylistId`). Progreso en tab Descargas; nunca CDN en Room.

| Capacidad | Entry point |
|-----------|-------------|
| Prefs | `ListenBrainzPreferencesRepository` / `ListenBrainzSettings` (`saveWhileListening`, `saveWhileListeningPercent`) |
| Settings UI | `ListenBrainzSettingsScreen` — registrar + **Mostrar Para Ti** + **Guardar al escuchar** (+ slider %) |
| Política de cambio de track | `PlaybackTrackChangePolicy.resolve` + `PlaybackChangeHint` / `PlaybackTrackChange` en `data/playback/PlaybackTrackChangePolicy.kt` |
| Política Guardar al escuchar | `SaveWhileListeningPolicy.shouldSave` + `SaveWhileListeningEvent` en `data/listenbrainz/SaveWhileListeningPolicy.kt` |
| Submit listens | `PlaybackRuntime` → `ListenTracker.onTrackChanged` / `ListenSyncCoordinator` → `ListenBrainzClient.submitListens` |
| List Discover | `ListenBrainzClient.fetchCreatedForPlaylists` → `MusicPlayerViewModel.refreshListenBrainzDiscoverPlaylists` |
| Abrir playlist | `openListenBrainzPlaylist` + `MatchListenBrainzTracksUseCase` |
| Map a cola | `MatchedLbPlaylist.toPlayableItems` / `MatchedRemoteTrack.toPlayableItem` |
| Play / shuffle / índice | `playMatchedTracks` / `shuffleMatchedTracks` → `playMatchedCollection` / `shufflePlayableCollection` con `DiscoverPlaybackOrigin` (`MatchedLbPlaylist.toDiscoverOrigin`) |
| Import locales + pendientes | `saveListenBrainzPlaylistAsLocal` → `ImportListenBrainzPlaylistUseCase.createLocalFromMatched` (`PlaylistPendingTrack(identity = track.identity)`; unmatched → `OnlineCatalogTrack(identity, provider = ListenBrainz)`) |
| Import + descarga ya | `importListenBrainzPlaylistWithDownloads` / `downloadPlaylistPendingTracks` → `runTrackedDownload` (`LB_IMPORT`) |
| Descarga manual Remote | `downloadRemoteItem` → `runTrackedDownload` (`DISCOVER`); UI `RemoteTrackPlaceholderRow.onDownload` en detalle LB/CF; NP `NowPlayingRemoteDownloadAction` |
| CF Recomendados | `ListenBrainzClient.fetchCfRecordingRecommendations` → `FetchAndMatchCfRecommendationsUseCase` → `refreshCfRecommendations` / `openCfRecommendations` / `playMatchedTracks` / `shuffleMatchedTracks` |
| UI sección | `PlaylistsScreen` — "Para Ti" + "Recomendados"; Guardar / Descargar faltantes / descarga por track; detalle local muestra pendientes |
| Restore sesión | `MusicPlayerViewModel.navigation` → `UiNavigationState.playlistDetail` (`ListenBrainz` / `CfRecommendations`) + `UiNavigationState.selectedNavIndex`; fetch al hidratar/abrir tab Playlists; fallo (sin red, Discover off, API) → lista general + toast (`restoreDiscoverDetailOrFallback`) |

## 10. Stream remoto (playback sin descarga)

**Invariantes:**
- Cola unificada `List<PlayableItem>` (`Local` | `Remote`); APIs `Song` se adaptan con `Song.toPlayable()`.
- Re-extraer stream YouTube just-in-time (`MusicRepository.streamResolver` → `YouTubeExtractor`); playback usa `resolveForPlayback(maxCachedAgeMs = STREAM_READY_MAX_AGE_MS)` (60s) aunque la cache general dure ~4 min; download llama `resolveQuery(forceRefresh = true)`; **no** guardar `audioUrl` CDN en Room. `StreamResolver.withKeyLock` reserva/ref-cuenta el lock antes de suspender y solo poda entradas sin referencias, para no entregar dos mutex del mismo query.
- `PlaybackMediaItemCodec` v1 codifica Local/Remote con `queueEntryId`, identity, query/mbid, videoId y UA en extras; la URL CDN vive **solo** en `MediaItem.uri`. Un Remote sin resolver usa `Uri.EMPTY`; `UserAgentMediaSourceFactory` no lo sustituye por `SilenceMediaSource` (el player queda IDLE hasta resolve). ExoPlayer usa UA vía `StreamPlaybackTag` en `RequestMetadata.extras` (`setStreamPlaybackTag` / `streamPlaybackTag()`), nunca `localConfiguration.tag`.
- Googlevideo rechaza con 403 requests iniciales sin `Range` acotado. `MusicService.boundGoogleVideoRequest` envuelve el datasource con `ResolvingDataSource` y, cuando Media3 deja `DataSpec.length` desconocido, usa el `clen` firmado de la URL para fijar la longitud restante; aplica también después de un seek.
- **Pausar no depende del TTL:** la rama de pausa de `PlaybackRuntime.togglePlayPause` actualiza la intención, cancela resolve/prefetch/recovery, llama `pause()` y retorna antes de `requestPlaybackForCurrent` y de comprobar si el stream necesita resolución. Así una pausa nunca reconstruye ni reinicia el tema.
- **Una sola intención de reproducción:** `PlaybackRuntime` conserva su propia intención `playWhenReady`, `queueEntryId`/índice y posición muestreada; nunca consulta un facade después de `onDisconnected`. Antes de tener controller/target listo, `pendingPlayIntentEpoch` es un token monotónico. Reconectar sincroniza una sesión viva o rematerializa cola+slot+posición y recién después resuelve/prepara. Pausar durante resolve/buffering no se deshace al completar.
- `PlaybackRuntime.prefetchAround` resuelve índices N+1 / N+2 y la ventana se desliza aun sin UI (al pasar a N+1 alcanza N+3), pero solo con `playWhenReady=true`; una sesión viva pausada no dispara resolve/prefetch hasta Play. El audio lo bufferea ExoPlayer vía `setPreloadConfiguration` (`PRELOAD_TARGET_DURATION_US`, 10s) en `MusicService`.
- **Un seek no saca al player de `STATE_IDLE`.** Un stream que falla lo deja ahí, así que next/previous parecían muertos mientras el botón de play funcionaba: Media3 prepara al player idle solo para la acción de play (`Util.handlePlayButtonAction`), no para un seek. `ensurePreparedForPlayback()` cubre el hueco y se llama en `skipToNext` / `skipToPrevious`, en cada `onMediaItemTransition` (donde `ensureRemoteReadyAt` corta temprano si el ítem es Local o si el stream sigue fresco) y en el salteo automático de `recoverAfterUnplayable`. Solo prepara: `playWhenReady` sigue decidiendo si arranca, así que saltar en pausa sigue en pausa.
- **Ventana de gracia antes de saltear** (`PlaybackSettings.streamSkipGraceSeconds`, default 3s, Ajustes → Reproducción → Canciones online): ante *cualquier* error de un `Remote`, `handlePlayerError` re-extrae y re-prepara en loop hasta que se agote la ventana (`remoteRecoveryJob` + `remoteRecoveryDeadlineMs`, una por `queueEntryId`, compartida entre rondas de error). Re-resolver una URL no reinicia el deadline: solo progreso real/`isPlaying` o cambiar de slot limpia la ventana; al vencer usa el fallback circular y pausa si no queda candidato. 0 = saltear al primer error. Antes se miraba una lista corta de códigos IO y cualquier otro (página HTML de error, contenedor mal formado, fallo de decode) salteaba el tema en silencio.
- **Identidad de ocurrencia = `PlayableItem.queueEntryId` para Local y Remote.** Toda entrada nueva/restaurada pasa por `withFreshQueueEntryIds`; `copy(song/resolved=…)` conserva el id. `mediaId` identifica audio y puede repetirse, nunca un slot. `applyResolvedRemote` usa `indexOfRemoteSlot`; pre-shuffle/snapshot/UI usan `PlaybackQueueSlots`/`indexOfQueueEntry`. El id viaja solo en `MediaMetadata.extras` durante la sesión y no se persiste.
- **Selección/inicio/recovery:** `PlaybackFallbackPlanner.circularPlan` prueba circularmente cada slot una vez e incluye Local tanto al tocar cola como al inicio normal y tras un error; no corta arbitrariamente al quinto fallo. `rejectedQueueEntries` evita cascadas infinitas hasta que una canción reproduce progreso real. `PlaybackSelectionIntentGate` hace latest-tap-wins.
- **Completions obsoletos:** todo cambio de colección/slot incrementa `playbackGeneration` y cancela transition resolve, recovery y prefetch. Tras cada suspensión se exige generación + controller + `queueEntryId`, así un trabajo viejo no reemplaza, prepara, saltea ni pausa la reproducción nueva aunque ignore cancelación cooperativa.
- **Seek pausado persistente:** `seekTo` actualiza el snapshot con debounce (`scheduleSeekPersistence`) y el mismo writer serial de `PlaybackSessionStore`; varios seeks rápidos guardan solo la posición final y una escritura vieja no adelanta a otra nueva.
- **`resolvingRemote` es ref-counted** (`beginResolving` / `endResolving`): con un booleano plano, el `finally` de un resolve cancelado apagaba el indicador mientras otro seguía corriendo.
- Descarga explícita (“Agregar”) sigue download-then-play; stream no la reemplaza.

| Capacidad | Entry point |
|-----------|-------------|
| Modelo | `PlayableItem` (`TrackMeta`; `Remote` guarda `identity` + mbid/stream), `ResolvedStream` en `data/model/PlayableItem.kt` |
| Resolver | `MusicRepository.streamResolver` (`StreamResolver.resolveForPlayback` / `resolve` / `prefetch` en `data/stream/StreamResolver.kt`) |
| HTTP ExoPlayer | `StreamPlaybackTag` + `setStreamPlaybackTag` / `streamPlaybackTag()` (`RequestMetadata.extras`) + `MusicService` `UserAgentMediaSourceFactory` / `boundGoogleVideoRequest` (UA + `clen`→`DataSpec.length`; placeholder Remote `Uri.EMPTY` queda IDLE, no `SilenceMediaSource`) |
| Invalidar stream muerto | `StreamResolver.invalidate(item)` (suspend, bajo el mutex; borra la clave `id:` **y** la `q:` y toda entrada con ese `videoId`) |
| FGS background | Una sola notif Media3 (`RefreshingMediaNotificationProvider` → `DefaultMediaNotificationProvider`, canal `playback_channel`, id 1001): portada + Previous · Play/Pause · Next. Artwork async vuelve por `triggerNotificationUpdate`, así no despromueve Remote IDLE. `MusicService.onUpdateNotificationAsync` fuerza foreground cuando `PlaybackServiceLifetimePolicy.isPlaybackEngaged`; Media3 mantiene 10 min de gracia tras pausa/error. `onTaskRemoved` conserva política propia. OEM puede poner `RUN_ANY_IN_BACKGROUND` en IGNORE y demotar FGS 125→300 sin `onForegroundServiceStartNotAllowedException`; `maybeNotifyBackgroundRestriction` publica notif 1002 solo si AppOps IGNORE/ERRORED se confirma (`BACKGROUND_RESTRICTION_CONFIRM_MS`, o al toque si `startForegroundDenied`) y la cancela al levantar la restricción. Sticky restart reanuda vía `shouldResumeAfterStickyRestart` → `PlaybackRuntime.requestResumeAfterServiceRestart` (no persiste engaged en `onStartCommand`). Wake: `WAKE_MODE_NONE` en local y `WAKE_MODE_NETWORK` en stream. Copy: `backgroundRestrictionGuidance`. |
| Ownership cola/play | `PlaybackRuntime` (único `MediaController`, listener y jobs; `attachUi` / `detachUi` / `events`); `MusicPlayerViewModel` delega comandos, expone StateFlows y consume eventos |
| Codec Media3 | `PlaybackMediaItemCodec` (`encode` / `decode` / payload v1 sin CDN) |
| Stream desde catálogo | `playOnlineCatalogTrackAsStream` + preview in-dialog (`CatalogTrackItem` / `CandidateTrackCard` + `CatalogPreviewBar`); `cycleSongCatalogResult` / `cycleTrackCandidate` (“Buscar otro”) |
| UI player | `BottomPlayerBar` / `NowPlayingScreen` / `QueueScreen` observan `PlayableItem` |

## 10b. Continuidad del mini player

**Invariantes:**
- `MusicPlayerViewModel.init` llama `PlaybackRuntime.attachUi` y colecta `events`; `onCleared` llama `detachUi`. El ViewModel no guarda `MediaController`, cola ni estado de resolución.
- `PlaybackRuntime` conserva controller/cola durante recreate si hay UI, intención `playWhenReady` o cola pendiente; tras reconnect `syncFromController` prioriza una sesión viva. Si la sesión volvió vacía, rematerializa su cola con el `queueEntryId`/índice y posición muestreada propios. `onDisconnected` primero desasocia el owner y jamás lee propiedades del controller ya inválido; reconecta con backoff.
- Sin sesión viva: `PlaybackHydration.hydrateQueue` rematchea Local por id/uri y conserva Remotes identity+mbid+query/`videoId` sin CDN. Una cola solo-Remote se hidrata aun con biblioteca vacía; si current local se borró, avanza y pone posición 0.
- Con autoplay off, `applyHydratedQueue` solo publica mini player/cola/posición: `addPlayableBatch` / `playNextBatch` / `removeFromQueue` / `moveQueueItem` / `toggleShuffle` mutan la cola en memoria sin tocar Media3. El primer Play materializa el snapshot completo e índice/posición antes de resolve/prepare. Con autoplay on usa el mismo camino automáticamente.
- Persistencia: `persistPlaybackSession` captura un `PlaybackPersistenceRequest` inmutable en el scope Main y un writer único lo guarda en IO en orden; una escritura vieja nunca puede pisar una nueva.
- Lifecycle: el ticker de 200 ms existe solo mientras `isPlaying`; al quedar sin UI, playback ni cola pendiente, el runtime cancela future/retry/ticker y libera el controller. Playback/cola sobreviven a `onTaskRemoved`.
- Biblioteca vacía **sin snapshot Remote usable** → `BottomPlayerBar` oculto (`currentItem == null`).
- Con playback activo (`playWhenReady`, items en timeline, no `ENDED`), `MusicService` permanece FGS aunque se destruya Activity/ViewModel o se remueva la task. `PlaybackServiceLifetimePolicy` conserva el caso `STATE_IDLE` temporal de placeholder Remote; `onTaskRemoved` no hace `stopSelf`. Wake: `WAKE_MODE_NONE` (local) / `WAKE_MODE_NETWORK` (stream). Tras un kill OEM, sticky restart reanuda si `playback_lifetime.engaged` quedó verdadero (`commit()` en listeners; `onStartCommand` no pisa el flag).
- System resumption: `MediaButtonReceiver` → `BestiaPopMediaLibraryCallback.onPlaybackResumption`; metadata-only usa `systemResumptionMetadataSnapshot` sin mutar runtime/red y Play usa `restoreSystemPlaybackSnapshot` + codec. `autoplayOnLaunch` sigue siendo solo para launch de UI.
- No persistir CDN de `Remote`. Mini bar: Previous + status (`Resolviendo…` / `Armando radio…` / `radioStatusLabel`).

| Capacidad | Entry point |
|-----------|-------------|
| Lease UI + mensajes | `PlaybackRuntime.attachUi` / `detachUi` / `events`; wiring en `MusicPlayerViewModel.init` / `onCleared` |
| Resync sesión | `PlaybackRuntime.syncFromController` + `PlaybackMediaItemCodec.decode` |
| Last-played + cola | `PlaybackSessionStore`, `LastPlayedCodec`, `QueueSnapshotCodec`, `PlaybackHydration.hydrateQueue` en `data/preferences/PlaybackSessionStore.kt` |
| Wrap / trim | `PlaybackQueueOrder.rotateToStart` / `trimHistory` (+ remap `shufflePlayOrder`) en `data/playback/PlaybackQueueOrder.kt` |
| Seed idle | `PlaybackRuntime.maybeSeedIdlePlayer` / `applyHydratedQueue` |
| Mini bar UI | `BottomPlayerBar` (`statusLabel`, Previous); wiring en `MainScreen` |

## 10c. Android Auto / Bluetooth browse

**Invariante:** el árbol externo expone solo catálogo local reproducible offline: Canciones, Álbumes, Artistas y Playlists. Discover, Radio y pendientes remotos quedan fuera; Now Playing remoto sigue visible por la sesión activa.

**Invariante controles externos:** controllers no confiables (launchers/widgets) reciben solo transporte — Play/Pause, Prepare, Anterior y Siguiente— además de lectura; no reciben mutación de cola/biblioteca, repeat ni shuffle. `BestiaPopMediaLibraryCallback.onConnectAsync` aplica `untrustedTransportPlayerCommands`; los controllers confiables conservan el set completo.

Entry points: `MediaLibraryBrowseProvider` + `MediaLibraryIds` / `MediaLibraryBrowseMapper` y `BestiaPopMediaLibraryCallback` (`service/library`); callbacks connect/root/children/item/search/set-items; snapshot cacheado se invalida al emitir canciones/overrides/playlists y se reconstruye lazy en `Dispatchers.Default` al siguiente request (no relee Room por request); agregados en `BrowseLocalLibraryUseCase`; playlist ordenada vía `MusicDao.getPlaylistSongsOrdered`. Selección externa se stagea en `PlaybackRuntime.stageExternalPlayableCollection`, sin MediaController reentrante ni pipeline paralelo.

## 10d. Acciones de canción/álbum en Now Playing

**Invariante:** ⋮ junto al título (radio sigue en el header). Nav a biblioteca/playlist **cierra** NP + limpia search. Editar / añadir a playlist / identificar / radio no cierran NP.

| Acción | Cuándo | Entry point |
|--------|--------|-------------|
| Ir al álbum / artista | Match en `libraryProjection.albums` / `libraryProjection.artists` | `openLibraryAlbum` / `openLibraryArtist` + `setSelectedNavIndex(0)` |
| Ir a playlist local | Membresía Room (`getPlaylistIdsForSong`) | `openLocalPlaylist` + tab Playlists |
| Ir a Para Ti / Recomendados | `DiscoverPlaybackOrigin` process-scoped (sesión; no persistido) si play/shuffle desde LB/CF | `PlaybackRuntime.discoverPlaybackOrigin` → `openListenBrainzPlaylistDetail` / `openCfRecommendationsDetail` |
| Añadir a playlist / Identificar / Editar canción / Editar letra | Solo `PlayableItem.Local` | `SongActionDialogsHost` / `identifySongForReview` / `EditLyricsDialog` (Texto + Sincronizar + `PlaybackScrubber`) |
| Editar álbum | Local + álbum en biblioteca | `AlbumEditDialogsHost`; merge único en `MainScreen` (`pendingAlbumMerge`) |
| Descargar ahora | Solo `PlayableItem.Remote` (visible bajo título) | `NowPlayingRemoteDownloadAction` → `downloadRemoteItem`; estados vía `activeDownloads` |
| Iniciar radio | Siempre | `startRadio()` (mismo que icono header) |

Origen Discover: `PlaybackRuntime.playPlayableCollection(..., origin)` lo setea (wrappers CF/LB pasan `CfRecommendations` / `ListenBrainz`) y sobrevive `MusicPlayerViewModel.onCleared`/recreate; `None` al armar cola local/manual, al iniciar radio con sugerencias (reemplazo, keep-current o auto), al vaciar cola y en `applyHydratedQueue`.

## 11. Radio (similares)

**Invariantes:**
- Seed = canción elegida (`startRadio(seedSong)` o `currentItem`); entry en menú de canción (“Iniciar radio”) y `NowPlayingScreen`.
- **Modos UI:** Solo conocidos (`KNOWN`) / Solo nuevos (`NEW`) / Ambos (`BOTH`); label `radioStatusLabel` (“Radio · Solo conocidos|Solo nuevos|Ambos”).
- Long-press Radio en Now Playing: Solo conocidos / Solo nuevos / Ambos / Detener radio (`PlaybackRuntime.stopRadio` no vacía cola). Cancela `radioStartJob` y `radioRefillJob` process-scoped, limpia `radioLoading` y resetea el cooldown de refill vacío; una nueva radio confirmada también lo resetea. Tras el fetch, `startRadio` chequea `isActive` antes de mutar estado.
- **Auto:** al llegar a `STATE_ENDED` con `RepeatMode.OFF`, `startRadio(auto = true)` respeta preferred; default sin preferred = `BOTH` si hay red (Deezer usable sin token LB), si no `KNOWN`.
- **Durante reproducción:** no saltea el tema actual; `replaceUpcomingWithRadio` + toast “Se agregaron canciones de la radio a la cola”.
- Tras obtener sugerencias, `PlaybackModeClear.afterRadioStart` se aplica tanto al reemplazo de próximos como a la cola nueva: apaga shuffle restaurando orden y Repeat One; no toca Repeat All. Si no hubo sugerencias, no cambia modos.
- **KNOWN:** solo biblioteca (`LocalMetadataRadio` + boost co-playlist). **NEW:** solo `PlayableItem.Remote` vía LB → CF → Deezer (+ iTunes fill); matches de biblioteca se omiten; reintenta con backoff hasta ~45s (`suggestRadioWithRetry`); toast “Radio online no disponible” solo si tras timeout no hay Remotes. **BOTH:** intercala Remote, Local… (`RadioEngine.interleaveEquitable`); sin red sigue con conocidos (sin toast).
- Fill remoto: `SimilarTracksProvider` (Deezer); LB/CF siguen cableados en `RadioEngine` con credenciales.
- Refill con el mismo modo; **NEW** reintenta online ~20s; **no** persistir URLs CDN.
- **Multi-select → playlist:** `previewSimilarFromSelection` usa el mismo `RadioEngine` (`suggestFromSeeds` round-robin + dedupe); **no** llama `startRadio` / `replaceUpcomingWithRadio`. Preview editable → crear playlist / play / encolar (`SimilarPlaylistPreviewDialog`). `suggestFromSeeds` corre las semillas en paralelo acotado (`MAX_SEED_CONCURRENCY`) con deadline (`SEEDS_TIMEOUT_MS`): en serie, 10 semillas dejaban el spinner minutos con solo los timeouts de OkHttp como límite.

| Capacidad | Entry point |
|-----------|-------------|
| Modos | `RadioMode.KNOWN` / `NEW` / `BOTH`; `radioMode` / `radioStatusLabel` |
| Motor | `createBestiaPopRadioEngine` en `domain/radio/BestiaPopRadioEngineFactory.kt` → `RadioEngine.suggest` / `suggestFromSeeds` → `RadioSuggestResult`; `interleaveEquitable` / `roundRobinMerge` |
| Preview multi-seed | `BuildSimilarPlaylistPreviewUseCase` + VM `previewSimilarFromSelection` / `confirmSimilarPreviewAsPlaylist` / `playSimilarPreview` / `enqueueSimilarPreview`; estado `similarPlaylistPreview` |
| Contrato fill | `SimilarTracksProvider` |
| Local | `LocalMetadataRadio.suggest` (+ `coPlaylistSongIds` vía `IMusicRepository.getCoPlaylistSongIds`; unión multi-seed en use case) |
| LB | `ListenBrainzRadio.suggest` + LB client metadata/lb-radio |
| CF fill | `CfRecommendationsRadio.suggest` (`artist_type=similar`, cache TTL) |
| Deezer fill | `DeezerSimilarRadio.suggest` + `MetadataFetcher.resolveDeezerArtistId` / `fetchDeezerArtistRadio` / `fetchDeezerRelatedArtistIds` / `fetchDeezerArtistTop` / `fetchItunesArtistSongs` |
| Sesión cola | `PlaybackRuntime.startRadio` / `stopRadio` / `setRadioPreferredMode` / `replaceUpcomingWithRadio` / refill/auto; jobs process-scoped |
| UI | Now Playing (tap/long-press + ⋮ “Iniciar radio”); mini bar `statusLabel` (radio / resolving); menú canción biblioteca “Iniciar radio”; multi-select `MultiSelectActionBar` “Similares” + `SimilarPlaylistPreviewDialog` |

## 12. System back (jerarquía UI)

**Invariante:** un gesto atrás = un paso atrás. Paridad con ArrowBack / chevron / Cancel. No detiene reproducción. Sin Navigation Compose: `BackHandler` por capa.

| Prioridad | Comportamiento | Entry point |
|-----------|----------------|-------------|
| Diálogos / menús | Framework `onDismissRequest` | `Dialog` / `AlertDialog` / `DropdownMenu` (NP ⋮ + merge álbum en `MainScreen`) |
| Identify review | ITEM+overview → vuelve overview (`returnIdentifyReviewOverview`); si no, oculta overlay y conserva cola (`dismissIdentifyReview`); `skipAllIdentifyReview` vacía | `IdentifyReviewScreen` `BackHandler` |
| Add Music colección | `clearSelectedCollection` antes de cerrar | `AddMusicDialog` `BackHandler` + `onDismissRequest` |
| Now Playing | `dismissFullPlayer` | `NowPlayingScreen` `BackHandler` |
| Library nested | cancel addition → multi-select → álbum (`closeLibraryAlbum`, conserva artista\|género) → artista\|género → clear search | `LibraryScreen` `BackHandler` / `popLibraryNested` |
| Playlists nested | un detalle a la vez (local / LB / CF) → lista | `PlaylistsScreen` `BackHandler` → `closePlaylistDetail` |
| Settings nested | Temas / LB / Reproducción / Sonido / Descargas / Archivos → home | `SettingsScreen` `BackHandler` |
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
| GitHub Releases | `./release.sh` → APK `BestiaPop-{VERSION_NAME}.apk`; tag `v{VERSION_NAME}` (validado tag-safe y fijado al commit compilado si está en el remoto), `--latest`; notas con `versionCode: N` y body nunca vacío (lo lee la app); tras publicar `verify_published_release` chequea tag / versionCode / asset `BestiaPop*.apk` / ni draft ni prerelease; repo `github-release.properties` `GITHUB_REPOSITORY` → `BuildConfig.GITHUB_REPOSITORY` |
| Invitar amigos | Ajustes → Invitar amigos: `ACTION_SEND` con `https://github.com/{repo}/releases/latest` (`GitHubReleaseUrls.latestPageUrl`) |
| Update in-app | Un solo fetch `GitHubUpdateClient.fetchReleases` (`/releases`) → `AppReleaseSelection.from(releases, VERSION_CODE, VERSION_NAME)` = notas de la versión instalada (match por `versionCode`, fallback tag `v{VERSION_NAME}`) + `newer` acumuladas + `updateTarget`. Al abrir (release, máx. 1/12h) muestra `AppUpdateDialogs`. `AppUpdateViewModel` conserva factory `AndroidViewModel` y recibe internamente gateway/store/clock/debug/installer para tests. `ApkUpdateDownloader` escribe `.part`, exige HTTP exitoso + `Content-Length` exacto si existe + APK válido del mismo package, publica con move atómico y limpia parcial/final ante error o cancelación; recién entonces `FileProvider` + `REQUEST_INSTALL_PACKAGES` abren el instalador |
| Ajustes → Actualización | `AppUpdateScreen`: versión instalada + `versionCode`, link al repo (`GitHubReleaseUrls.repoUrl`), notas de la versión actual (cacheadas en `AppUpdateCheckStore` para verlas offline), botón Buscar actualización (`refreshReleases(force = true)`) y, si hay versiones nuevas, qué cambia en cada una + Actualizar (`startUpdate`) |
| Play Console AAB | `./deploy-play.sh --upload --rollout` path legacy (no distribución de producto) |

## Relacionado

- Capas y stack → `bestiapop-architecture`
- Paths exactos → `bestiapop-implementation-map`
