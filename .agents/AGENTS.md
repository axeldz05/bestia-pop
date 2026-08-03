# Arquitectura y Principios del Proyecto BestiaPop (sofoapps)

## 1. Filosofía "Todo es una Playlist" (Colecciones Unificadas)
- Todas las agrupaciones de música (Lista de canciones, Álbumes, Artistas, Playlists personalizadas, Colas de reproducción) deben tratarse mediante un pipeline unificado de colecciones (`playCollection`, `shuffleCollection`, `enqueueCollection`).
- Las acciones de reproducción, mezcla y cola deben ser consistentes e intercambiables sin importar el origen de la colección.

## 2. Búsqueda Online y Descarga de Audio
- Las búsquedas de catálogo/metadatos pueden obtener información inicial de iTunes, Deezer u otros catálogos.
- La descarga real del stream de audio se resuelve extrayendo y procesando el equivalente de mejor calidad en YouTube (`YouTubeExtractor` / `downloadAndSaveOnlineTrack`).
- Se debe validar siempre la caducidad de los enlaces CDN (evitando errores HTTP 403) re-obteniendo el stream antes de iniciar la descarga.

## 3. Filtrado y Organización Multicampo
- La vista de biblioteca (`songsState`) debe admitir búsquedas filtrando dinámicamente por título, artista, álbum y género (`searchQuery`), así como ordenamiento por múltiples criterios (`SortOption`: Título, Artista, Álbum, Género, Fecha de adición).
- Permitir la alternancia de diseños (p. ej. vista plana vs separadores por álbum al estilo Tauon).

## 4. Personalización y Portadas (Diferencia Álbum vs Playlist)
- **Portadas de Álbum**: Cuando se actualiza o asigna la portada de un Álbum (`setAlbumArtwork`), **todas las canciones** pertenecientes a ese álbum deben heredar y usar la misma portada asignada.
- **Portadas de Playlist Personalizada**: La portada de una playlist personalizada (`Playlist.coverUri` / `PlaylistEntity.coverUri`) pertenece a la lista de reproducción como entidad independiente y no sobreescribe las portadas individuales de las canciones que contiene.
- Las imágenes seleccionadas localmente deben copiarse a almacenamiento interno persistente (`context.filesDir`) para evitar pérdida de permisos tras reiniciar la app.
