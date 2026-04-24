# Monitor de Logística — Kotlin

App Android nativa en Kotlin que previene escaneos QR anticipados en **Envíos SC Pack** (`com.mercadoenvios.logistics`).

## Cómo funciona

1. **AccessibilityService** escucha eventos SOLO de `com.mercadoenvios.logistics`
2. Cuando la app se abre → **Overlay 1** (advertencia naranja, 8 seg auto-cierre)
3. Cuando detecta texto/botón de escaneo en pantalla → **Overlay 2** (bloqueo rojo con opciones)
4. La detección de botones es **100% nativa** vía `AccessibilityNodeInfo` — sin OCR, sin cámara

## Build local (Android Studio)

1. Abrir `F:\Proys\LogisticsMonitorKotlin` en Android Studio
2. Sincronizar Gradle → Android Studio descarga el wrapper automáticamente
3. Run → seleccionar dispositivo → instalar

## Build CI (GitHub Actions)

Cada push a `main` o `develop` genera el APK automáticamente.
Descargarlo desde la pestaña **Actions → Artifacts**.

## Configuración en el dispositivo

1. Instalar APK (origen desconocido)
2. Abrir **Monitor Logística**
3. Paso 1: Configurar permiso overlay
4. Paso 2: Activar servicio de accesibilidad (buscar "Monitor Logística")
5. Paso 3 (opcional): Iniciar notificación persistente

## Estructura

```
app/src/main/
├── java/com/logistics/monitor/
│   ├── MainActivity.kt                  ← UI + setup de permisos
│   ├── LogisticsAccessibilityService.kt ← NÚCLEO: detección + overlays
│   ├── LogisticsMonitoringService.kt    ← ForegroundService + notificación
│   └── OverlayManager.kt               ← Sistema de ventanas superpuestas
├── res/
│   ├── layout/activity_main.xml
│   ├── layout/overlay_warning.xml      ← 1er cartel (naranja)
│   ├── layout/overlay_blocking.xml     ← 2do cartel (rojo)
│   └── xml/accessibility_service_config.xml
└── AndroidManifest.xml
```

## Keywords de detección QR

`escanear`, `scan`, `qr`, `código`, `scanner`, `cámara`, `capturar`, `leer`, `barcode`

(Configurables en `LogisticsAccessibilityService.kt` → `QR_KEYWORDS`)
