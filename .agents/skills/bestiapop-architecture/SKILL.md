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
| Estado UI | `MusicPlayerViewModel` (AndroidViewModel) + StateFlow; owners tipados en `ui/state` para proyecciones, cargas, catálogo y navegación; playback observado desde `PlaybackRuntime` process-scoped |
| Reproducción | Media3 1.11.0 ExoPlayer + `MediaLibraryService` (`MusicService`) |
| Persistencia | Room (`bestiapop_music_db`, v12) |
| Tags de archivo | jaudiotagger 3 (`AudioTagReader` / `AudioTagWriter`) + `MediaMetadataRetriever` fallback |
| Preferencias | DataStore (`ThemePreferencesRepository`, `ListenBrainzPreferencesRepository`, `PlaybackPreferencesRepository`, `DownloadPreferencesRepository` metered/path prefs, `LibraryPreferencesRepository` display+nav, `ActiveDownloadsStore`, `IdentifyReviewStore` cola identify, `IdentifyWorkStore` lote identify en curso, `PlaybackSessionStore` last-played + cola, `AppUpdateCheckStore` last GitHub check) |
| Red / catálogo | OkHttp + `MetadataFetcher` (iTunes/Deezer) + `YouTubeExtractor` + `ListenBrainzClient` + `MusicBrainzClient` (identify + Cover Art Archive, 1 req/s; timeout no reintenta duration y circuit-skip 5 min tras 3 fallos) + `GitHubUpdateClient` (releases) |
| Sync WiFi | Ktor CIO embebido (`WebServerService`) |
| Imágenes | Coil |
| Crash reporting | Firebase Crashlytics vía `CrashReporter` (`BestiaPopApplication`); sin Analytics / sin `AD_ID` |

minSdk 26 · targetSdk 36 · compileSdk 36 · Java/Kotlin 17 · AGP 9.3 + KSP · Room 2.8.
Release: R8 minify + resource shrinking + `ndk.debugSymbolLevel=SYMBOL_TABLE` (mapping y native symbols).
Versión: `version.properties` (`VERSION_CODE` / `VERSION_NAME`). Distro amigos: GitHub Releases (`./release.sh`, `github-release.properties`). Play AAB (`./deploy-play.sh`) queda como path legacy.

## Capas y paquetes

```
ui/          Compose screens, components, theme, ViewModel, ui.state
domain/      use cases + radio + IMusicRepository (puerto)
data/        MusicRepository, Room, network, stream, preferences, models, util
service/     MusicService (playback), WebServerService (WiFi sync)
```

Políticas puras de reproducción: `data/playback/PlaybackQueueOrder.kt`, `data/playback/PlaybackQueueSlots.kt`, `data/playback/PlaybackFallbackPlanner.kt`, `data/playback/PlaybackSelectionIntentGate.kt` y `data/playback/PlaybackTrackChangePolicy.kt`. La elegibilidad de Guardar al escuchar vive en `data/listenbrainz/SaveWhileListeningPolicy.kt`; la ejecución process-scoped queda en `service/`.

`BestiaPopApplication.onCreate` inicializa Crashlytics (colección solo en builds no-debug) y construye el grafo process-scoped: `MusicRepository`; `createBestiaPopRadioEngine()` (`domain/radio/BestiaPopRadioEngineFactory.kt`); `ProcessDownloadCoordinator.create(...)`; `ProcessDownloadRuntime.create(...)`; `ProcessIdentifyRuntime.create(...)`; `ProcessSaveWhileListeningCoordinator`; y `PlaybackRuntime.create(...)`. Non-fatals con contexto: `data/util/CrashReporter.kt`.

**Regla de dependencia:** `ui` → `domain` → (interfaces). `data` implementa `domain.repository`. `ui` puede usar `data.model` y servicios Media3; la lógica de negocio nueva va en `domain/usecase` o `domain/radio`, no en pantallas.

## Flujo de datos (happy path)

1. Room / MediaStore → `MusicRepository` (`MusicFileStore` para I/O local) → `allSongsFlow` identity-slim (sin `lyrics` ni play stamps; letra vía `getSongById`). El flow se comparte a nivel proceso (`shareIn` Eagerly + `distinctUntilChanged`) para una sola query Room, **sin** `ORDER BY` (el sort lo hace `GetLibrarySongsUseCase`). `touchSongLastPlayed` escribe `song_play_stats`, no `songs`, para no re-queryar la biblioteca.
2. `LibraryProjectionState` proyecta catálogo una vez tras `hydrateUiPreferences` (`prefsReady`): `GetLibrarySongsUseCase.projectCatalog` → `songs` / `albums` / `list`. `FLAT` ordena el pool; `ALBUM_GROUPS` agrupa sin sort global (orden visual = bloques de álbum + pista). `LibraryListModel` cachea `albumNames` / `songsById` y `LibraryAlbumSegment.songIds`; Compose pide filas con `size`/`keyAt`/`itemAt`. `catalogLoaded` pasa a true al terminar esa proyección (spinner vs vacío), no al materializar N `SongRow`. `LibraryScreen` en Canciones solo colecta `songList`+`catalogLoaded`. `artists` / `genres` / `recentSongs` son WhileSubscribed. Recientes combina `allSongsFlow` + `songPlayStatsFlow`. Cambios solo de last-played no reconstruyen el catálogo (`sameLibraryCatalog` + tabla aparte). Haystack de búsqueda solo si `query` no está en blanco; query no-vacía espera `LIBRARY_SEARCH_DEBOUNCE_MS`. `rawSongs` es `stateIn` Eagerly del `shareIn`; la proyección lee `allSongsFlow` directo (sin `[]` inicial). `pruneRestoredLibraryStack` corre después de `catalogLoaded` con `LibraryStackLookups.fromSongs`.
3. Screens Compose observan StateFlows y llaman métodos del ViewModel
4. Reproducción: `MusicPlayerViewModel` no posee controller ni lógica de resolución. `MainActivity.onStart` llama `onAppForeground` (probe de background + resume de descargas/identify en `Dispatchers.IO`) y **después del primer frame** `attachPlaybackUi()` → `PlaybackRuntime.attachUi()` (no hay `startService(MusicService)` extra: el controller arranca el servicio). Reexpone StateFlows y colecta `PlaybackRuntime.events`; los comandos delegan al runtime. `onCleared` llama `detachUi()`. El runtime posee `List<PlayableItem>` (`Local` | `Remote`; cada ocurrencia lleva `queueEntryId`) + `DiscoverPlaybackOrigin`, intención `playWhenReady`, slot/índice y posición muestreada, un solo lease de `MediaController`/listener y una generación que invalida resolve/recovery/prefetch viejos → `MusicService` (ExoPlayer). Una desconexión nunca vuelve a leer el controller inválido; la reconexión restaura desde el estado process-scoped. Sin UI, playback ni cola, cancela ticker/retry y libera el controller. Kill → `PlaybackSessionStore` (`queue_json` sin ids efímeros/CDN + last-played; origen Discover no persistido); `onPlaybackResumption` comparte hidratación mutex con el arranque y permite reanudar desde System UI/Bluetooth sin persistir CDN.
5. Stream remoto: `PlaybackRuntime` → `StreamResolver.resolveForPlayback(maxCachedAgeMs)` / `resolveQuery` → `YouTubeExtractor.extractAudioStreamDetailed` → `PlaybackMediaItemCodec` (identidad/query/mbid/videoId/UA en extras; CDN solo URI) → `MusicService.boundGoogleVideoRequest` (completa `DataSpec.length` desde `clen` para emitir `Range` cerrado) → ExoPlayer.
6. Radio: `RadioEngine` process-scoped (KNOWN / NEW / BOTH; fill LB→CF→Deezer; dedupe global `tryAddRemote`; multi-seed `suggestFromSeeds` → playlist preview sin mutar cola) → `PlaybackRuntime.startRadio` / refill de cola; refill/auto siguen sin UI adjunta.
7. Para Ti: `MatchListenBrainzTracksUseCase` → `MatchedLbPlaylist.toPlayableItems` → façade `playPlayableCollection`; CF: `FetchAndMatchCfRecommendationsUseCase` → `matchFromMetadata` → `MatchedCfRecommendations.toPlayableItems`; descarga manual `downloadRemoteItem` (`DISCOVER`); opcional `saveWhileListening` → `ProcessSaveWhileListeningCoordinator`; ambos comparten `ProcessDownloadCoordinator`; import Room vía `ImportListenBrainzPlaylistUseCase` + `LB_IMPORT`.
8. Descarga online: VM envía `ProcessDownloadRequest` a `ProcessDownloadRuntime` process-scoped; autosave entra por la façade `ProcessSaveWhileListeningCoordinator.save` al mismo runtime; todos comparten claim/cola/persistencia/semáforo global en `ProcessDownloadCoordinator` (`DownloadLane`, un solo `DownloadPlaylistDestination`, policy/batch/stage durables) → `DownloadAudioTrackUseCase` → `MusicRepository.downloadAndSaveOnlineTrack` (re-extract vía `StreamResolver.resolveQuery(forceRefresh = true)`; googlevideo `Range` por chunks vía `GoogleVideoRange.nextChunkRange`) → Room + storage. Antes de bytes: flush durable + lease. Lifetime compartido por `settleOnlineDownloadLifetime`: UIDT API 34+ o FGS dataSync API 26–33 para explícitas; job constrained para Guardar al escuchar. `MainActivity.onStart` refresca `BackgroundExecutionStatus` y reanuda interrupciones también en regreso warm. Banner/diálogo OEM solo si `blocksBackgroundPlayback` / `oemScreenOffCleanupActive` se confirman.

## Navegación UI

`MainScreen` bottom nav (índice persistido en `LibraryPreferencesRepository` / `selectedNavIndex`; deep-link descargas = `openDownloadsTabTransient` sin pisar snapshot; `SaveableStateHolder` / `SaveableStateProvider` preserva estado y scroll de cada tab):
0. Biblioteca (`LibraryScreen` + chips browse [Canciones, Álbumes, Artistas, Géneros, Playlists, Recientes] + nested album/artist/genre/playlist)
1. Descubrir (`DiscoverScreen` + feed recomendaciones [ListenBrainz / Deezer] + buscador con historial y categorías + detalle de colecciones)
2. Descargas (`DownloadsScreen`)
3. WiFi Sync (`WebServerScreen`)
4. Ajustes (`SettingsScreen` / temas / ListenBrainz y recomendaciones / Reproducción / Sonido / Descargas / update GitHub)

Overlay: `BottomPlayerBar` → `NowPlayingScreen` (⋮ canción/álbum; merge álbum en `MainScreen`); cola en `QueueScreen`.
El mini player consume `PlaybackRuntime.currentItem` / `isPlaying` / posición a través del ViewModel. `PlaybackRuntime.syncFromController` decodifica una sesión viva con `PlaybackMediaItemCodec.decode`; si no existe, `PlaybackSessionStore` + `PlaybackHydration.hydrateQueue` restauran cola/last-played (`songsForHydration` por ids si `allSongsFlow` aún no emitió) y `maybeSeedIdlePlayer` publica el estado (ver features §10b). `MusicService`/ExoPlayer arranca al conectar `MediaController` (`attachPlaybackUi` / `attachUi`), no en `PlaybackRuntime.connect()` ni con un `startService` extra en `MainActivity.onStart`. `BottomPlayerBar` no accede al controller.

**System back:** un paso por gesto en la jerarquía UI (`BackHandler` anidados; sin Navigation Compose). Prioridad: diálogos/menús → Now Playing → nested del tab → doble atrás para salir en raíz (`MainScreen`). Ver features §12.

## Principios estructurales (invariantes)

1. **Todo es colección** — play/shuffle/enqueue pasan por pipeline unificado (`MusicPlayerViewModel` façade → `PlaybackRuntime.playPlayableCollection`); cola process-scoped `PlayableItem` = orden físico y cada slot se identifica por `queueEntryId` (no `mediaId`); UI = `displayQueue`.
2. **Catálogo ≠ audio** — metadatos de iTunes/Deezer; bytes de audio vía YouTube (re-extraer URL antes de descargar/stream por CDN 403).
3. **Álbum vs playlist en portadas/metadata** — álbum tiene `album_overrides` (guardar solo álbum vs álbum+canciones); portada de playlist es entidad propia; editar canción no reescribe álbum.
4. **Portadas locales** — copiar a `context.filesDir` (no depender de content URIs temporales).
5. **Remoto efímero** — `PlayableItem.Remote` + `ResolvedStream` en memoria; `queueEntryId` identifica ocurrencias Local/Remote solo durante runtime; nunca persistir ids de slot ni URLs CDN en Room/DataStore.
6. **Radio** — sesión con seed + providers (`LocalMetadataRadio` / `ListenBrainzRadio` / `CfRecommendationsRadio` / `DeezerSimilarRadio` via `SimilarTracksProvider`); fill NEW/BOTH sin exigir token LB (red + Deezer); refill de cola; no pipeline paralelo.
7. **Para Ti mixto** — Discover + CF Recomendados reproducen Local+Remote; descarga manual por track (`DISCOVER`) / “Guardar al escuchar” / import LB no bloquean ni persisten URLs CDN; faltantes de import usan `activeDownloads` (`LB_IMPORT` + `targetPlaylistId`).
8. **Cola de descargas** — `QUEUED`/`SUCCESS` visibles; runtime/job process-scoped único por variantes plain/`batch:` y máx. 3 concurrentes globales entre manual + autosave; requests activos se persisten completos como interrumpidos, reconcilian commit Room/file y reanudan al próximo foreground salvo salida `REASON_USER_REQUESTED`; playlist del catálogo crea playlist local.
9. **TrackIdentity hub** — hechos musicales compartidos (`title`/`artist`/`album`/`artworkUri`/`durationMs`/`trackNumber`) viven en `TrackMeta` / `TrackIdentity` (`data/model/TrackIdentity.kt`). Catálogo (`OnlineCatalogTrack` / `CatalogTrackCandidate`), descargas (`ActiveDownload` vía `currentTrack`), identify (`IdentifyCandidate`), remoto (`PlayableItem.Remote`), persist cola (`PersistedQueueItem.Local` / `LastPlayedSnapshot`), stream YT (`YouTubeStreamResult`), tags de archivo (`AudioFileMetadata`), LB (`LbPlaylistTrack` / `LbRecordingMetadata`), match remoto CF/LB (`MatchedRemoteTrack`) y pending (`PlaylistPendingTrack`) envuelven ese núcleo. `Song` es **plano** (hot path Room + filtro/sort/`song.copy`) e implementa `TrackMeta` sin anidar — no persistir CDN / score / mbid. Campos nuevos de biblioteca = columna + `AppDatabase.version++`; efímeros no van en `Song`. `PlaylistPendingTrackEntity` sigue plano (columna SQL `releaseName`; mapper `album` ↔ `releaseName`). No clonar DTO satélite ni un Track gordo con mbid+score+CDN. `StreamResolver` es instancia única (`MusicRepository.streamResolver`).

## Servicios Android

Ownership process-scoped: `service/PlaybackRuntime.kt` (`create`, `attachUi`, `detachUi`, `events`; cola + `DiscoverPlaybackOrigin`, controller demand-driven, reconnect con backoff sin leer el facade desconectado, intención/slot/posición propios, callbacks externos de timeline/repeat/shuffle, generación de resolve/prefetch/recovery, posición/tracker/radio, resumption y writer serial de sesión), `service/ProcessDownloadRuntime.kt` (jobs/manual retry/conflictos/eventos) y `service/ProcessIdentifyRuntime.kt` (lote identify, remaining persistido, review por canción) retenidos por `BestiaPopApplication`; `service/PlaybackMediaItemCodec.kt` es la frontera Media3. `MediaLibraryBrowseProvider` versiona `allSongsFlow` + overrides + playlists en el scope del servicio, reconstruye el snapshot lazy en `Dispatchers.Default` solo al primer request tras un cambio, lo reutiliza para Auto/AVRCP y traduce selección al staging único de `PlaybackRuntime`. Los servicios/job Android solo dan lifetime/notificación; el ViewModel es fachada UI.

| Servicio | Rol |
|----------|-----|
| `MusicService` | `MediaLibraryService` + ExoPlayer (`WAKE_MODE_NONE` local / `WAKE_MODE_NETWORK` remoto + audio offload) + WakeLock transitorio en buffering/transiciones; única notif Media3 `RefreshingMediaNotificationProvider` → `DefaultMediaNotificationProvider`, canal `playback_channel`/1001, 10 min de gracia y FGS `mediaPlayback` mientras `playWhenReady && mediaItemCount > 0`; sticky `onStartCommand` llama `requestResumeAfterServiceRestart` si `shouldResumeAfterStickyRestart` (no persiste el player idle); notif 1002 solo si `blocksBackgroundPlayback` se confirma (`maybeNotifyBackgroundRestriction`) y se cancela al levantar la restricción. `onUpdateNotificationAsync` y refresh de artwork conservan Remote `STATE_IDLE`; `onTaskRemoved` usa política propia. `MediaButtonReceiver` + resumption restauran System UI/Bluetooth; callback separado sirve browse Auto/AVRCP y concede solo transporte a launchers/widgets no confiables. |
| `OnlineDownloadJobService` / `OnlineDownloadForegroundService` | Lifetime de descargas explícitas: UIDT `JobScheduler.setUserInitiated` API 34+; FGS `dataSync` + wake lock API 26–33. Comparten `collectDownloadNotifications` + settle ref-counted sin ocultar `setNotification` / `startForeground` |
| `OnlineAutomaticDownloadJobService` | JobScheduler con red para Guardar al escuchar (no finge UIDT ni intenta iniciar FGS desde background); usa el mismo runtime/cola y reprograma interrupciones |
| `IdentifyJobService` / `IdentifyForegroundService` | Lifetime de identificar pedido por el usuario: UIDT API 34+; FGS `dataSync` + wake lock API 26–33. `IdentifyExecutionLauncher` lease ref-counted; notif progreso + tap a revisión |
| `WebServerService` | Servidor Ktor local para sync/upload por WiFi. FGS `dataSync` (no `mediaPlayback`) vía `ServiceCompat.startForeground`; `onTimeout` Android 15+ marca transferencias activas como error y cierra limpiamente. Subidas: validación de `Host` + tope `MAX_UPLOAD_BYTES` también sobre el stream |

## Base de datos

Entidades: filas de app en `data.model` — `Song` (`songs`), `AlbumOverride` (`album_overrides`), `SongPlayStat` (`song_play_stats`). El resto sigue en `data/db`: `PlaylistEntity`, `PlaylistSongCrossRef`, `PlaylistPendingTrackEntity`, `PendingListenEntity`.
Índice único Room: `songs.uriString`. Deduplicación lógica por `matchKey(artist, title)` en filtros de scan / download conflict (`Music/BestiaPop` app-managed). I/O local solo vía `MusicFileStore` / `AudioPersistRef.canonicalize`: BestiaPop = path absoluto; MediaStore ajeno = `content://media`. One-shot `migrateCanonicalAudioUris` (`canonical_audio_uris_migrated`; identity-slim + `updateSongUri`, no clobbea `lyrics`). Migrator dedup histórico: branch `archive/library-dedup-v1-migrator`.
Migraciones Room: 1→2 (dedupe + unique index), 2→3 (playlist description/coverUri), 3→4 (pending_listens), 4→5 (playlist_pending_tracks), 5→6 (`album_overrides`), 6→7 (index `playlist_song_cross_ref.songId`), 7→8 (`songs.lastPlayedAt`), 8→9 (`playlist_pending_tracks.trackNumber`), 9→10 (`song_play_stats` + copy de `songs.lastPlayedAt > 0`), 10→11 (indices `album`, `artist`, `dateAdded`), 11→12 (index `title`).
Downgrade (instalar un APK viejo) sigue siendo destructivo para que la app abra, pero **no es silencioso**: `AppDatabase.VERSION` vs `LibraryPreferencesRepository.highestDbVersionSeen` (fuera de Room, sobrevive el wipe) → `warnIfDatabaseWasDowngraded` avisa que se perdieron playlists y overrides.

## Relacionado

- Features esenciales → skill `bestiapop-features`
- Mapa de archivos/funciones → skill `bestiapop-implementation-map`
- Protocolo de actualización de docs → skill `bestiapop-living-docs`
- Principios históricos resumidos → `.agents/AGENTS.md` (mantener alineado)
