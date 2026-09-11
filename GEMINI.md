# BestiaPop (sofoapps) — Agents rules

App Android Kotlin/Compose de música: biblioteca local, playlists, descarga online (YouTube), temas y WiFi sync.

## Skills de arquitectura (fuente de verdad viva)

Leer y seguir estos skills del repo **antes** de diseñar o implementar cambios no triviales. Contienen referencias directas a implementaciones actuales:

| Skill | Path | Usar cuando |
|-------|------|-------------|
| **Arquitectura** | `.agents/skills/bestiapop-architecture/SKILL.md` | Capas, stack, flujos, dónde colocar código |
| **Features** | `.agents/skills/bestiapop-features/SKILL.md` | Comportamiento esencial e invariantes + entry points |
| **Implementation map** | `.agents/skills/bestiapop-implementation-map/SKILL.md` | Localizar archivos/clases/funciones concretas |
| **Living docs** | `.agents/skills/bestiapop-living-docs/SKILL.md` | Protocolo para actualizar los skills anteriores |
| **Release changelog** | `.agents/skills/bestiapop-release-changelog/SKILL.md` | Anotar cambios user-facing y armar notas del APK |
| **Linter management** | `.agents/skills/kotlin-linter-management/SKILL.md` | Diagnóstico, ejecución y resolución de errores de linter sin supresiones |
| **Device debugging** | `.agents/skills/android-device-debugging/SKILL.md` | Depuración en dispositivo físico USB, memoria, Doze, LMK, servicios e input |

Resumen histórico de principios (mantener alineado con features): `.agents/AGENTS.md`

## Obligación de mantener los skills

Al modificar arquitectura, features esenciales o APIs/ubicaciones de código, **actualizar en el mismo cambio** los skills de la tabla (según `bestiapop-living-docs`):

1. Añadir/ajustar referencias **directas** (`path` + `Class`/`fun`).
2. Eliminar referencias obsoletas.
3. No dejar invariantes documentados que el código ya no cumpla.

Si solo hay un bugfix local sin cambio de diseño, no hace falta tocar skills.

## Convenciones de código

- Package root: `com.bestiapop.android`
- Lógica nueva de negocio → `domain/usecase`; persistencia/red → `data`; UI → `ui`
- Reproducción de agrupaciones → pipeline unificado `playCollection` / `shuffleCollection` / `enqueueCollection` (no inventar paths paralelos)
- Descarga de audio online → re-extraer stream YouTube antes de bajar (CDN expira → 403)
- Portada de **álbum** propaga a canciones; portada de **playlist** no
- Imágenes elegidas por el usuario → copiar a `context.filesDir`
- Preferir patrones y nombres ya usados en `MusicPlayerViewModel`, `MusicRepository`, use cases existentes
- Metadatos de canción compartidos → `TrackIdentity` / `TrackMeta` (`data/model/TrackIdentity.kt`). No clonar DTO satélite (title/artist/álbum/art/duration/trackNumber) por sistema. Wrappers solo para extras: score (`IdentifyCandidate` / `MatchedRemoteTrack.score`), mbid/stream (`PlayableItem.Remote`, `LbPlaylistTrack` / `LbRecordingMetadata`), genre de archivo (`AudioFileMetadata`), pending (`PlaylistPendingTrack`), columnas Room (`Song` plano = fila `songs`; `PlaylistPendingTrackEntity.releaseName` ↔ `identity.album`). Si un campo nuevo habría que pegarlo en >2 data classes de track, el modelo está mal.
- **Anti-patrón de decompresión de parámetros (prohibido desglosar en primitivas sueltas):** Nunca desglosar metadatos de canción existentes (`TrackMeta`, `TrackIdentity`, etc.) en múltiples parámetros primitivos independientes (`expectedDurationMs`, `expectedTitle`, `expectedArtist`, `expectedAlbum`, ...) a lo largo de métodos o capas intermedias. Si una función o pipeline necesita validar o comparar datos de una pista, debe recibir el contenedor unificado (`expected: TrackMeta? = null`). Desempaquetar campos en firmas intermedias es decompresión y obliga a tocar decenas de llamadas ante cualquier cambio futuro.
- No crear markdown de docs extras salvo que el usuario lo pida; los skills anteriores son el lugar para documentar arquitectura/features
- Changelog user-facing → `CHANGELOG.pending.md` (gitignored). Tras features/fixes visibles, anotar ahí (skill `bestiapop-release-changelog`). No anotar refactors ni detalle interno

## Build / deploy

- **Regla absoluta de instalación:** instalar o reinstalar en un dispositivo **únicamente** con `./install.sh` (debug) o `./install.sh --release`. Nunca usar `adb install`, `gradle install*`, `connectedDebugAndroidTest`/UTP ni instalar el APK de tests manualmente.
- **Preservar datos siempre:** no ejecutar `adb uninstall`, `pm uninstall`, `pm clear`, `cmd package clear` ni ninguna operación que pueda borrar Room, DataStore, playlists o ajustes. No borrar/recrear datos para tests.
- Los tests instrumentados se pueden **compilar**, pero no ejecutar si eso requiere instalar fuera de `install.sh`. Si una verificación exige otra instalación, detenerse e informar al usuario.
- Si `install.sh` falla, reportar el error; no sustituirlo con comandos manuales de instalación/desinstalación.
- Publicar para amigos: `./release.sh` (bump `version.properties`, APK firmado → GitHub Releases; `versionCode` en las notas del release). Repo en `github-release.properties` (`GITHUB_REPOSITORY=owner/repo`)
- Antes de `./release.sh`: leer skill `bestiapop-release-changelog`, resumir `CHANGELOG.pending.md` en notas **para el usuario** (sin refactors), escribir `CHANGELOG.release-notes.md` y pasar `--notes-file` (o dejar que el script lo tome si existe)
- Tests unitarios bajo `app/src/test`; instrumentados bajo `app/src/androidTest`

## Al terminar la implementación y haberlo verificado mediante Build - deploy
Leer las skills de refactorizacion y aplicalos en los cambios que hiciste.
- Compresión semántica .agents/skills/semantic-compression/SKILL.md
- Granularidad continua .agents/skills/continuous-granularity/SKILL.md
Busca principalmente comportamiento repetido que creaste ya sea en tus cambios o con el resto del codigo que podria estar teniendo comportamientos similares (ejemplo, si cambiaste como se descarga algo, busca en todas las partes de descargas si tienen comportamiento repetido).
Una regla de oro para saber si tenes comportamiento repetido es pensar en cuantos sitios tendrías que tocar código para cambiar algo de lo que implementaste, si son más de 2 veces es que tenés código repetido. Ejemplos: cambiar un algoritmo específico para las recomendaciones, cambiar texto de "descarga completada", botones como reproducir cancion o agregar a playlist.
- **Chequeo obligatorio de decompresión:** Al terminar cualquier cambio, revisar todas las funciones y llamadas modificadas: si se agregaron parámetros individuales que pertenecen a un modelo existente (ej. datos de canción `expected*`, metadatos de playlist o colecciones), refactorizar de inmediato para empaquetarlos en el tipo compartido (`TrackMeta`, etc.) antes de dar la tarea por concluida.

## Linters, calidad de código y modernización de APIs

- **Prohibición estricta de supresión:** Nunca agregar `@SuppressLint` ni `@Suppress` para eludir advertencias o errores del linter o del compilador. Si un linter marca un problema, se debe corregir el código en su raíz arquitectural o estructural, no esconderlo ni evitarlo.
- **Modernización y adaptación de APIs:** Si se requiere interactuar con funcionalidades de APIs inestables (ej. `@UnstableApi` en Media3) o deprecadas, investigar y adaptar el diseño para usar APIs estables o desacoplar la lógica en el límite correcto (ej. en la factoría de `DataSource` en lugar de llamadas de conveniencia).
- **Linters canónicos del proyecto:**
  - **Android Lint:** Correr `./gradlew lintDebug` o `./gradlew lint` para validar APIs de Android, recursos, KTX, ciclos de vida y seguridad.
  - **ktlint:** El linter y formateador estandarizado para Kotlin proviene exclusivamente de `https://github.com/ktlint/ktlint` (nunca de repositorios obsoletos de terceros).
  - **Kotlin compiler:** Compilar con `-Pkotlin.compiler.allWarningsAsErrors=true` para garantizar código libre de advertencias de compilación.
- **Invariante de entrega:** Todo cambio debe finalizar con **0 errores y 0 warnings en el código base** reportados por Android Lint, compilador de Kotlin y ktlint en los bloques de código modificados. Consulta el skill `.agents/skills/kotlin-linter-management/SKILL.md`.

## Limpieza obligatoria al terminar

Después de completar **toda** la implementación, la verificación final, living docs y refactorización:

1. Ejecutar `gradle --stop`.
2. Revisar los JVM restantes (`jps -lv`) y terminar con `TERM` cualquier
   `org.gradle.launcher.daemon.bootstrap.GradleDaemon` o
   `org.jetbrains.kotlin.daemon.KotlinCompileDaemon` que haya quedado del build.
3. Verificar que ya no queden Gradle Daemons ni Kotlin Compile Daemons.

Hacer esta limpieza solo después de la última tarea Gradle esperada, para no provocar reinicios
innecesarios durante la implementación. No matar los language servers de Cursor/IntelliJ/JDT ni
el `com.github.badsyntax.gradle.GradleServer`; tampoco borrar caches `~/.gradle` o artefactos de
build salvo pedido explícito del usuario.