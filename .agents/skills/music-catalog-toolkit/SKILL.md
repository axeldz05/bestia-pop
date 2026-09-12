---
name: music-catalog-toolkit
description: Herramientas CLI y scripts de investigación de catálogos musicales (Deezer, iTunes, YouTube InnerTube) y descarga de canciones o álbumes completos con merge y gap-filling.
---

# BestiaPop — Music Catalog & Download CLI Toolkit

Este skill proporciona un conjunto de herramientas ejecutables en Python (`scripts/catalog_tool.py`) para interactuar directamente desde la línea de comandos con las mismas APIs públicas y privadas de catálogo musical utilizadas en BestiaPop: **Deezer**, **Apple iTunes** y **YouTube (InnerTube)**.

Permite investigar respuestas crudas, simular la lógica de resolución de álbumes con combinación y relleno de huecos (gap-filling), extraer streams de audio de YouTube y descargar pistas individuales o álbumes completos.

---

## 1. Guía Rápida de Comandos CLI

El script principal se encuentra en:
```bash
.agents/skills/music-catalog-toolkit/scripts/catalog_tool.py
```

### A. Consultas a Deezer API
```bash
# Buscar artista por nombre
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer search-artist "Asian Kung-Fu Generation" --limit 5

# Listar discografía / álbumes de un artista por ID
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer artist-albums --artist-id 831 --limit 20

# Obtener canciones más populares de un artista
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer artist-top --artist-id 831 --limit 15

# Buscar un álbum específico
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer search-album "Magic Disk" --artist "Asian Kung-Fu Generation"

# Obtener tracklist de un álbum en Deezer
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer album-tracks --album-id 177830402

# Buscar canción suelta
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py deezer search-track "Haruka Kanata" --artist "Asian Kung-Fu Generation"
```

### B. Consultas a Apple iTunes API
```bash
# Buscar álbum en iTunes
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py itunes search-album "Magic Disk" --artist "Asian Kung-Fu Generation"

# Lookup estricto de tracks de un álbum (filtra wrapperType == "track")
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py itunes album-tracks --collection-id 1536382816

# Buscar canciones sueltas en iTunes
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py itunes search-song "Haruka Kanata"
```

### C. Resolvedor Unificado de Álbumes (BestiaPop Album Pipeline)
Replica la lógica de `MetadataFetcher.fetchAlbumTrackCandidates` implementada en la aplicación Android:
1. Intenta Deezer directo (`album/{id}/tracks`).
2. Si falla o no se conoce ID, busca el álbum en Deezer y extrae sus tracks.
3. Intenta iTunes directo por `collectionId`.
4. Si falla o no se conoce ID, busca el álbum en iTunes y hace `lookup?id={collectionId}&entity=song`.
5. Combina por slots (`trackNumber` y título normalizado) sin duplicar filas en UI y rellenando canciones faltantes.
6. **Invariante estricto:** Nunca inyecta canciones de búsquedas globales sueltas.

```bash
# Mostrar tracklist resuelto en consola
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py album --artist "Asian Kung-Fu Generation" --title "Magic Disk"

# Exportar como JSON completo
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py album --artist "Asian Kung-Fu Generation" --title "Magic Disk" --json
```

### D. YouTube (Búsqueda y Extracción de Stream)
```bash
# Búsqueda en YouTube emulando la app
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py youtube search "Asian Kung-Fu Generation Haruka Kanata" --limit 5

# Extraer URL directa de streaming de audio
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py youtube extract-stream "Asian Kung-Fu Generation Haruka Kanata"
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py youtube extract-stream "https://www.youtube.com/watch?v=nJ6A6GC_ki4"
```

### E. Descargas de Audio
```bash
# Descargar una canción suelta
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py download song "Asian Kung-Fu Generation Haruka Kanata" -o ./downloads

# Descargar un álbum completo resuelto con tracklist estricto y etiquetado automático
python3 .agents/skills/music-catalog-toolkit/scripts/catalog_tool.py download album --artist "Asian Kung-Fu Generation" --title "Magic Disk" -o ./downloads
```

---

## 2. Diferencias Funcionales entre `catalog_tool.py` y la App Android

Es fundamental comprender en qué aspectos difiere este script externo frente al código Kotlin nativo de BestiaPop:

| Característica / Capa | `catalog_tool.py` (Script CLI) | BestiaPop (App Android Nativa) |
|---|---|---|
| **Pila de Red (HTTP)** | Usa la biblioteca `requests` de Python sobre el stack de red de Linux. Conexiones sincrónicas o de proceso único. | Usa `OkHttpClient` (`HttpClients.api`) con pool de sockets HTTP/2, interceptores de User-Agent, timeouts configurados para Doze, y `CacheDataSource.Factory` de Media3. |
| **Streaming y Caché de Audio** | Descarga streams completos directamente a disco mediante bloques secuenciales HTTP. | Descarga por rangos de bytes (`Range: bytes=X-Y`) con recuperación de offsets (`GoogleVideoRange.kt`), persistiendo en la caché compartida de ExoPlayer mientras el usuario escucha en streaming. |
| **Entorno de Red y Bot Detection** | Corre en entornos de escritorio o servidores, donde YouTube InnerTube frecuentemente bloquea o solicita CAPTCHA (`LOGIN_REQUIRED`) a clientes web no autenticados. | Corre en dispositivos Android físicos con perfiles de cliente móvil (`ANDROID`, `ANDROID_MUSIC`, `TVHTML5`) y certificados GMS, permitiendo reproducir y extraer streams sin interrupción. |
| **Persistencia de Biblioteca** | Guarda archivos en carpetas locales (`./downloads/<Artista - Álbum>/`) y opcionalmente incrusta tags con `mutagen`. | Persiste en la base de datos Room SQLite (`songs`, `albums`, `album_overrides`, `playlists`), indexa en el MediaStore de Android y copia carátulas a `context.filesDir`. |
| **Ciclo de Vida y Energía** | No tiene restricciones de ahorro de energía; se ejecuta mientras el proceso en terminal permanezca activo. | Debe interactuar con Foreground Services (`MusicService`, `WebServerService`), Wakelocks con timeout, eventos de Doze mode (`dumpsys deviceidle`) y protección contra el Low Memory Killer (LMK). |
| **Concurrencia** | Ejecución procedural y secuencial por bloques. | Arquitectura basada en Kotlin Coroutines (`StateFlow`, `Dispatchers.IO`, `viewModelScope`), colas de descarga con estados reactivos (`ActiveDownload`, `DownloadPhase`, `ProcessDownloadCoordinator`). |

---

## 3. Modificación y Evolución del Script

Este script está pensado como una **herramienta viva**:
- Los agentes y desarrolladores pueden y deben modificar, optimizar o añadir nuevos subcomandos a `catalog_tool.py` cuando se identifiquen nuevos endpoints o se requieran diagnósticos más profundos (por ejemplo, soporte para MusicBrainz, ListenBrainz o extracción de letras sincronizadas LRC).
- Si se alteran los parámetros del script o sus capacidades, actualizar este archivo `SKILL.md` para mantener documentado su uso.
