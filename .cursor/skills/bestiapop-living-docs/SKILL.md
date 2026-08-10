---
name: bestiapop-living-docs
description: >-
  Protocolo para mantener actualizados los skills de arquitectura, features e
  implementation-map de BestiaPop. Usar al terminar cambios que alteren capas,
  APIs, features o ubicaciones de código; también cuando .cursorrules lo exija.
---

# BestiaPop — Living docs

Los skills del proyecto son la **fuente de verdad viva** para el agente. El código manda; si diverge del skill, **actualizar el skill en el mismo cambio** (o inmediatamente después).

## Skills a mantener

| Skill | Path | Qué documenta |
|-------|------|----------------|
| `bestiapop-architecture` | `.cursor/skills/bestiapop-architecture/SKILL.md` | Capas, stack, flujos, invariantes |
| `bestiapop-features` | `.cursor/skills/bestiapop-features/SKILL.md` | Features + entry points |
| `bestiapop-implementation-map` | `.cursor/skills/bestiapop-implementation-map/SKILL.md` | Archivos/clases/funciones |
| `bestiapop-living-docs` | este archivo | Este protocolo |
| `bestiapop-release-changelog` | `.cursor/skills/bestiapop-release-changelog/SKILL.md` | Changelog local user-facing → notas de APK |

También alinear `.agents/AGENTS.md` si cambian los 4 principios históricos (colecciones, download YouTube, filtro biblioteca, portadas).

## Cuándo actualizar (obligatorio)

Actualizar **en el mismo PR/tarea** si el cambio:

- [ ] Añade/elimina/renombra package, capa o dependencia de arquitectura
- [ ] Cambia un invariante (colecciones, CDN YouTube, álbum vs playlist art, filesDir)
- [ ] Añade feature user-facing o cambia comportamiento esencial
- [ ] Crea/mueve/renombra archivo o API pública listada en el mapa
- [ ] Cambia esquema Room, servicios Android o navegación de `MainScreen`

## Cómo actualizar

1. **Leer** el skill afectado antes de editar código relacionado (si no está en contexto).
2. **Editar código**.
3. **Parchear el skill** con referencias **directas** (path + nombre de clase/función), no prosa vaga.
4. Preferir tablas path → símbolo. Quitar entradas obsoletas; no acumular “legacy” sin marcar.
5. Mantener cada `SKILL.md` **< 500 líneas** y conciso.
6. Si AGENTS.md queda desfasado respecto a features 1–4, sincronizar bullets.

## Formato de referencias

Preferido:

```markdown
| Acción | Entry point |
|--------|-------------|
| X | `ClassName.method` en `path/File.kt` |
```

Evitar: “ver el ViewModel” sin método; paths inventados; docs de APIs eliminadas.

## Checklist post-cambio

```
Living docs:
- [ ] architecture refleja capas/flujos actuales
- [ ] features lista invariantes + entry points nuevos/cambiados
- [ ] implementation-map tiene paths/símbolos correctos
- [ ] AGENTS.md alineado si tocaba principios 1–4
```

No hace falta commit de docs separado: incluirlos junto al cambio de código cuando el usuario pida commit.
