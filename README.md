# BestiaPop

Reproductor de música para Android con biblioteca local, playlists, descargas y streaming, radio de similares, ListenBrainz y sincronización por WiFi.

**Estado:** beta · **Requisito:** Android 8.0 (API 26) o superior · **Versión:** [`version.properties`](version.properties)

[Descargar la última versión](https://github.com/axeldz05/bestia-pop/releases/latest)

Sin anuncios ni Analytics. Los builds release envían fallos técnicos a Firebase Crashlytics.

## Instalar

1. Abrí la [última release](https://github.com/axeldz05/bestia-pop/releases/latest).
2. Descargá `BestiaPop-*.apk`.
3. Permití **Instalar apps desconocidas** para el navegador o gestor de archivos.
4. Abrí el APK e instalalo.
5. Concedé acceso al audio y a las notificaciones cuando la app lo solicite.

Las actualizaciones siguientes se pueden instalar desde **Ajustes → Actualización → Buscar actualización** o descargando un APK nuevo. **Ajustes → Invitar amigos** comparte el enlace de la última release.

> Algunos fabricantes restringen las apps instaladas fuera de una tienda. Si la música se corta al salir, configurá BestiaPop como “Sin restricciones” o excluila de la optimización de batería.

## Primeros pasos

1. En el primer arranque, esperá a que termine el banner de importación de la biblioteca.
2. Tocá una canción o usá Play/Shuffle en un álbum, artista o playlist.
3. Desde **Biblioteca → Agregar**, elegí una carpeta, pegá un enlace de YouTube o explorá el catálogo.
4. Abrí **Descargas** para cancelar, reintentar o reproducir una descarga terminada.
5. Tocá el mini player para abrir Now Playing: portada, letra, cola, radio, volumen y acciones de la canción.
6. Para pasar música desde una PC, activá **WiFi Sync** y abrí en la PC la URL que muestra la app.

La barra inferior reúne **Biblioteca**, **Playlists**, **Descargas**, **WiFi Sync** y **Ajustes**. El gesto atrás cierra un nivel; en la raíz, pulsalo dos veces para salir sin detener la música.

## Funciones principales

### Biblioteca

- Importación inicial de la música del dispositivo e importación manual de carpetas del teléfono o SD.
- Exploración por **Canciones**, **Álbumes**, **Artistas**, **Géneros** y **Recientes**.
- Búsqueda, orden ascendente/descendente y vista plana o agrupada por álbum desde el botón de ajustes de vista.
- Edición de título, artista, álbum, género, año, número de pista y portadas.
- Identificación online de tags. Las coincidencias dudosas quedan en una cola persistente para revisar, buscar otra opción, aplicar u omitir.
- Selección múltiple para identificar, buscar similares, crear una playlist o borrar canciones.

Las descargas propias se guardan en `Music/BestiaPop`. Si los archivos siguen allí después de reinstalar, la app puede reindexarlos.

### Agregar, streaming y descargas

**Biblioteca → Agregar** ofrece tres entradas:

- **Local:** importar una carpeta.
- **Por enlace:** pegar una URL de YouTube, `youtu.be` o un ID de video.
- **Catálogo:** buscar canciones, álbumes, playlists, géneros y charts con metadatos de iTunes/Deezer.

Los resultados del catálogo se pueden previsualizar por streaming o descargar. Las descargas:

- se registran en una cola única con hasta tres trabajos simultáneos;
- aparecen en la pestaña **Descargas**;
- permiten sobrescribir, guardar como nuevo o cancelar ante un duplicado;
- reextraen el stream antes de descargar para evitar reutilizar una URL vencida.

El uso de redes móviles está permitido por defecto y se puede cambiar en **Ajustes → Descargas**.

### Reproducción y radio

- Una misma cola puede combinar canciones locales y streams remotos.
- Mini player, Now Playing con pestañas de portada/letra/cola y cola reordenable.
- Reproducción en segundo plano mediante Media3 y controles en la notificación del sistema.
- La cola y el último tema se conservan entre sesiones. Autoplay está apagado por defecto; recordar shuffle y repetición está encendido.
- Ajustes para saltar streams que fallan, limpiar modos al reproducir/saltar y evitar restricciones de batería.

La radio se inicia desde Now Playing o el menú de una canción:

- **Solo conocidos:** usa canciones de la biblioteca.
- **Solo nuevos:** busca descubrimientos online.
- **Ambos:** intercala canciones locales y remotas.

Tocá el icono de radio para usar el modo preferido; mantenelo pulsado para cambiar de modo o detener la radio. Desde una selección múltiple, **Similares** permite revisar resultados y crear una playlist sin alterar la cola actual.

### Playlists y ListenBrainz

- Playlists locales con portada propia, sin modificar las portadas de sus canciones.
- Scrobbling con [ListenBrainz](https://listenbrainz.org); los listens sin conexión se envían más tarde.
- **Para Ti** y **Recomendados** con mezcla de coincidencias locales y remotas.
- Descarga por canción, importación de playlists y **Guardar al escuchar** para streams.

Configurá el token desde **Ajustes → ListenBrainz**, validalo y activá **Mostrar Para Ti**. El token se obtiene en [listenbrainz.org/settings](https://listenbrainz.org/settings/).

### WiFi Sync

Con ambos dispositivos en la misma red, activá el servidor en **WiFi Sync** y abrí su URL desde una PC u otro dispositivo. La app permite subir audio, omite archivos que ya conoce e intenta identificar los tags importados. Los conflictos quedan disponibles para revisión.

### Personalización

- Temas predefinidos y colores personalizados.
- Volumen por encima del 100% y balance estéreo L/R independiente.
- Escritura opcional de tags de Room a archivos compatibles desde **Ajustes → Archivos**.
- Preferencias de reproducción, descargas, ListenBrainz y actualización agrupadas en **Ajustes**.

## Datos, privacidad y límites

- No hay anuncios, Firebase Analytics ni uso del advertising ID.
- Crashlytics solo recopila fallos en builds no-debug.
- Playlists, overrides de álbum y preferencias viven en los datos privados de la app. No confíes en que sobrevivan a una desinstalación: el backup cloud excluye Room, aunque una transferencia entre dispositivos puede conservar parte de los datos según Android.
- Los audios en `Music/BestiaPop` suelen permanecer en el almacenamiento al desinstalar y se pueden reindexar.
- Las URLs CDN de YouTube caducan y nunca se guardan en Room; se vuelven a resolver al reproducir o descargar.
- Catálogo, identificación, radio online, ListenBrainz y streaming necesitan conexión.
- La resolución de YouTube usa InnerTube, una API interna no estable. Los cambios de YouTube pueden interrumpir temporalmente streaming o descargas.

## Solución de problemas

- **La APK no instala:** habilitá “Instalar apps desconocidas” para la aplicación que abrió el archivo.
- **La música se corta al salir:** quitá la restricción de batería para BestiaPop.
- **Una descarga devuelve 403 o un stream falla:** comprobá la red y reintentá desde **Descargas**; las URLs de YouTube expiran.
- **No aparece una actualización:** usá un build release y abrí **Ajustes → Actualización**. Para instalarla hace falta permiso de paquetes desconocidos.
- **La biblioteca no reaparece tras reinstalar:** verificá que los audios continúen en `Music/BestiaPop`; playlists y metadata privada pueden haberse perdido.

## Desarrollo

### Requisitos

- JDK 17
- Android SDK con compile/target SDK 36
- Gradle disponible en `PATH` (el repositorio no incluye Gradle Wrapper)
- `adb` y un dispositivo o emulador para instalar y ejecutar tests instrumentados

```bash
git clone https://github.com/axeldz05/bestia-pop.git
cd bestia-pop
```

Descargá `app/google-services.json` desde la [Firebase Console](https://console.firebase.google.com/) antes de compilar. El repositorio no distribuye una configuración de ejemplo; Crashlytics real requiere el archivo del proyecto Firebase.

Para desarrollo diario:

```bash
./install.sh              # debug
./install.sh --release    # release local; usa firma debug si no hay keystore
```

El script compila, instala con ADB, abre `MainActivity` y ajusta restricciones de segundo plano conocidas en sideload. Debug y release comparten `applicationId`; si existe `keystore.properties`, ambas variantes usan el mismo certificado.

Un `keystore.properties` local utiliza estas claves:

```properties
storeFile=path/al/archivo.jks
storePassword=...
keyAlias=...
keyPassword=...
```

No subas ese archivo ni el keystore al repositorio.

### Tests y coverage

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest   # requiere dispositivo/emulador

./coverage.sh                 # tests JVM + reporte HTML
./coverage.sh --android       # instrumentados + reporte
./coverage.sh --all           # reporte combinado
./coverage.sh --emulator-help # ayuda para un emulador headless
```

Los escenarios que necesitan coordinación desde el host viven en:

- `scripts/run-playback-process-death-e2e.sh`
- `scripts/run-playback-task-removal-e2e.sh`
- `scripts/run-backup-restore-e2e.sh`
- `scripts/run-library-permission-denied-e2e.sh`

### Publicar una release

La distribución actual es una APK firmada en GitHub Releases. Para publicar se necesita:

- `app/google-services.json`;
- `keystore.properties` y su `.jks`;
- `GITHUB_REPOSITORY=axeldz05/bestia-pop` en `github-release.properties`;
- GitHub CLI autenticado con `gh auth login`;
- notas en `CHANGELOG.release-notes.md` o mediante `--notes`/`--notes-file`.

```bash
./release.sh --dry-run   # valida versión, tag y notas sin escribir ni compilar
./release.sh             # bump, build, tag y GitHub Release
```

El script incrementa `version.properties`, genera `dist/BestiaPop-*.apk`, crea `v{VERSION_NAME}` y verifica que la release contenga el APK y el `versionCode` esperado por el updater.

## Estructura

BestiaPop es un único módulo Android `:app`, con package raíz `com.bestiapop.android`.

```text
ui/       pantallas Compose, componentes, estado y ViewModel
domain/   casos de uso, radio y contrato del repositorio
data/     Room, red, modelos, preferencias y resolución de streams
service/  reproducción Media3, descargas de proceso y servidor WiFi
```

Stack principal: Kotlin, Jetpack Compose/Material 3, Media3 ExoPlayer, Room, DataStore, OkHttp, Ktor y Coil.

## Créditos y servicios externos

La resolución de YouTube es una implementación Kotlin propia de BestiaPop sobre InnerTube. La selección y configuración de perfiles de cliente está basada en los perfiles publicados y mantenidos por [yt-dlp](https://github.com/yt-dlp/yt-dlp), en particular [`yt_dlp/extractor/youtube/_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py). BestiaPop no incluye ni ejecuta yt-dlp.

Los metadatos online pueden provenir de iTunes y Deezer; scrobbling y recomendaciones personales usan ListenBrainz.

## Licencia

[GNU Affero General Public License v3.0](LICENSE).
