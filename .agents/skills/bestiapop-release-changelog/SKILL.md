---
name: bestiapop-release-changelog
description: >-
  Changelog local (gitignored) de cambios user-facing desde el último APK
  BestiaPop, y notas de GitHub Release sin ruido de refactors. Usar al terminar
  features/fixes visibles, al pedir release/./release.sh, o cuando .cursorrules
  lo exija.
---

# BestiaPop — Changelog pendiente → notas de release

## Archivos (locales, no van a git)

| Archivo | Rol |
|---------|-----|
| `CHANGELOG.pending.md` (raíz del repo) | Diario de bullets **user-facing** desde el último APK |
| `CHANGELOG.release-notes.md` (raíz) | Borrador de notas para `./release.sh` (lo escribe el agente al publicar) |

Ambos están en `.gitignore`. No commitearlos.

Versión publicada: `version.properties` (`VERSION_NAME` / `VERSION_CODE`) + tag `v{VERSION_NAME}`.

## Cuándo anotar (obligatorio)

Tras un cambio **visible para quien usa la app** (feature, fix, mejora de UX/copy), **en el mismo trabajo** añadir 1–3 bullets bajo `## Pendiente` en `CHANGELOG.pending.md`.

### Incluir

- Features nuevas (Identificar, descargas, radio, Para Ti, WiFi, temas, update in-app…)
- Bugs que el usuario notaba
- Cambios de comportamiento en pantallas/acciones existentes
- Mejoras de precisión/resultados que se sienten en uso (p. ej. mejores matches al identificar)

### Excluir (el usuario no lo necesita)

- Refactors, renames, compresión semántica, “living docs” / skills
- Tests, CI, proguard, bump interno sin efecto UX
- Detalle de APIs/clases (`proposeSongIdentity`, Room, codecs…)
- Telemetría/Crashlytics keys, detalles de implementación

**Regla:** si no se notaría en la app sin mirar el código, no va al changelog.

### Estilo de bullet

- Español, tono amigo/beta (como la UI)
- Qué gana el usuario, no el path del archivo
- Corto (una línea; dos si hace falta)
- Mal: `MusicRepository.fetchListenBrainzIdentifyTrack edge re-rank`
- Bien: `Identificar: con ListenBrainz activo, afina canciones dudosas cuando el catálogo no alcanza`

Si el archivo no existe, crearlo con la plantilla de abajo y rellenar `Último APK` desde `version.properties` + último tag `v*`.

## Al hacer un release

Cuando el usuario pida publicar / `./release.sh`:

1. Leer `CHANGELOG.pending.md` → sección `## Pendiente`.
2. Filtrar otra vez (solo user-facing). Si solo había refactors desde el último APK, notas mínimas honestas (“Mantenimiento interno”) o lo que sí quede pendiente.
3. Escribir `CHANGELOG.release-notes.md`:

```markdown
BestiaPop {NEXT_VERSION_NAME}

- …bullets user-facing…

versionCode: {NEXT_VERSION_CODE}
```

`versionCode` debe coincidir con el que va a publicar `release.sh` (actual + 1 si hay bump). Si falta la línea el script la agrega; si está y **no** coincide, aborta antes de compilar (`--dry-run` ya lo muestra). Ese es el caso típico de reusar un `CHANGELOG.release-notes.md` viejo: con el número equivocado, los usuarios de la versión anterior nunca verían la actualización.

4. Correr (o indicar) `./release.sh --notes-file CHANGELOG.release-notes.md` (u opciones que pida el usuario). Si no hay `--notes` / `--notes-file`, `release.sh` usa `CHANGELOG.release-notes.md` cuando existe.
5. Tras release **exitoso**:
   - Actualizar cabecera `Último APK publicado` al nuevo `VERSION_NAME` / `VERSION_CODE` / tag
   - Vaciar `## Pendiente` (dejar el heading + comentario de plantilla)
   - Opcional: mover los bullets a `## Historial local` bajo `### {VERSION_NAME}`
   - Borrar o vaciar `CHANGELOG.release-notes.md` (evitar reusar notas viejas)

No inventar features que no estén en el pending ni en el diff real.

## Plantilla `CHANGELOG.pending.md`

```markdown
# BestiaPop — changelog pendiente (local)

> No versionar. Ver skill `bestiapop-release-changelog`.

Último APK publicado: **VERSION_NAME** (versionCode N, tag `vVERSION_NAME`)

## Pendiente (próximo release)

<!-- Bullets user-facing desde el último APK. Sin refactors. -->

-

## Historial local

### VERSION_NAME
- …
```

## Relacionado

- Publicar APK: `./release.sh` (`version.properties`, GitHub Releases)
- Features/invariantes: `bestiapop-features`
