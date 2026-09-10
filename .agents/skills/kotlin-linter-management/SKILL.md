---
name: kotlin-linter-management
description: >-
  Guía y catálogo de patrones para ejecutar linters (Android Lint, ktlint) y
  resolver errores y advertencias de código en BestiaPop sin suprimir el linter
  (@SuppressLint/@Suppress), adaptando y modernizando APIs inestables o deprecadas.
---

# BestiaPop — Manejo de Linters y Calidad de Código Kotlin

Guía de referencia para diagnosticar, ejecutar y solucionar problemas reportados por las herramientas de linting en BestiaPop manteniendo la política estricta de **cero supresiones**.

## 1. Herramientas y Ejecución

| Linter | Alcance | Comando |
|---|---|---|
| **Android Lint** | APIs Android, manifest, recursos, extensiones KTX, seguridad | `./gradlew lintDebug` / `./gradlew lintRelease` |
| **ktlint** | Estilo oficial de Kotlin, indentación, espaciado, firmas, expresiones | `ktlint --editorconfig=.editorconfig <archivos>` |
| **Compilador Kotlin** | Tipos, compatibilidad, deprecaciones, overrides | `./gradlew compileDebugKotlin -Pkotlin.compiler.allWarningsAsErrors=true` |

> [!IMPORTANT]
> El binario oficial de **ktlint** proviene exclusivamente de la organización canónica: `https://github.com/ktlint/ktlint`. No usar repositorios obsoletos de terceros.

---

## 2. Principios de Resolución

1. **Nunca suprimir:** No agregar `@SuppressLint(...)`, `@Suppress(...)` ni `@OptIn(UnstableApi::class)` en código de dominio o componentes principales para silenciar advertencias.
2. **Resolver en la frontera adecuada:** Si una API de alto nivel es inestable o restringida, descender a la abstracción de nivel inferior que sea pública y estable.
3. **Guardas en tiempo de ejecución:** Validar versiones de Android con `Build.VERSION.SDK_INT` antes de leer constantes o invocar métodos disponibles a partir de cierta versión de API.

---

## 3. Catálogo de Errores Comunes y Soluciones Estructurales

### A. `RestrictedApi` (Ej. `ComponentActivity.dispatchKeyEvent`)
- **Problema:** Sobrescribir o invocar métodos anotados con `@RestrictTo(LIBRARY_GROUP_PREFIX)`.
- **Solución:** Usar los métodos públicos estándar del framework de Android correspondientes en `Activity`:
  - En lugar de `dispatchKeyEvent(event)`: sobreescribir `onKeyDown(keyCode, event)` y `onKeyUp(keyCode, event)`.

### B. `UnstableApi` (Media3 / ExoPlayer)
- **Problema:** Métodos marcados con `@UnstableApi` (ej. `MediaItem.Builder.setCustomCacheKey`).
- **Solución:** Configurar el comportamiento en la factoría correspondiente (`CacheDataSource.Factory.setCacheKeyFactory`) en lugar del modelo/DTO de reproducción.

### C. `InlinedApi` (Constantes de APIs superiores)
- **Problema:** Usar constantes de APIs nuevas (ej. `JobParameters.STOP_REASON_USER` de API 31) en código que corre en minSdk inferior (ej. API 26).
- **Solución:** Proteger el acceso evaluando `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` y retornar un valor seguro por defecto (ej. `0`) en versiones anteriores.

### D. `WakelockTimeout` (Android Lint)
- **Problema:** Invocar `wakeLock.acquire()` sin tiempo de expiración.
- **Solución:** Pasar siempre un timeout explícito: `wakeLock.acquire(WIFI_SYNC_WAKELOCK_TIMEOUT_MS)`.

### E. `UseKtx` (Android Lint)
- **Problema:** Llamar a métodos manuales cuando Android Core KTX provee extensiones oficiales.
- **Solución:**
  - `Color.parseColor("#...")` $\rightarrow$ `"#...".toColorInt()`.
  - Dibujo manual de `Drawable` en Canvas $\rightarrow$ `drawable.toBitmap(...)`.
  - `prefs.edit().put...().apply()` $\rightarrow$ `prefs.edit { put...(...) }`.

### F. `SwitchIntDef` y Deprecaciones
- **Problema:** Linters que exigen todas las constantes de un `@IntDef` en `when (level)`, mientras que algunas de esas constantes están deprecadas en el SDK moderno.
- **Solución:** Extraer la lógica a una función auxiliar desacoplada que acepte un `Int` primitivo sin la anotación `@IntDef`, evitando la colisión entre `SwitchIntDef` y `@Deprecated`.

### G. Reglas de Estilo ktlint
- **Indentación:** 4 espacios por nivel; 8 espacios para continuación de expresiones (o 4 espacios alineados dentro de bloques condicionales).
- **Cuerpo de función única:** Funciones de una sola instrucción de retorno deben usar `=`:
  ```kotlin
  private fun hasAudioPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { ... }
  ```
- **Bloques vacíos:** `catch (_: Exception) {}` en una sola línea.
- **Longitud de línea:** Máximo 120 caracteres; romper strings largas usando concatenación `+` con sangría.

---

## 4. Checklist de Verificación antes de Commit

1. [ ] ¿Compilación limpia con `./gradlew compileDebugKotlin`?
2. [ ] ¿`./gradlew lintDebug` reporta 0 errores y 0 warnings en el código fuente?
3. [ ] ¿ktlint pasa sin violaciones en los archivos modificados?
4. [ ] ¿Todos los tests unitarios (`./gradlew testDebugUnitTest`) siguen pasando?
5. [ ] ¿Se ejecutó `./gradlew --stop` y se limpiaron daemons residuales de Gradle/Kotlin?
