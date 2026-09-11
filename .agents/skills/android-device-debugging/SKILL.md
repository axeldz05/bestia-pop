---
name: android-device-debugging
description: >-
  Guía y catálogo de comandos para depurar en dispositivos Android físicos conectados por USB.
  Incluye consideraciones de energía (Doze/USB), diagnóstico de memoria (PSS/LMK/exit-info),
  inspección de servicios/notificaciones, y simulación de interacción (input/recents/swipe).
---

# BestiaPop — Depuración en Dispositivo Físico Conectado por USB

Guía práctica y comandos probados para investigar rendimiento, consumo de memoria, ciclo de vida de servicios y comportamiento en segundo plano directamente en un dispositivo Android real.

---

## 1. Consideraciones de Dispositivo Conectado por USB

> [!WARNING]
> **Android no suspende igual cuando está conectado a USB:**
> Al recibir alimentación por USB (`BATTERY_STATUS_CHARGING`), Android **desactiva el modo Doze profundo y relaja las restricciones de ahorro de energía**. Una tarea en segundo plano puede parecer funcionar sin problemas mientras el cable está conectado, pero ser terminada por el sistema minutos después de desconectarlo.

### Simular estado de batería desconectada sin desenchufar el cable:
```bash
# 1. Simular que el dispositivo está desconectado de la corriente
adb shell dumpsys battery unplug

# 2. Forzar entrada al modo Doze (inactividad profunda)
adb shell dumpsys deviceidle force-idle

# 3. Avanzar pasos de Doze
adb shell dumpsys deviceidle step

# 4. Apagar pantalla para simular reposo completo
adb shell input keyevent KEYCODE_POWER

# 5. RESTAURAR siempre el estado normal al terminar
adb shell dumpsys battery reset
```

---

## 2. Diagnóstico de Memoria y Rendimiento

### A. Inspección en vivo de memoria (PSS / RSS / Dalvik / Native)
```bash
# Resumen completo de memoria del proceso
adb shell dumpsys meminfo com.bestiapop.android

# Primeras 40 líneas (desglose de Java Heap, Native Heap, Gráficos y PSS Total)
adb shell dumpsys meminfo com.bestiapop.android | head -n 40
```
- **Total PSS:** Memoria real proporcional ocupada. Si sube de 250-300 MB en reproducción normal, investigar Bitmap allocations o colecciones sin acotar.
- **Native Heap vs Dalvik Heap:** Bitmaps en Android 8+ (API 26+) residen en Native Heap. Si Native Heap se dispara, hay Bitmaps sin reciclar o imágenes sin redimensionar.

### B. Historial de salida del proceso (Por qué se cerró la app)
Permite saber exactamente si el sistema cerró el proceso por memoria baja (LMK), actualización o petición del usuario:
```bash
adb shell dumpsys activity exit-info com.bestiapop.android
```
- `REASON_LOW_MEMORY (3)`: El Low Memory Killer cerró el proceso por falta de memoria RAM en el sistema.
- `REASON_OTHER (13) desc='MemAvailable'`: El sistema recortó el proceso para mantener un umbral mínimo de RAM disponible.
- `REASON_USER_REQUESTED (10)`: El usuario descartó la app de la lista de recientes (swipe-away).
- `REASON_CRASH (4)` o `REASON_ANR (6)`: Error fatal o falta de respuesta.

---

## 3. Inspección de Servicios, Foreground y Notificaciones

### A. Verificar si quedan servicios activos en segundo plano
```bash
adb shell dumpsys activity services com.bestiapop.android
```
- Si la app fue cerrada y no debe estar reproduciendo, la salida bajo `ACTIVITY MANAGER SERVICES` debe indicar `(nothing)` o no listar `MusicService` / `WebServerService`.

### B. Verificar notificaciones activas en el sistema
```bash
adb shell dumpsys notification --noredact | grep -i bestiapop
```
- Permite comprobar si la notificación multimedia o de descarga sigue anclada en el shade del sistema.

### C. Inspeccionar tareas activas y recientes (Overview / Recents)
```bash
adb shell dumpsys activity recents | grep -E "Recent #|bestiapop" -A 1 -B 1
```

---

## 4. Simulación de Interacción y Gestor de Tareas (Input / UI)

### A. Botones físicos y multimedia
```bash
# Botón Home (mandar app a segundo plano manteniendo proceso)
adb shell input keyevent KEYCODE_HOME

# Botón Recientes / Gestor de tareas
adb shell input keyevent KEYCODE_APP_SWITCH

# Controles multimedia
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
adb shell input keyevent KEYCODE_MEDIA_NEXT
adb shell input keyevent KEYCODE_MEDIA_PREVIOUS

# Subir / bajar volumen del sistema
adb shell input keyevent KEYCODE_VOLUME_UP
adb shell input keyevent KEYCODE_VOLUME_DOWN
```

### B. Lanzar la aplicación directamente
```bash
adb shell am start -n com.bestiapop.android/.MainActivity
```

### C. Simular descarte desde el gestor de tareas (Swipe-away)
Para verificar que el servicio se detiene y no resucita como zombie:
```bash
# 1. Abrir vista de aplicaciones recientes
adb shell input keyevent KEYCODE_APP_SWITCH

# 2. Deslizar la tarjeta de la app hacia arriba (ajustar coordenadas según resolución, ej. 1080x2400)
adb shell input swipe 540 1400 540 100 150

# 3. En caso de orientación horizontal (landscape):
adb shell input swipe 1193 450 1193 20 150
```

### D. Volcado de jerarquía visual (para ubicar vistas o textos en pantalla)
```bash
adb shell uiautomator dump /sdcard/screen.xml
adb shell cat /sdcard/screen.xml | grep -o 'content-desc="[^"]*"'
```

---

## 5. Logcat Filtrado y Efectivo

```bash
# Logs canónicos de BestiaPop (reproducción, ciclo de vida, runtime y caídas)
adb logcat -d -v time -s BestiaPop:V BestiaPopPlayback:V BestiaPopLifecycle:V BestiaPopRuntime:V BestiaPopService:V AndroidRuntime:E | tail -n 50

# Ver únicamente excepciones no capturadas (crashes)
adb logcat -d -v time -s AndroidRuntime:E

# Limpiar buffer de logcat antes de una prueba específica
adb logcat -c
```

---

## 6. Reglas Obligatorias al Operar con Dispositivo

1. **Instalación:** Desplegar siempre con `./install.sh` (o `./install.sh --release`).
2. **Preservación de datos:** Nunca ejecutar `adb uninstall`, `pm clear` ni borrar las bases de datos de Room o preferencias del usuario.
