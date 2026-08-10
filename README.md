# BestiaPop

Reproductor de música para Android: biblioteca local, playlists, descarga y streaming online, radio de similares, ListenBrainz y sync por WiFi.

Sin anuncios ni Analytics. Los crashes de builds release van a Firebase Crashlytics.

**Requisito:** Android 8.0 (API 26) o superior.

Versión actual: ver `version.properties` (también en **Ajustes** dentro de la app).

---

## Qué hace

### Biblioteca

- Escanea la música del dispositivo al primer arranque.
- Importá carpetas (MP3, FLAC, M4A, WAV) desde el botón **Agregar**.
- Las descargas de la app viven en `Music/BestiaPop` y se reindexan si reinstalás.
- Vistas **Canciones / Álbumes / Artistas**, búsqueda, orden (título, artista, álbum, género, fecha) y lista plana o agrupada por álbum.
- Editá metadatos de canción o álbum, portadas y número de pista. La portada de álbum puede aplicarse solo al álbum o también a las canciones.
- **Identificar** busca coincidencias online y corrige tags. Los casos dudosos van a una cola de revisión (no se pisan a ciegas).

### Agregar música

Desde **Biblioteca → Agregar**:

| Pestaña | Uso |
|---------|-----|
| **Local** | Elegí una carpeta del teléfono o SD. |
| **Por enlace** | Pegá una URL de YouTube (u otro link de audio) y descargá. |
| **Catálogo** | Buscá canciones, álbumes o playlists (metadatos iTunes/Deezer). Reproducí en stream o descargá a la biblioteca. |

Las descargas se encolan (hasta 3 a la vez) y aparecen en la pestaña **Descargas**. Si el archivo ya existe: sobrescribir, guardar como nuevo o cancelar.

### Reproducción

- Play / shuffle / encolar de cualquier colección: biblioteca, álbum, artista, playlist o recomendaciones.
- Mini player persistente, Now Playing a pantalla completa y cola arrastrable.
- Mezclá temas locales con streams remotos (sin bajarlos primero).
- La cola y el último tema se recuerdan al cerrar la app. El autoplay al abrir está **apagado** por defecto (**Ajustes → Reproducción**).
- Notificación de reproducción en segundo plano (controles del sistema).

### Radio

Desde Now Playing o el menú de una canción → **Iniciar radio**.

- **Solo conocidos:** similares de tu biblioteca.
- **Solo nuevos:** descubrimientos online (stream).
- **Ambos:** mezcla de los dos.

Mantener pulsado el icono de radio cambia el modo o detiene la radio (no vacía la cola).

### Playlists y ListenBrainz

- Playlists locales: crear, portada propia (no pisa el artwork de las canciones), agregar/quitar temas.
- Con cuenta [ListenBrainz](https://listenbrainz.org):
  - Scrobbling (sin red se encola y se envía después).
  - Sección **Para Ti** (Daily/Weekly Jams y otras Discover) y **Recomendados**.
  - Reproducí la mezcla local + remoto; descargá un tema, importá la playlist o activá **Guardar al escuchar**.

Token: [listenbrainz.org/settings](https://listenbrainz.org/settings/).

### WiFi Sync

En la misma red WiFi, activá el servidor en la pestaña **WiFi Sync** y abrí la URL desde otro dispositivo para subir archivos. La app omite lo que ya tenés. Tras el upload intenta identificar tags; los conflictos se revisan igual que en la biblioteca.

### Temas y sonido

- Temas predefinidos o colores custom (**Ajustes → Temas**).
- Amplificar volumen por encima del 100% y balance L/R independientes (**Ajustes → Sonido**). El boost puede distorsionar temas ya muy masterizados.

### Actualizaciones

En builds **release**, la app consulta GitHub Releases al abrir (como máximo cada 12 h) y también desde **Ajustes → Buscar actualización**. Descarga el APK e invita a instalar (hace falta permiso de “instalar apps desconocidas”).

**Invitar amigos** comparte el link de la última release.

---

## Instalar (usuarios)

### APK desde GitHub Releases

1. Abrí la última release del repo (quien publique la app te pasa el link; en la app: **Ajustes → Invitar amigos**).
2. Descargá `BestiaPop-*.apk`.
3. En el teléfono: permití **Instalar apps desconocidas** para el navegador o el gestor de archivos.
4. Abrí el APK e instalá.
5. Concedé acceso a audio/notificaciones cuando la app lo pida.

Las actualizaciones siguientes pueden hacerse desde **Ajustes → Buscar actualización** o bajando el APK nuevo.

> En algunos fabricantes (p. ej. Motorola) el sideload restringe el segundo plano. Si al salir de la app se corta la música, revisá que BestiaPop no esté en “batería restringida” / “no optimizar”. Los installs vía `./install.sh` ajustan eso por ADB.

### Google Play

Hay un flujo de AAB (`./deploy-play.sh`) pensado como path legacy / testing. La distribución habitual entre amigos es el APK de GitHub.

---

## Cómo usar (recorrido rápido)

1. **Primera vez:** la app importa la música del dispositivo. Esperá el banner de progreso en Biblioteca.
2. **Reproducir:** tap en una canción, o Play / Shuffle en el header de álbum, artista o playlist.
3. **Agregar más:** FAB **Agregar** → carpeta, enlace o catálogo. Seguimiento en **Descargas**.
4. **Now Playing:** tap en la barra inferior. Ahí están cola, radio, volumen, ⋮ (ir al álbum/artista, editar, identificar, descargar si es stream).
5. **Playlists:** creá listas locales; si configuraste ListenBrainz, mirá **Para Ti** y **Recomendados**.
6. **PC → teléfono:** **WiFi Sync** → encendé el servidor → en la PC abrí `http://IP:puerto` y subí archivos.
7. **Ajustes:** temas, ListenBrainz, comportamiento de aleatorio/repetición al abrir, sonido, update e invitar.

El gesto **atrás** cierra un nivel (diálogo → Now Playing → detalle → tab). En la raíz de un tab, dos veces para salir; la música sigue.

---

## Compilar e instalar desde el código

### Requisitos

- JDK **17**
- Android SDK (compile/target **36**)
- `adb` y un teléfono con **Depuración USB** (o WiFi ADB)
- Gradle en el PATH, o Android Studio (el repo puede no traer `gradlew`)

### Arranque

```bash
git clone <este-repo>
cd bestia-pop
```

1. Firebase (Crashlytics en release): copiá `app/google-services.json` desde la [Firebase Console](https://console.firebase.google.com/). Si no está, `./install.sh` puede usar `app/google-services.json.example` para que compile; Crashlytics real no funciona con el example.
2. Opcional, misma firma debug/release (conserva datos al cambiar de variante):

```bash
cp keystore.properties.example keystore.properties
# editá passwords y generá el .jks, ver más abajo
```

3. Dispositivo conectado:

```bash
./install.sh              # debug, iteración diaria
./install.sh --release    # APK release (beta-like en el teléfono)
```

El script compila, instala (`adb install -r -d`), lanza `MainActivity` y, en sideload, relaja restricciones de segundo plano vía ADB.

Debug y release comparten `applicationId` (`com.bestiapop.android`). Con `keystore.properties`, ambas variantes usan el mismo certificado y el `-r` no borra Room/playlists.

### Tests

```bash
gradle :app:testDebugUnitTest
```

Los instrumentados van en `app/src/androidTest`.

---

Eso incrementa `version.properties`, arma el APK firmado + `latest.json`, crea el tag `v{VERSION_NAME}` y sube la release.

Opciones útiles: `--no-bump`, `--version-name X`, `--notes "…"`, `--no-upload`, `--dry-run`.

Link para compartir: `https://github.com/OWNER/REPO/releases/latest`.

---

## Estructura del proyecto

Módulo único `:app`, package `com.bestiapop.android`.

```
ui/          pantallas Compose, ViewModel, tema
domain/      use cases, radio, puerto IMusicRepository
data/        Room, red, stream YouTube, preferencias, modelos
service/     MusicService (Media3) · WebServerService (Ktor / WiFi)
```

| Pieza | Tecnología |
|-------|------------|
| UI | Jetpack Compose + Material 3 |
| Player | Media3 ExoPlayer + `MediaLibraryService` |
| DB | Room |
| Prefs | DataStore |
| Catálogo / audio | OkHttp · iTunes/Deezer · extractor YouTube |
| WiFi | Ktor embebido |
| Imágenes | Coil |

Scripts:

| Script | Para qué |
|--------|----------|
| `./install.sh` | Build + install en dispositivo |
| `./release.sh` | APK firmado → GitHub Releases |
| `./deploy-play.sh` | AAB Play Console (legacy) |

Versión: `version.properties` (`VERSION_CODE` / `VERSION_NAME`).

---

## Datos, privacidad y límites

- **Sin ads.** El permiso `AD_ID` se elimina del manifest mergeado. Analytics de Firebase está desactivado.
- Crashlytics solo en builds **no debug**.
- Playlists, overrides de álbum y preferencias viven en datos privados de la app: **se pierden al desinstalar** (en el futuro se guardará playlists y overrides de álbum para luego ser importados). Los audios en `Music/BestiaPop` suelen quedar en el almacenamiento y se reindexan al volver a instalar.
- Las URLs de stream de YouTube caducan; la app las re-extrae al reproducir o descargar. No se guardan en la base.
- Identificar / catálogo / radio / ListenBrainz necesitan red.

---

## Solución de problemas

| Síntoma | Qué probar |
|---------|------------|
| No instala `./install.sh` | `adb devices` debe listar el teléfono; USB debugging + autorización. |
| Firma incompatible al cambiar debug↔release | El script desinstala conservando datos (`-k`). Si el OEM lo rechaza, un uninstall total pierde playlists (no los MP3 en `Music/BestiaPop`). |
| La música se corta al salir | Quitar restricción de batería; o instalar con `./install.sh`. |
| Descarga 403 / stream falla | Red / YouTube; reintentá desde **Descargas**. La URL CDN vence rápido. |
| Update in-app no aparece | Build **release**, `GITHUB_REPOSITORY` configurado, y permiso de instalar paquetes. |
| Crashlytics vacío | Usá el `google-services.json` real del proyecto Firebase, no el example. |

---

## Licencia

[GNU Affero General Public License v3.0](LICENSE).
