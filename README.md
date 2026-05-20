# CogniPilot — App Android (Monitor Logística)

App Android nativa en Kotlin que asiste al repartidor durante su jornada, sobre
la app de logística **Envíos SC Pack** (`com.mercadoenvios.logistics`):

- **Bloqueo Poka-Yoke** (HU-08): si el repartidor escanea un paquete que no
  corresponde a la parada activa, un overlay rojo bloquea la confirmación.
- **Aviso suave** (HU-07): cartel amarillo no bloqueante al abrir la app de
  envíos avisando si está dentro o fuera del horario operativo permitido.
- **Sistema de notificaciones propio** (HU-18): polling controlado + SSE
  realtime cuando hay cambios de horario o reglas desde el panel del
  supervisor. **Sin Google FCM**.
- **Login con JWT** (HU-03): el repartidor inicia sesión y la app descarga su
  ruta del día + reglas activas, persiste en Room para uso offline.
- **Modo global** (opcional): además de la app de envíos, reportar eventos
  de cualquier app del cel para análisis del supervisor.

> Backend: [CogniPilotBack](https://github.com/fmlucero/CogniPilotBack)
> (FastAPI + Postgres + Redis). UI admin web:
> [CogniPilotRemote](https://github.com/fmlucero/CogniPilotRemote)
> (Next.js, solo lectura del back).

## Arquitectura interna

```
app/src/main/java/com/logistics/monitor/
├── ui/
│   ├── SplashActivity.kt              ← decide login vs main según sesión
│   └── LoginActivity.kt               ← form email+password (HU-03)
├── auth/
│   ├── TokenStorage.kt                ← EncryptedSharedPreferences
│   ├── AuthApi.kt                     ← /api/auth/{login,refresh,logout}
│   ├── AuthRepository.kt              ← singleton, orquesta login/logout
│   ├── AuthInterceptor.kt             ← Bearer en cada request
│   └── RefreshAuthenticator.kt        ← refresh transparente en 401
├── http/HttpClient.kt                 ← OkHttp singleton con auth
├── data/
│   ├── AppDatabase.kt                 ← Room v1
│   ├── entities/                      ← Ruta, Parada, Paquete, Regla
│   ├── dao/                           ← RutaDao, ReglaDao
│   ├── MeApi.kt                       ← /api/me/{ruta,reglas}
│   └── MeRepository.kt                ← sync remote-first + cache local
├── MainActivity.kt                    ← UI principal + estado del sistema
├── LogisticsAccessibilityService.kt   ← NÚCLEO: detección + overlays
├── LogisticsMonitoringService.kt      ← ForegroundService + notif persistente
├── OverlayManager.kt                  ← Ventanas superpuestas
├── EventReporter.kt                   ← POST /api/events con Bearer
├── ScheduleApi.kt                     ← GET /api/schedule (polling)
├── ScheduleSyncWorker.kt              ← WorkManager periódico cada 15 min
├── ScheduleRepository.kt              ← snapshot local del horario
├── ScheduleNotifier.kt                ← NotificationManager para cambios
├── RealtimeStreamClient.kt            ← SSE para realtime (foreground)
├── GlobalModeRepository.kt            ← toggle del modo global
└── DeviceIdProvider.kt                ← UUID hardware persistente
```

## Stack técnico

| Capa | Tech |
|---|---|
| Lenguaje | Kotlin 1.9.23 |
| AGP | 8.3.2 (minSdk 23, targetSdk 34) |
| Persistencia local | Room 2.6.1 (KSP) |
| Storage cifrado | androidx.security:security-crypto 1.1.0-alpha06 |
| HTTP | OkHttp 4.12.0 (cliente único con AuthInterceptor + RefreshAuthenticator) |
| Streaming | OkHttp SSE (`okhttp-sse`) para `/api/realtime/stream` |
| JSON | Moshi 1.15.1 (kotlin-codegen) |
| Background work | WorkManager 2.9.1 (sync cada 15 min) |
| Coroutines | kotlinx.coroutines 1.7.3 |
| UI | Material 1.12.0, ViewBinding, paleta CogniPilot (amarillo), Barlow Condensed |

## Flujo del repartidor

1. Abre la app → SplashActivity → decide:
   - Hay sesión → MainActivity
   - No hay sesión → LoginActivity
2. En LoginActivity ingresa `email + password`.
   - Login → POST `/api/auth/login` con `deviceUuid + modelo + osVersion + appVersion`
     (el back auto-registra el dispositivo)
   - Falla → mensaje genérico "Credenciales incorrectas" (no revela si el
     email existe)
3. Descarga ruta del día (`GET /api/me/ruta`) y reglas activas
   (`GET /api/me/reglas`) → Room
4. MainActivity muestra:
   - 👤 Nombre + email + rol
   - 📍 Ruta del día (nombre + fecha + nro de paradas)
   - 📋 Cantidad de reglas activas
   - Botones de configuración (overlay, accesibilidad, activar monitor)
5. Activar monitor → ForegroundService + AccessibilityService
6. Al abrir SC Pack → overlay amarillo con info de horario
7. Al detectar escaneo en pantalla → cruza el ID con la parada activa,
   bloquea con overlay rojo si no coincide
8. Cualquier cambio de horario desde el panel admin llega:
   - **<1s** vía SSE si la app está en foreground
   - **<30s** vía polling si la app está abierta
   - **≤15 min** vía WorkManager si la app está en background

## Build CI (GitHub Actions)

Cada push a `main` o `develop` genera el APK debug automáticamente. Descargarlo
desde **Actions → Artifacts → `logistics-monitor-debug-<sha>`**.

## Build local (Android Studio)

1. Abrir `F:\Proys\LogisticsMonitorKotlin` en Android Studio
2. Sincronizar Gradle → el wrapper se descarga automáticamente
3. Crear `local.properties` apuntando al SDK:
   ```properties
   sdk.dir=C:\\Users\\<tu-usuario>\\AppData\\Local\\Android\\Sdk
   ```
4. Run → seleccionar dispositivo → instalar

## Configuración en el dispositivo (primera vez)

1. Instalar APK (orígenes desconocidos habilitado para esta app)
2. Abrir **Monitor Logística**
3. Login con credenciales de repartidor
4. **Paso 1**: Configurar permiso overlay (`SYSTEM_ALERT_WINDOW`)
5. **Paso 2**: Activar servicio de accesibilidad — buscar "Monitor Logística"
6. **Paso 3**: Activar monitor (botón amarillo)

> **Modo global**: opcional. Al activarlo, además de SC Pack, se reportan
> eventos de cualquier app del celular al backend (gris en el panel admin).

## URL del backend

Configurada en `res/values/strings.xml` → `@string/backend_base_url`. Apunta al
**Cloudflare Quick Tunnel** del back en UM-Cloud. La URL es efímera y cambia
con cada arranque del tunnel; cuando rota hay que actualizar el string y
rebuildear el APK (la nueva URL se obtiene desde la VM con `~/cfurl.sh`).

> No hay endpoints públicos: TODO requiere Bearer JWT salvo `/api/auth/login`.

## Keywords de detección QR

Configurables en `LogisticsAccessibilityService.kt` → `QR_KEYWORDS`:

`escanear`, `scan`, `qr`, `código`, `scanner`, `cámara`, `capturar`, `leer`,
`barcode`

## Credenciales de prueba (entorno UM-Cloud)

```
fm.lucero@alumno.um.edu.ar              / repartidor123  (Logística Cuyo)
diego.morales@logisticacuyo.com.ar      / repartidor123
luciana.varela@logisticacuyo.com.ar     / repartidor123
javier.rios@transportesdelsur.com.ar    / repartidor123  (Transportes del Sur)
carla.guzman@transportesdelsur.com.ar   / repartidor123
```

## Logout

Botón "Cerrar sesión" en la pantalla principal: detiene el ForegroundService,
desconecta SSE, cancela WorkManager, borra tokens cifrados + Room + datos de
usuario. El `device_uuid` se preserva (identidad del hardware).
