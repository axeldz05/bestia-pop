# Informe de cobertura funcional de BestiaPop

Fecha de la auditoría: 12 de agosto de 2026.

Este documento describe qué comportamiento ya está protegido, qué riesgos siguen sin
pruebas y cómo repartir la implementación entre agentes. Su alcance es el producto
descrito por `README.md` y por los skills vivos de arquitectura, features e
implementation map. “Todos los tests” significa todos los comportamientos distintos e
invariantes conocidos del producto, no todas las combinaciones posibles de datos.

## 1. Resumen ejecutivo

- La captura agregada disponible informa **23% de instrucciones y 25% de ramas**.
  Unit tests aportan 19%/24% y androidTest 7%/5%. Es una foto orientativa, no una
  línea base contractual: los artefactos en `app/build` pertenecen a ejecuciones
  distintas y la ejecución unitaria más reciente fue parcial.
- Hay **60 archivos JVM con 390 métodos `@Test`** y **7 clases androidTest con 19
  tests**, más `DeviceAwakeRule`. El número de tests no es pequeño; el problema es su
  distribución.
- Las zonas fuertes son políticas de cola/reproducción, radio, matching, codecs y
  utilidades. Los huecos de mayor riesgo están en `MusicRepository`, descarga real a
  disco, importación/Room, `WebServerService`, el cliente HTTP de ListenBrainz, temas,
  pantallas completas y `MusicService` con ExoPlayer real.
- El siguiente salto útil no vendrá de añadir más tests de helpers. Vendrá de pruebas
  de integración que entren por una frontera del producto y observen un resultado:
  fila Room, archivo publicado, cola visible, HTTP status, preferencia restaurada,
  notificación o estado de una pantalla.
- No se recomienda fijar inicialmente un porcentaje global. Compose, coroutines y
  código Room/KSP generado distorsionan JaCoCo. La primera puerta debe ser una matriz
  de invariantes P0/P1 cumplida; después se puede fijar un piso por paquetes estables.

Prioridad recomendada:

1. Descarga completa/403/truncado y deduplicación/importación de biblioteca.
2. WiFi Sync HTTP y playlist pending después de descargar.
3. Navegación/back sobre pantallas reales y un smoke de `MusicService`.
4. Persistencia de temas, settings, cola y descargas.
5. Smokes con red real y verificaciones manuales, fuera de PR.

## 2. Qué significa “funcional” en este proyecto

Un test funcional puede ser rápido y usar fakes. Lo que lo define es qué contrato
protege, no dónde corre.

### Sí es funcional

- Llamar `downloadAndSaveOnlineTrack`, simular respuestas HTTP y verificar que una
  descarga truncada no aparece ni en disco ni en Room.
- Iniciar una colección en el tercer tema y verificar el orden de reproducción que
  ve el usuario, sin inspeccionar la lista temporal usada para rotarla.
- Abrir álbum → artista y pulsar Back; verificar que se retrocede exactamente un
  nivel.
- Reiniciar Activity o proceso y verificar que tema, posición, cola, theme o pantalla
  vuelven según las preferencias.
- Subir un archivo al endpoint WiFi y verificar HTTP, transferencia y canción
  persistida.

### Es demasiado dependiente de implementación

- Verificar cuántas veces se llamó un método privado cuando el resultado final basta.
- Testear una data class o getter solo para sumar instrucciones.
- Afirmar el orden interno de coroutines, salvo que el orden sea un contrato visible
  como latest-tap-wins.
- Copiar en androidTest cada caso de una política pura ya cubierta en JVM.
- Verificar IDs concretos de recursos Media3 cuando se puede comprobar la acción
  Play/Pause/Next resultante.

### Pirámide adecuada para BestiaPop

| Capa | Uso recomendado |
|---|---|
| JVM puro | Reglas, políticas, matching, orden, selección y transformaciones |
| JVM integración / Robolectric | Room, DataStore, archivos temporales, clientes HTTP mock, repositorio |
| androidTest Compose aislado | Semántica, estado y callbacks de una pantalla/componente |
| androidTest dispositivo | Activity, permisos, MediaStore, Service, Notification, ExoPlayer y process lifecycle |
| Nightly con red real | Detectar drift de YouTube/Deezer/iTunes/GitHub sin bloquear PR |
| Manual pre-release | Audio audible, OEM background, SAF real, TalkBack y LAN real |

## 3. Infraestructura y línea base observada

### 3.1 Configuración actual

- JaCoCo 0.8.15 lo aplica AGP 9.3.1.
- `debug` tiene `enableUnitTestCoverage` y `enableAndroidTestCoverage`.
- La agregación experimental está habilitada con
  `android.experimental.reportAggregationSupport=true`.
- El repo puede no tener `gradlew`; `coverage.sh` usa `gradle` del PATH como fallback.
- No hay exclusiones custom de JaCoCo. Room/KSP generado y bytecode de Compose entran
  en el denominador.
- No hay Gradle Managed Devices, Test Orchestrator, sharding ni animationsDisabled
  configurados hoy.

Comandos actuales:

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest

./coverage.sh --unit
./coverage.sh --android
./coverage.sh --all
```

Rutas relevantes:

- Unit: `app/build/reports/coverage/test/debug/index.html`
- androidTest esperado: `app/build/reports/coverage/androidTest/debug/connected/index.html`
- Agregado AGP actual:
  `app/build/reports/code_coverage_html_report/global/index.html`
- Agregado legacy esperado por el script:
  `app/build/reports/coverage/debug/index.html`

### 3.2 Métricas de la captura agregada

| Fuente | Instrucciones | Ramas |
|---|---:|---:|
| Agregada | 23% (39.330/170.304) | 25% (3.018/11.670) |
| JVM | 19% (32.788/170.304) | 24% (2.807/11.670) |
| androidTest | 7% (13.589/170.304) | 5% (640/11.670) |

Cobertura agregada por package:

| Package | Instrucciones | Ramas | Lectura |
|---|---:|---:|---|
| `ui.update` | 0% | 0% | Update UI sin tests |
| `ui` | 0% | 0% | ViewModel central sin test directo |
| `domain.repository` | 0% | n/a | Interfaces; no perseguir |
| `ui.screens` | 0% | 0% | Pantallas completas no ejecutadas |
| `data.repository` | 1% | 0% | Hueco crítico |
| `data.db` | 11% | 1% | Incluye mucho código generado |
| `ui.screens.library` | 11% | 12% | Componentes aislados parciales |
| `ui.theme` | 13% | 0% | Composición/DTO visual |
| `ui.components` | 14% | 13% | Cobertura puntual de widgets |
| package raíz | 18% | 2% | Application/Activity wiring |
| `data.network` | 31% | 22% | Parsers sí, clientes HTTP no |
| `data.util` | 39% | 35% | Utilidades mixtas |
| `data.update` | 39% | 55% | Parser/selección cubiertos |
| `ui.state` | 41% | 25% | Codecs/modelos parciales |
| `service` | 53% | 41% | Runtime con fakes; services reales no |
| `data.model` | 60% | 52% | No subir por getters |
| `data.preferences` | 66% | 73% | Codecs fuertes; stores reales parciales |
| `data.listenbrainz` | 74% | 57% | Políticas/coordinador fuertes |
| `data.stream` | 84% | 58% | Resolver fuerte |
| `domain.radio` | 86% | 60% | Motor fuerte |
| `domain.usecase` | 87% | 64% | Use cases fuertes |
| `data.playback` | 89% | 71% | Políticas fuertes |
| `domain.util` | 90% | 71% | No es la prioridad siguiente |

Clases de alto valor y cobertura baja:

- `MusicRepository`: aproximadamente 2% agregado; descarga, scan, identify, álbumes
  y playlists casi no se ejecutan.
- `MusicPlayerViewModel`: 0%; no hace falta cubrir todos sus métodos, pero sí sus
  fachadas que unen UI y casos de uso.
- `MusicService`: 0%; falta al menos un smoke de servicio/ExoPlayer/notificación.
- `WebServerService`: 0%; solo se prueba el sanitizer que usa.
- `ProcessSaveWhileListeningCoordinator`: sin suite dedicada.
- `ApkUpdateInstaller`: sin prueba de download/file-provider.

### 3.3 Caveats antes de comparar deltas

1. El HTML unitario, el `.exec` unitario y el agregado fueron generados en horas
   diferentes. La última salida de tests unitarios visible solo contiene tres clases.
2. Existe `coverage.ec` instrumentado, pero no el HTML androidTest clásico.
3. Las lambdas suspendidas aparecen como clases `$1`, `$2` o `ContinuationImpl`.
4. Room/KSP y Compose inflan el denominador. No borrar la métrica cruda; si se crea
   una vista “accionable”, publicarla en paralelo y documentar exclusiones.
5. Un incremento global puede ser inútil si proviene de DTOs. Cada agente debe
   reportar también qué invariante nuevo quedó protegido.

La baseline confiable se obtiene en un workspace limpio y sin filtros:

```bash
gradle :app:testDebugUnitTest
adb get-state
./coverage.sh --all
```

## 4. Inventario actual

### 4.1 JVM: 60 archivos, 390 tests

Los paths parten de `app/src/test/java/com/bestiapop/android/`.

| Test | Cant. | Evidencia principal | Tipo dominante |
|---|---:|---|---|
| `data/playback/PlaybackSelectionIntentGateTest` | 2 | Latest tap gana | Funcional/política |
| `data/playback/PlaybackTrackChangePolicyTest` | 4 | Cambio real vs mutación metadata | Funcional/política |
| `data/playback/PlaybackQueueSlotsTest` | 4 | Restaurar pre-shuffle y snapshot | Funcional/política |
| `data/playback/PlaybackQueueOrderTest` | 19 | Rotate, shuffle, trim, insert, remap | Funcional/política |
| `data/playback/PlaybackFallbackPlannerTest` | 3 | Fallback circular Local/Remote | Funcional/política |
| `data/stream/StreamResolverTest` | 9 | TTL, lock, refresh, invalidate | Funcional/integración fake |
| `service/PlaybackRuntimeContinuityTest` | 33 | Reconnect, persist, stale work, radio, autosave | Funcional/integración |
| `service/PlaybackMediaItemCodecTest` | 2 | Round-trip y CDN efímera | Implementación necesaria |
| `service/PlaybackServiceLifetimePolicyTest` | 3 | FGS para placeholder/ended | Funcional/política |
| `service/PlaybackNotificationContractTest` | 4 | Acciones de notificación | Híbrido |
| `service/ProcessDownloadCoordinatorTest` | 5 | Claims, límite 3, targets, cancel | Funcional/integración |
| `service/StereoBalanceAudioProcessorTest` | 3 | Escala PCM L/R | Implementación DSP |
| `data/preferences/PlaybackModeClearTest` | 10 | Clear en play/skip/radio | Funcional/política |
| `data/preferences/PlaybackModeRestoreTest` | 8 | Shuffle/repeat/autoplay al abrir | Funcional/política |
| `data/preferences/PlaybackSessionStoreTest` | 14 | Hydrate cola/last played | Funcional/integración fake |
| `data/preferences/QueueSnapshotCodecTest` | 5 | Persistencia sin CDN | Implementación necesaria |
| `data/model/PlayableItemRemoteMediaIdTest` | 7 | Duplicados y `queueEntryId` | Funcional/política |
| `ui/components/QueueUiIdentityTest` | 1 | Keys/foco de slots duplicados | Funcional UI |
| `data/network/YouTubeExtractionIntegrationTest` | 1 | Extracción real con red | Live smoke |
| `data/network/YouTubeAudioPreferenceTest` | 3 | Topic/Official Audio primero | Funcional/política |
| `data/network/CatalogGenreParseTest` | 3 | Géneros y tracks Deezer | Parser/contrato |
| `data/network/CatalogTrackNumberParseTest` | 2 | Número de pista catálogo | Parser/contrato |
| `data/network/ListenBrainzRadioParseTest` | 5 | JSON radio/CF | Parser/contrato |
| `data/model/ActiveDownloadCycleTest` | 3 | Cambiar candidato para retry | Funcional/modelo |
| `data/preferences/ActiveDownloadCodecTest` | 11 | Kill restore, badge, compat | Híbrido |
| `domain/usecase/DownloadAudioTrackUseCaseTest` | 4 | Fases, cancel y policy forward | Funcional/fachada |
| `data/util/UploadNameSanitizerTest` | 3 | Nombre común a upload/existing | Funcional/política |
| `domain/usecase/GetLibrarySongsUseCaseExecuteTest` | 3 | Buscar, ordenar, heredar artwork | Funcional |
| `domain/usecase/GetLibrarySongsUseCaseListItemsTest` | 20 | Álbumes, browse, recent, collapse | Funcional |
| `domain/usecase/AlbumCoverVsPlaylistCoverTest` | 2 | Portadas independientes en modelo | Funcional parcial |
| `data/preferences/LibraryUiPreferencesCodecTest` | 8 | Sort/view/nav y legacy | Híbrido |
| `data/util/FilenameMetadataHintsTest` | 20 | Rips y tags débiles | Funcional/política |
| `data/util/AlbumTrackNumbersTest` | 5 | Encode/decode disc-track | Implementación necesaria |
| `data/util/AudioPersistRefTest` | 5 | Canonicalización SAF/path/content | Funcional/política |
| `data/util/SongPathNormalizerTest` | 7 | Paths y filenames | Híbrido |
| `data/util/StorageUtilsTest` | 4 | MIME/labels/storage | Implementación |
| `data/util/AudioTagWriterTest` | 4 | Formato y writable gate | Funcional parcial |
| `domain/util/AlbumNamesTest` | 5 | Normalización/ellipsis/mojibake | Funcional/política |
| `domain/util/AlbumMergeTest` | 10 | Target y keys equivalentes | Funcional/política |
| `domain/util/TrackMatchKeysTest` | 9 | Normalize/match/download IDs | Funcional/política |
| `data/model/TrackIdentityTest` | 9 | Merge, duración y queries | Funcional/modelo |
| `data/util/TrackIdentityJsonTest` | 4 | JSON compartido | Implementación necesaria |
| `data/repository/PlaylistPendingTrackMapperTest` | 2 | `album` ↔ `releaseName` | Implementación necesaria |
| `ui/state/PlaylistDetailNavTest` | 2 | Estado de detalle | Híbrido |
| `domain/util/IdentifyRankingTest` | 25 | Confianza y conflictos | Funcional/política |
| `domain/util/IdentifyCatalogQueryTest` | 4 | Refinar búsqueda | Funcional/política |
| `domain/util/IdentifyAlbumGroupsTest` | 5 | Agrupar review | Funcional/política |
| `data/preferences/IdentifyReviewCodecTest` | 3 | Persistencia sin CDN/orphans | Híbrido |
| `domain/radio/RadioEngineTest` | 18 | KNOWN/NEW/BOTH, interleave, fill | Funcional/integración fake |
| `domain/radio/DeezerSimilarRadioTest` | 4 | Fill remoto y dedupe | Funcional/integración fake |
| `domain/usecase/BuildSimilarPlaylistPreviewUseCaseTest` | 4 | Preview multi-seed y playlist | Funcional |
| `domain/usecase/FetchAndMatchCfRecommendationsUseCaseTest` | 2 | CF a Local/Remote | Funcional |
| `domain/usecase/MatchListenBrainzTracksUseCaseTest` | 3 | Match normalizado | Funcional |
| `domain/usecase/ImportListenBrainzPlaylistUseCaseTest` | 4 | Matched + pending a playlist | Funcional |
| `data/listenbrainz/MatchedLbPlaylistPlayableTest` | 1 | Playlist LB a cola mixta | Funcional |
| `data/listenbrainz/ListenTrackerTest` | 4 | Progreso pese a metadata/repeat | Funcional |
| `data/listenbrainz/ListenSyncCoordinatorTest` | 5 | Offline queue, submit, retry | Funcional/integración fake |
| `data/listenbrainz/SaveWhileListeningPolicyTest` | 7 | Threshold, ended, manual skip | Funcional/política |
| `data/update/GitHubReleaseParserTest` | 6 | JSON de releases | Parser/contrato |
| `data/update/AppReleaseSelectionTest` | 5 | Versión actual y update target | Funcional/política |

Conclusión del inventario JVM: no faltan más casos de `PlaybackQueueOrder` o
`IdentifyRanking` con urgencia. Falta ejecutar las reglas ya probadas dentro de
fronteras reales: repositorio, Room, archivo, HTTP, Service, Activity y pantalla.

### 4.2 Instrumentados: 7 clases, 19 tests

| Test | Cant. | Qué prueba realmente | Límite |
|---|---:|---|---|
| `ui/LibraryScreenFunctionalTest` | 3 | Multi-select y lista aislada | No abre `MainActivity` |
| `ui/LibraryBrowseFunctionalTest` | 1 | Selección de chip | No prueba navegación |
| `ui/DownloadsUiFunctionalTest` | 5 | Cancel/retry/success callbacks | No descarga |
| `ui/PlaylistRemoteRowFunctionalTest` | 2 | Download/cancel de fila | No playlist completa |
| `ui/SystemBackHierarchyFunctionalTest` | 1 | Tres BackHandlers sintéticos | No usa pantallas reales |
| `service/PlaybackContinuityFunctionalTest` | 5 | Runtime con controller fake | No usa `MusicService`/ExoPlayer |
| `service/PlaybackNotificationFactoryInstrumentedTest` | 2 | PendingIntents Service/FGS | Sí cruza API Android real |

`testutil/DeviceAwakeRule` despierta y desbloquea el equipo. Un secure lock OEM puede
seguir bloqueando input; un emulador sin PIN es el entorno recomendado.

## 5. Matriz de tests funcionales propuestos

Leyenda:

- **P0:** riesgo de pérdida/corrupción o feature central sin red de seguridad.
- **P1:** flujo principal o regresión costosa.
- **P2:** cobertura complementaria.
- **N:** nightly opcional, con red real.
- **M:** manual pre-release.
- **JVM-I:** JVM integration/Robolectric; **AUI:** Compose aislado;
  **ADEV:** dispositivo/Activity/Service real.

Los nombres de clase son sugeridos. Extender una clase existente es preferible cuando
ya posee el fixture correcto.

### 5.1 Colecciones, cola, shuffle y continuidad

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable que lo hace funcional |
|---|---|---|---|
| P1 | Reproducir álbum desde pista N por `playPlayableCollection` | JVM-I, extender `PlaybackRuntimeContinuityTest` | Pista tocada queda actual y el prefijo pasa al final |
| P1 | Shuffle de colección conserva cada ocurrencia una vez | JVM, extender `PlaybackQueueOrderTest` | Cola reproducible sin perder duplicados |
| P1 | Enqueue agrega al final y Play Next detrás de actual | JVM-I, runtime existente | Orden visible final, no helper usado |
| P1 | Tap en cola con shuffle activo | JVM-I, runtime existente | Cambia pista sin apagar shuffle ni rotar cola |
| P1 | Dos taps remotos lentos | JVM-I, runtime existente | Solo suena el último tap |
| P1 | Remove/move durante shuffle y luego desactivar | JVM-I, runtime existente | Restaura sobrevivientes y deja agregados al final |
| P1 | Cola hidratada con autoplay off | JVM-I, runtime existente | Mini player aparece; no hay resolve hasta Play |
| P1 | Cola Remote-only tras cold start | JVM-I, extender `PlaybackSessionStoreTest` | Se restaura aunque biblioteca esté vacía |
| P2 | Drag de dos slots con mismo `mediaId` | AUI, `QueueDragFunctionalTest` | Se mueve la ocurrencia elegida por `queueEntryId` |
| P2 | Botones play/shuffle de álbum/artista/playlist | AUI, screens correspondientes | La cola mostrada coincide con el orden visual |
| P1 | Local → Remote → Local en `MusicService` | ADEV, `MixedQueuePlaybackFunctionalTest` | La transición mantiene orden, current y notificación |

### 5.2 Biblioteca, importación y datos Room

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | Insertar URI duplicada | JVM-I, `MusicRepositoryInsertIntegrationTest` | Conserva id, lyrics, `lastPlayedAt` y membresía |
| P0 | Borrar canciones | JVM-I, mismo test | Cross-refs desaparecen; playlist no queda corrupta |
| P0 | Scan MediaStore excluye `Music/BestiaPop` | Robolectric, `MusicRepositoryScanIntegrationTest` | No duplica archivo app-managed |
| P0 | Resync tras reinstall | Robolectric, `MusicRepositoryResyncIntegrationTest` | Archivos manejados vuelven una sola vez a Room |
| P1 | Canonicalizar SAF/cache a path absoluto | JVM-I, repositorio + temp files | URI final reproduce y playlists apuntan al id correcto |
| P1 | Primera importación corre una sola vez | Robolectric/DataStore | Segundo arranque no vuelve a escanear |
| P1 | Permission granted/denied en primer inicio | ADEV, `LibraryPermissionFunctionalTest` | Biblioteca importa o muestra CTA, sin crash |
| P1 | Buscar con tildes/puntuación y mantener selección | AUI, extender `LibraryScreenFunctionalTest` | Filtrar no deselecciona ids fuera de pantalla |
| P1 | Álbum interno por track number, cero al final | JVM, extender use case | Play usa el mismo orden que la lista |
| P1 | RECENT solo incluye `lastPlayedAt > 0` | JVM + AUI | Descendente y sheet de orden deshabilitado |
| P1 | Artista → álbum → Back | AUI/ADEV, `LibraryNavigationFunctionalTest` | Un gesto quita solo un nivel |
| P1 | Género → álbum → Back | AUI/ADEV, misma clase | Conserva el género al cerrar álbum |
| P2 | Collapse uno/todos en grupos | AUI | Cambia visibilidad sin alterar orden/play |
| P2 | Sort/view/chip tras recreate | Robolectric/ADEV | Preferencias y nav snapshot reaparecen |
| M | SAF folder con paths acentuados y SD | Manual | Canciones importadas, reproducibles y no duplicadas |

### 5.3 Identificación, metadata y portadas

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | Aplicar candidato a canción local | JVM-I, `MusicRepositoryIdentifyIntegrationTest` | Cambia identity pero conserva duración local real |
| P1 | HIGH auto-aplica, MEDIUM/LOW se encola | JVM-I, repo + fake catalog | Room/review reflejan la confianza, no llamadas internas |
| P1 | LB enriquece cuando catálogo no da HIGH | JVM-I, HTTP fixture | Propuesta final usa metadata LB y marca origen |
| P1 | Reidentificar omite pending | JVM-I, store real/fake HTTP | No hace nueva red y mantiene una sola entrada |
| P1 | Dismiss de review y cold start | Robolectric, `IdentifyReviewStoreIntegrationTest` | Overlay se oculta, badge/cola sobreviven |
| P1 | Aplicar grupo de álbum | AUI, `IdentifyReviewFunctionalTest` | Todas las MEDIUM elegibles se actualizan |
| P1 | Buscar otro + filtros + mostrar más | AUI + fake source | Candidatos visibles se anexan sin reordenar los vistos |
| P1 | Guardar álbum solo | JVM-I, `MusicRepositoryAlbumIntegrationTest` | Override cambia; filas Song no |
| P1 | Guardar álbum y canciones | JVM-I, misma clase | Override y siblings adoptan metadata |
| P1 | Editar una canción | JVM-I | Siblings y override de álbum permanecen |
| P1 | Merge con key mojibake/equivalente | JVM-I | Un álbum visible, canciones y override consistentes |
| P1 | Elegir cover externo | Robolectric | Se copia a `filesDir`; funciona tras perder content URI |
| P1 | Portada de playlist | JVM-I/AUI | Cambia playlist y nunca artwork de canciones |
| P2 | Tag write opt-in | Robolectric + audio fixture | Solo path writable soportado cambia; content URI se omite |
| M | Round-trip real mp3/m4a/flac/ogg | Manual/fixture legal | Tags pueden releerse por otra herramienta |

### 5.4 Catálogo, extractor y stream remoto

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P1 | Search track/album/playlist | JVM-I + MockWebServer, `MetadataFetcherIntegrationTest` | Modelos mostrables respetan identity y track number |
| P1 | Géneros y charts con error/empty/paginación | JVM-I, misma clase | UI recibe resultado o error estable, no crash de parser |
| P1 | Resolver id YouTube vs id Deezer/iTunes | JVM, extender extractor | Solo id YT se usa directo; catálogo crea query |
| P1 | Fixture estable de YouTube search/player | Robolectric, `YouTubeExtractorFixtureTest` | Obtiene video/audio/UA sin red real |
| P1 | Cache fresca vs playback max 60s | JVM, extender `StreamResolverTest` | Playback re-resuelve stream demasiado viejo |
| P1 | Invalidar por video borra keys id y query | JVM | Siguiente intento no reutiliza CDN muerto |
| P1 | Dos resolves concurrentes misma query | JVM | Una extracción compartida, ambos resultados válidos |
| P2 | Preview cancelado al elegir otra canción | JVM-I/AUI | El preview viejo no reemplaza al nuevo |
| N | Un video conocido extrae y reproduce 2–5 s | Nightly, test existente separado | Detecta drift del proveedor, no bloquea PR |

No se deben guardar HTML/JSON capturados con tokens, cookies o URLs CDN vigentes.
Los fixtures deben anonimizarse y contener solo la estructura necesaria.

### 5.5 Descarga online y centro de descargas

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | Body menor a `Content-Length` | JVM-I, `MusicRepositoryDownloadIntegrationTest` | No hay Song ni archivo parcial |
| P0 | Retry `Range`, servidor responde 206 | JVM-I, mismo test | Archivo final concatena una vez y bytes coinciden |
| P0 | Retry `Range`, servidor responde 200 | JVM-I | Reinicia desde cero; no duplica prefijo |
| P0 | CDN responde 403/410 | JVM-I | Fuerza nueva extracción y descarga desde byte cero |
| P0 | Se agotan 5 intentos/cancelación | JVM-I | Parcial se borra y estado termina ERROR/cancelado |
| P0 | Conflicto de artist+title | JVM-I | `DuplicateSongException` produce Overwrite/Save As/Cancel |
| P1 | Descarga exitosa | JVM-I | Archivo completo, Song con track number y estado SUCCESS |
| P1 | Manual y autosave misma identidad | JVM-I, `ProcessSaveWhileListeningCoordinatorTest` | Un job, InFlight neutral y un resultado |
| P1 | Targets de dos playlists en un claim | JVM-I, extender coordinator | Song se agrega una vez a cada una y pending se elimina |
| P1 | Gate metered cambia mientras espera permit | JVM-I | Se bloquea según red al adquirir permit |
| P1 | Métricas metered/unmetered al completar | JVM-I/DataStore | Suma bytes al tipo de red final |
| P1 | Cancelar QUEUED o DOWNLOADING y retry | JVM-I + AUI | Job real termina y nueva fila puede iniciar |
| P1 | Kill con DOWNLOADING/QUEUED/SUCCESS | Robolectric | Interrumpidas pasan a ERROR; SUCCESS se conserva |
| P1 | Deep-link de notificación | ADEV, `DownloadDeepLinkFunctionalTest` | Abre tab Descargas sin pisar nav persistida |
| P1 | Add Music: género/álbum/playlist y batch | AUI, `AddMusicDialogFunctionalTest` | Back retrocede colección; batch aparece en centro |
| P1 | Descarga pending de playlist | JVM-I/ADEV | Al terminar, placeholder se vuelve Song local |
| P2 | Badge y dismiss all | AUI | Cuenta solo downloading+error y limpia filas permitidas |

### 5.6 Playback real, notificación y segundo plano

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | Reproducir MP3 corto desde biblioteca | ADEV, `LocalPlaybackServiceFunctionalTest` | Progreso avanza y aparece FGS notification |
| P1 | Pause/Play/Next/Previous desde shade | ADEV, `NotificationMediaKeysFunctionalTest` | Current/play state cambia en la app |
| P1 | Activity recreate durante playback | ADEV | Misma cola, slot y posición aproximada |
| P1 | Finish/swipe task con play activo | ADEV AOSP | Service sigue; `stopWithTask=false` se cumple |
| P1 | Pause y task removed | ADEV | FGS deja de estar engaged según contrato |
| P1 | Remote mock lento, pausar durante resolve | ADEV + localhost | No prepara ni reanuda al completar |
| P1 | Error remoto dentro de grace | JVM-I + fake clock/controller | Reintenta hasta deadline sin cambiar de slot |
| P1 | Error remoto al vencer grace | JVM-I | Prueba fallback circular y pausa si no hay candidato |
| P1 | Next/prev desde `STATE_IDLE` | JVM-I/ADEV | Prepara y cambia; si estaba pausado sigue pausado |
| P1 | Headset/controller cambia repeat/shuffle | JVM-I callback | Runtime/prefs y cola visible se reconcilian |
| P2 | Volume boost enable/disable | ADEV | Rango UI 0..2 y setting llega al Service |
| P2 | Balance L/R sliders | ADEV + estado de processor | Gains aplicados y reset 1/1 |
| M | Audio audible, foco, unplug y OEM task kill | Manual físico | No se corta o se explica batería restringida |

Un emulador `-no-audio` sirve para estado/posición/notificación. No demuestra calidad
audible ni que `LoudnessEnhancer` funcione en un dispositivo real.

### 5.7 Playlists, ListenBrainz y Discover

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | CRUD playlist con Room | JVM-I, `MusicRepositoryPlaylistIntegrationTest` | Flows emiten, membresía y delete son consistentes |
| P1 | Import LB matched + pending | JVM-I, extender use case con repo Room | Playlist queda utilizable sin CDN persistida |
| P1 | Playlist id inválido al restore | JVM/AUI | Vuelve a lista general con aviso |
| P1 | Submit listens 200/401/500/timeout | JVM-I, `ListenBrainzClientIntegrationTest` | Queue se drena, conserva o informa según respuesta |
| P1 | Offline y regreso online | JVM-I, coordinator existente + fake client | Pendientes se envían en orden/batches |
| P1 | Created-for y detalle playlist | JVM-I, HTTP fixtures | Se obtiene identidad/mbid y se mapea Local/Remote |
| P1 | CF recording metadata | JVM-I | Recomendaciones se rematchean contra biblioteca |
| P1 | Download SUCCESS en Discover | JVM-I | Remote se convierte en Local sin reiniciar pantalla |
| P1 | Save while listening al threshold/ENDED | JVM-I + coordinator | Archivo/Song aparece una vez; manual skip no guarda |
| P1 | Settings LB no listas al cold start | JVM-I runtime | No hace scrobble/autosave antes de primera emisión real |
| P1 | Discover visible solo con enabled+username | AUI | Sección aparece/desaparece según contrato |
| P1 | Falla al restaurar detalle Discover | AUI | Vuelve a lista y muestra toast |
| P2 | Importar y descargar faltantes | AUI/ADEV mock | Filas muestran cola/progreso y terminan locales |
| M | Cuenta real LB: scrobble y Para Ti | Manual con token local | Listen visible en LB; nunca guardar token en fixture |

### 5.8 Radio y similares

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P1 | KNOWN con co-playlist boost | JVM, suite existente | Sugiere biblioteca, excluye seed/duplicados |
| P1 | NEW LB→CF→Deezer fill | JVM-I con providers fake | Resultado contiene solo Remote aun si provider falla |
| P1 | BOTH sin red | JVM-I | Continúa con conocidos y sin toast incorrecto |
| P1 | NEW timeout sin remotes | JVM-I fake clock | Tras presupuesto aparece “Radio online no disponible” |
| P1 | Inicio durante canción | JVM-I runtime | Conserva current y reemplaza solo upcoming |
| P1 | Refill al avanzar | JVM-I runtime | Añade slots nuevos sin duplicar historial |
| P1 | Radio auto en ENDED y repeat OFF | JVM-I | Usa preferred/default correcto |
| P1 | Stop cancela start/refill | JVM-I | No muta cola después de detener |
| P1 | Multi-seed bounded concurrency/deadline | JVM, extender preview | Termina en tiempo, round-robin y dedupe |
| P2 | Long press de radio en Now Playing | AUI | Selecciona KNOWN/NEW/BOTH o Detener |
| P2 | Preview editar/play/enqueue/create | AUI | Misma lista elegida llega a la acción |

Radio ya tiene buena cobertura pura. Los nuevos tests deben concentrarse en providers,
runtime y UI, no duplicar las permutaciones de `interleaveEquitable`.

### 5.9 WiFi Sync

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | `GET /existing-files` | Robolectric/Ktor, `WebServerServiceIntegrationTest` | Unión Room+folder, saneada y sin duplicados |
| P0 | POST válido con nombre acentuado/path | Robolectric/ADEV localhost | Archivo safe, transferencia SUCCESS y Song persistida |
| P0 | Fallo de persistencia | Robolectric | HTTP 500, transferencia ERROR y parcial borrado |
| P0 | Stream supera 512 MiB sin length | JVM-I con stream sintético | Se corta al límite sin reservar 512 MiB real |
| P1 | `Content-Length` mayor al límite | JVM-I | Responde 413 antes de escribir |
| P1 | Host externo/puerto incorrecto | JVM-I | Responde 403; Host LAN/localhost permitido |
| P1 | Start/stop desde tab | ADEV, `WifiServerLifecycleFunctionalTest` | URL/FGS state aparece y desaparece |
| P1 | Upload transfer progress/dismiss | AUI/ADEV | Fila progresa y puede descartarse |
| P1 | Upload completado dispara identify | JVM-I/ADEV | Canción se guarda y conflicto entra a review |
| P1 | Upload durante playback | ADEV | FGS dataSync no interrumpe mediaPlayback |
| P2 | Dashboard omite existentes | Browser/manual | No vuelve a subir mismo nombre saneado |
| M | Dos equipos en LAN real | Manual | URL accesible, Host guard válido y archivo reproducible |

Para JVM conviene extraer la construcción de rutas Ktor a una función que reciba
repository/audio store. Eso es una seam de frontera, no un helper creado para afirmar
detalles privados. Si se mantiene el Service monolítico, estos casos deberán correr en
androidTest y serán más lentos.

### 5.10 Temas, sonido y settings

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P1 | Elegir preset y cold start | Robolectric, `ThemePreferencesRepositoryTest` | Mismo id/colors/dark reaparece |
| P1 | Crear/editar custom theme | Robolectric | Colores custom persisten y se emiten |
| P1 | Cambiar tema en pantalla | AUI, `ThemeSettingsFunctionalTest` | La selección visible cambia sin recrear |
| P1 | Playback settings round-trip | Robolectric, `PlaybackPreferencesRepositoryTest` | Boost, gains, modos y grace se conservan |
| P1 | Download settings round-trip | Robolectric | Metered y contadores persisten |
| P1 | ListenBrainz settings readiness | Robolectric/runtime | Primera decisión usa valor guardado |
| P2 | Slider boost disabled/enabled | AUI | Rango/valor y callback correctos |
| P2 | Gains independientes + reset | AUI | Bajar L no sube R; reset da 1/1 |
| P2 | Tema custom y contraste semántico básico | AUI | Controles siguen presentes/legibles por semantics |
| M | Contraste visual y audio boost/balance | Manual físico | Resultado usable, no solo callbacks |

### 5.11 Persistencia, lifecycle y migraciones

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P0 | Migraciones Room 1→8 en cadena | JVM-I/Room, `AppDatabaseMigrationTest` | Schema final abre y conserva datos relevantes |
| P0 | Migración 7→8 | JVM-I | `lastPlayedAt` default y datos previos intactos |
| P1 | Downgrade detectado | Robolectric | Wipe permitido pero aviso de pérdida aparece |
| P1 | Queue/last played tras process kill | ADEV large | Mini player restaura sin autoplay si está off |
| P1 | Seek rápido y kill | JVM-I + fake persistence | Solo posición final reaparece |
| P1 | Shuffle snapshot y current borrado | JVM-I | Avanza a sobreviviente y posición 0 |
| P1 | Active downloads tras kill | Robolectric | SUCCESS queda; in-flight pasa a ERROR |
| P1 | Identify review tras kill | Robolectric/ADEV | Pending count y cola vuelven, overlay cerrado |
| P1 | Library nav snapshot | ADEV | Tab/chip/nested vuelven; search no |
| P1 | Playlist detail invalidado | ADEV | Fallback seguro a lista |
| P2 | App update notes cache offline | Robolectric | Ajustes muestra notas guardadas sin red |

### 5.12 Navegación, Back y accesibilidad

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P1 | Dialog → Now Playing → nested → root | ADEV, `MainScreenBackFunctionalTest` | Un Back cierra un nivel; playback continúa |
| P1 | Add Music collection → dialog | AUI | Primer Back limpia colección, segundo cierra |
| P1 | Identify item → overview → overlay | AUI | Cola se conserva al ocultar |
| P1 | Settings subsection → settings root | AUI/ADEV | Arrow y system Back tienen paridad |
| P1 | Playlist local/LB/CF detail → list | AUI | Solo detalle activo se cierra |
| P1 | Root double-back | ADEV | Primer gesto muestra snackbar; segundo sale |
| P1 | Timeout de double-back | ADEV fake/test clock si posible | Tras ventana vuelve a pedir dos gestos |
| P1 | Content descriptions de playback/download | AUI, `AccessibilityFunctionalTest` | Acciones clave tienen nombre y son clickeables |
| P1 | Sort sheet en RECENT | AUI | Orden está disabled y summary describe vista |
| P2 | Focus tras reorder de queue | AUI | Foco sigue el slot por `queueEntryId` |
| M | TalkBack recorrido Library/NP/Downloads | Manual | Orden, labels y anuncios son comprensibles |

### 5.13 Update, release y Crashlytics

| Pri. | Escenario y frontera | Capa / clase sugerida | Resultado observable |
|---|---|---|---|
| P1 | GitHub client 200/404/500/timeout | JVM-I, `GitHubUpdateClientIntegrationTest` | Result y lista de releases correctos |
| P1 | Launch throttle de 12 h | Robolectric, `AppUpdateViewModelTest` | No consulta antes; force sí |
| P1 | Notes de versión instalada offline | Robolectric | Cache visible en Ajustes |
| P1 | Descarga APK truncada/error | Robolectric, `ApkUpdateInstallerTest` | No abre installer ni deja archivo inválido |
| P2 | Update dialogs | AUI | Available/downloading/error disparan acciones correctas |
| P2 | Invitar amigos | Robolectric/ADEV | Intent ACTION_SEND contiene `/releases/latest` |
| N | Fetch release pública | Nightly | Parser/selection soportan payload real |
| M | APK firmado + unknown sources | Manual pre-release | Descarga e instalación completan |
| M | Crashlytics release | Manual build release | Crash/non-fatal llega sin Analytics/AD_ID |

`release.sh`, firma, GitHub publish y Play Console no deben ejecutarse para cobertura.
Son otro pipeline con dry-run/verificaciones propias.

## 6. Infraestructura propuesta

La infraestructura debe construirse una vez y luego quedar congelada durante las
oleadas paralelas.

### 6.1 JVM/Robolectric

- Añadir `MockWebServer` con la misma familia de OkHttp usada por producción.
- Añadir Room testing y una regla/factory para DB in-memory.
- Crear `testutil/RoomTestDatabase` o factory equivalente; cada test obtiene DB nueva
  y la cierra.
- Crear `testutil/MockWebServerRule` que exponga URL, request capturado y enqueue.
- Crear `testutil/TemporaryMusicFiles` para archivos pequeños y cleanup.
- Extender `FakeMusicRepository` solo con estado común. Fakes específicos viven junto
  al test; no convertirlo en un segundo `MusicRepository`.
- Usar dispatchers y clocks inyectados. Evitar `Thread.sleep`; esperar un estado con
  timeout y mensaje.
- Los clientes ya inyectables (`GitHubUpdateClient.http`) deben usarse sin cambiar
  producción. `MusicRepository(context)` construye DB, resolver, store y HTTP
  internamente; sus integraciones necesitarán seams mínimas o un fixture de contexto
  aislado. La seam debe representar una dependencia real, no exponer privados.

### 6.2 androidTest

- `MainActivity` usa `BestiaPopApplication` final y un grafo process-scoped real.
  Antes de crear una TestApplication, decidir una única estrategia:
  1. smoke full-app sobre grafo real y limpieza de datos, o
  2. factory de proceso/test runner que permita reemplazar DB/red/runtime.
- Crear un único `MainActivityFunctionalRule` con `DeviceAwakeRule`, permisos,
  locale estable, limpieza y `createAndroidComposeRule`.
- Añadir assets mínimos con licencia/creados para test: audio corto válido, imagen y
  fixtures JSON. No commitear música comercial.
- Usar `GrantPermissionRule` para `READ_MEDIA_AUDIO`/`POST_NOTIFICATIONS` según API.
- Desactivar animaciones para instrumentación.
- Considerar Android Test Orchestrator solo para suites que contaminan proceso; no
  usarlo en un test que necesita comprobar process-scoped continuity dentro del mismo
  caso.
- Etiquetar por tamaño/categoría: `Small` (Compose aislado), `Medium` (Room/local HTTP),
  `Large` (Service/process/device), `LiveNetwork`.

### 6.3 Qué no compartir entre agentes

Durante una oleada paralela solo un agente puede ser dueño de:

- `app/build.gradle.kts` y `gradle/libs.versions.toml`
- `FakeMusicRepository.kt`
- reglas bajo `testutil/`
- `DeviceAwakeRule.kt`
- test runner/TestApplication
- assets/fixtures comunes
- `coverage.sh`

Los demás agentes crean o editan exclusivamente sus clases asignadas. Si necesitan
infra nueva, dejan una petición para el dueño de Wave 0 en vez de duplicarla.

## 7. Oleadas para agentes en paralelo

Cada agente debe entregar: tests, comando exacto ejecutado, resultado, delta del
package/clase tocada y lista de invariantes protegidos. No debe perseguir líneas sin
valor ni cambiar comportamiento de producción salvo que encuentre un bug reproducible.

### Wave 0 — baseline y harness compartido (serial)

**Dueño único:** Infra.

**Archivos permitidos:** Gradle/version catalog de test, `testutil`, assets/fixtures y,
si se aprueba, runner/TestApplication. No implementar escenarios de features.

**Trabajo:**

1. Ejecutar suite JVM completa y guardar métricas de la misma corrida.
2. Arrancar emulador limpio y ejecutar los 19 androidTests actuales.
3. Incorporar MockWebServer, Room testing, temp files y categorías.
4. Acordar seam de `MusicRepository` y full-app graph.
5. Dejar un ejemplo verde de Room y otro de MockWebServer; los agentes siguientes los
   copian por uso, no por implementación.

**Comandos:**

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
./coverage.sh --all
```

**Terminado cuando:** baseline reproducible, harness documentado en código, tests
actuales verdes y ningún fixture depende de internet o datos personales.

### Wave 1 — quick wins JVM puros (4 agentes, paralelo)

No depende de Wave 0 salvo que se agreguen fixtures JSON.

- **1A Playback:** extender únicamente `PlaybackModeClearTest`,
  `PlaybackRuntimeContinuityTest` y/o `PlaybackSessionStoreTest` con tap+shuffle,
  enqueue/play-next y display-only remote.
- **1B Library:** extender únicamente `GetLibrarySongsUseCase*Test` con RECENT,
  selección/proyección y orden interno.
- **1C Discover:** extender únicamente tests de rematch, CF/LB y radio provider sin
  tocar runtime.
- **1D Update/prefs:** añadir casos de selección/update cache que no requieran Android
  real.

**Ejecutar cada clase:**

```bash
gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.service.PlaybackRuntimeContinuityTest'
```

Al integrar:

```bash
gradle :app:testDebugUnitTest
./coverage.sh --unit
```

**Terminado cuando:** cada caso afirma estado final/resultado visible; no se añadieron
tests de getters/codecs sin un requisito de compatibilidad.

### Wave 2 — integraciones JVM/Robolectric (4 agentes, paralelo)

Depende de Wave 0. Ningún agente toca fixtures comunes.

#### 2A — Room, biblioteca, playlists y álbumes

Clases propias:

- `MusicRepositoryInsertIntegrationTest`
- `MusicRepositoryAlbumIntegrationTest`
- `MusicRepositoryPlaylistIntegrationTest`
- `AppDatabaseMigrationTest`

Escenarios P0/P1 de 5.2, 5.3, 5.7 y 5.11.

#### 2B — HTTP, extractor y descarga

Clases propias:

- `MusicRepositoryDownloadIntegrationTest`
- `MetadataFetcherIntegrationTest`
- `YouTubeExtractorFixtureTest`
- `GitHubUpdateClientIntegrationTest`

No editar `YouTubeExtractionIntegrationTest` hasta decidir su categoría nightly.

#### 2C — ListenBrainz, autosave y coordinator

Clases propias:

- `ListenBrainzClientIntegrationTest`
- `ProcessSaveWhileListeningCoordinatorTest`
- extensiones de `ListenSyncCoordinatorTest`

No editar `ProcessDownloadCoordinatorTest` si 2B lo está usando; crear una clase de
integración separada.

#### 2D — DataStore, themes, settings y review

Clases propias:

- `ThemePreferencesRepositoryTest`
- `PlaybackPreferencesRepositoryTest`
- `IdentifyReviewStoreIntegrationTest`
- `AppUpdateCheckStoreTest`

**Comandos por agente:**

```bash
gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.data.repository.MusicRepository*IntegrationTest'

gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.data.network.*IntegrationTest'

gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.service.ProcessSaveWhileListeningCoordinatorTest'

gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.data.preferences.*Test'
```

**Terminado cuando:** no hay red real; DB/files/server cierran en `finally`/rules;
errores y cancelación dejan estado limpio; los P0 pasan individualmente y en suite.

### Wave 3 — androidTest aislado y full-app (4 agentes, paralelo)

Depende de Wave 0; Playback/WiFi pueden reutilizar resultados de Wave 2.

#### 3A — Compose aislado, Back y accesibilidad

- `LibraryNavigationFunctionalTest`
- `IdentifyReviewFunctionalTest`
- `AddMusicDialogFunctionalTest`
- `SettingsNavigationFunctionalTest`
- `AccessibilityFunctionalTest`

Usa `createComposeRule`; no necesita `MainActivity` salvo double-back.

#### 3B — MainActivity, biblioteca y playlists

- `MainScreenBackFunctionalTest`
- `LibraryPermissionFunctionalTest`
- `PlaylistCrudFunctionalTest`
- `DownloadDeepLinkFunctionalTest`

Es el único dueño de full-app rule durante la oleada.

#### 3C — MusicService y playback

- `LocalPlaybackServiceFunctionalTest`
- `NotificationMediaKeysFunctionalTest`
- `MixedQueuePlaybackFunctionalTest`
- `PlaybackActivityRecreateFunctionalTest`

Es el único dueño de assets de audio ya definidos por Wave 0.

#### 3D — WiFi y downloads localhost

- `WebServerServiceIntegrationTest` o `WifiServerLifecycleFunctionalTest`
- `WifiUploadFunctionalTest`
- `DownloadToLibraryFunctionalTest`
- `PlaylistPendingDownloadFunctionalTest`

No usa LAN externa: server y cliente viven en emulator/localhost.

**Comando para una clase en un único dispositivo conectado:**

```bash
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bestiapop.android.ui.LibraryNavigationFunctionalTest
```

**Comando por package cuando la oleada ya está integrada:**

```bash
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.bestiapop.android.ui
```

**Terminado cuando:** no hay sleeps arbitrarios, el keyguard está controlado, cada
test limpia Room/DataStore/files/Service y pasa tres veces seguidas en emulador.

### Wave 4 — process death, migración y segundo plano (2 agentes, paralelo limitado)

Depende de Waves 2 y 3C. Son `@LargeTest`; ejecutar separados de UI small.

- **4A Persistencia:** queue/process kill, active downloads, nav snapshot, review y
  migration DB.
- **4B Background:** task removed, FGS, reconnect de service, upload durante playback.

No ejecutar ambos sobre el mismo emulador al mismo tiempo.

```bash
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest
```

**Terminado cuando:** cada test deja la app detenida/limpia, distingue Activity
recreate de process kill y documenta tolerancias de posición/tiempo.

### Wave 5 — nightly y manual (no bloquea PR)

**Automático nocturno:**

- YouTube extractor/play de un fixture público estable.
- GitHub Releases pública.
- Opcional Deezer/iTunes search sin autenticación.
- Nunca submit ListenBrainz real.

**Manual pre-release:**

- Reproducción audible, audio focus, unplug y background en Motorola/AOSP.
- Boost y balance con auriculares.
- WiFi upload desde otro equipo.
- SAF/SD card y nombres acentuados.
- TalkBack y contraste de themes.
- Update APK firmado/unknown sources.
- Crashlytics release.

Guardar solo resultados y timestamps; nunca tokens, cookies, audio comercial ni CDN
URLs.

## 8. Emulador Linux

### 8.1 Setup headless mínimo

```bash
sdkmanager \
  "platform-tools" \
  "emulator" \
  "platforms;android-34" \
  "system-images;android-34;google_apis;x86_64"

avdmanager create avd \
  -n bestiapop_api34 \
  -k "system-images;android-34;google_apis;x86_64" \
  -d pixel_6
```

Comprobar KVM:

```bash
test -r /dev/kvm -a -w /dev/kvm
```

Arrancar:

```bash
emulator \
  -avd bestiapop_api34 \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  -memory 2048 \
  -cores 2
```

En otra terminal:

```bash
adb wait-for-device
adb shell 'until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done'
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

No configurar PIN/patrón en ese AVD. `DeviceAwakeRule` no puede derrotar un secure
lock de forma confiable.

### 8.2 Matriz de dispositivos

- PR rápido: API 34 Google APIs, phone, mock network.
- Compat periódica: API 26 o 28 y API 36.
- Pre-release: AOSP emulator + un equipo físico OEM.
- Playback audible/OEM restrictions: físico; el AVD no reemplaza ese control.

### 8.3 Paralelismo

Hoy `connectedDebugAndroidTest` descubre dispositivos conectados y el proyecto no
tiene managed devices. `ANDROID_SERIAL` por sí solo no debe asumirse como aislamiento
fiable para Gradle. Opciones seguras:

1. un emulador y partición secuencial por class/package;
2. jobs/containers separados, cada uno con su propio adb server y AVD;
3. configurar Gradle Managed Devices en Wave 0 y usar un device por shard.

Propuesta futura a validar con AGP 9.3:

```kotlin
testOptions {
    animationsDisabled = true
    managedDevices {
        localDevices {
            create("pixel6Api34") {
                device = "Pixel 6"
                apiLevel = 34
                systemImageSource = "aosp"
            }
        }
    }
}
```

Después:

```bash
gradle :app:pixel6Api34DebugAndroidTest
```

Para dos shards directos con instrumentation, primero instalar APK y test APK en cada
serial y usar `am instrument -e numShards N -e shardIndex I`. Esa vía no alimenta
automáticamente el reporte AGP igual que `connectedDebugAndroidTest`; usarla para
tiempo de feedback, y generar coverage final en una corrida soportada por Gradle.

## 9. Comandos de operación por alcance

JVM completo:

```bash
gradle :app:testDebugUnitTest
```

Una clase JVM:

```bash
gradle :app:testDebugUnitTest \
  --tests 'com.bestiapop.android.data.stream.StreamResolverTest'
```

Todos los instrumentados:

```bash
adb get-state
gradle :app:connectedDebugAndroidTest
```

Una clase instrumentada:

```bash
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bestiapop.android.service.PlaybackContinuityFunctionalTest
```

Coverage unitario:

```bash
./coverage.sh --unit
```

Coverage instrumentado:

```bash
./coverage.sh --android
```

Coverage combinado final:

```bash
./coverage.sh --all
```

Si AGP no crea la ruta legacy, abrir:

```text
app/build/reports/code_coverage_html_report/global/index.html
```

## 10. Criterios de aceptación y gates

### Para cada test

- El nombre expresa situación y resultado, no método interno.
- Entra por una frontera que producción usa o por una seam equivalente.
- Afirma resultado observable y cleanup.
- Controla tiempo, dispatcher y red.
- Tiene un mensaje útil al fallar.
- No duplica un caso JVM en androidTest sin añadir una frontera Android.

### Para cada agente

- Ejecuta su clase/suite focal.
- Ejecuta la suite de su source set antes de entregar.
- Reporta tests añadidos, invariantes, comando y duración.
- Reporta flakes observados; no los oculta con retries indiscriminados.
- No edita shared infra sin coordinación.

### Para integrar una wave

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
./coverage.sh --all
```

Gates iniciales recomendados:

1. Todos los P0 de descarga/importación/Room/WiFi verdes.
2. Ningún live-network test en la suite PR.
3. Cero tests instrumentados que dependan de orden de ejecución.
4. Cada nuevo archivo de producción de negocio trae al menos un contrato funcional.
5. El package tocado no baja sin explicación.

Solo después de dos o tres waves con baseline estable:

- `domain.util`, `data.playback` y `domain.usecase`: mantener sus niveles actuales.
- `data.repository`: fijar un piso basado en los métodos P0/P1, no en toda la clase.
- `service`: separar `PlaybackRuntime` de Android Services reales al leer el delta.
- UI: gatear escenarios/semantics, no un porcentaje global de bytecode Compose.

## 11. Orden recomendado de implementación

1. Wave 0 estabiliza medición y fixtures.
2. Wave 1 suma quick wins sin bloquear.
3. Wave 2 ataca el mayor riesgo: Room, repo, HTTP y DataStore.
4. Wave 3 valida las fronteras Android y pantallas.
5. Wave 4 comprueba lifecycle real.
6. Wave 5 detecta drift externo y defectos que coverage no puede demostrar.

La meta no es “100%”. La meta es que una refactorización pueda cambiar helpers,
coroutines, Room mappers o estructura Compose sin romper tests mientras el usuario
siga pudiendo importar, identificar, reproducir, personalizar, descargar, sincronizar
y recuperar su sesión con el comportamiento documentado.
