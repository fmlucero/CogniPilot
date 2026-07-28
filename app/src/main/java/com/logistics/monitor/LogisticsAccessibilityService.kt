package com.logistics.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.logistics.monitor.data.MeRepository
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Núcleo del Monitor de Logística.
 *
 * Flujo:
 *  1. Cuando el package objetivo entra en primer plano (TYPE_WINDOW_STATE_CHANGED)
 *     → Overlay de ADVERTENCIA (cartel naranja).
 *  2. Cuando el usuario CLICKEA un elemento cuyo texto/descripción contiene
 *     una keyword de escaneo → Overlay de BLOQUEO (cartel rojo).
 *  3. Cuando el package objetivo sale a segundo plano → se cierran ambos
 *     overlays y se resetea el estado.
 *
 * Importante: NO escaneamos toda la jerarquía de vistas en cada cambio de
 * contenido — esa estrategia provoca falsos positivos cuando la app tiene
 * un botón "Escanear" visible permanentemente (FAB, barra inferior, etc.).
 */
class LogisticsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LogisticsAccessSvc"
        const val TARGET_PACKAGE = "com.mercadoenvios.logistics"

        /** Palabras clave que indican que el click es para iniciar un escaneo QR */
        private val QR_KEYWORDS = listOf(
            "escanear", "scan", "qr", "código", "codigo", "scanner",
            "cámara", "camara", "capturar", "leer", "barcode"
        )

        var isServiceConnected = false
            private set

        @Volatile
        private var instance: LogisticsAccessibilityService? = null

        /**
         * Llamado desde el ForegroundService al iniciarse: limpia overlays y
         * resetea flags para que el cartel naranja vuelva a salir cuando el
         * usuario entre nuevamente a Envíos SC Pack.
         */
        fun resetMonitorState() {
            instance?.resetState()
        }

        /** HU-09 — accessor para que el ParadaProximityWatcher dispare nudges
         *  vía el OverlayManager del AAS. Null si el servicio no está activo. */
        fun currentInstance(): LogisticsAccessibilityService? = instance

        /**
         * Llamado desde ScheduleMessagingService cuando llega un nuevo horario.
         * Fuerza una re-evaluación inmediata de los overlays si la app está en pantalla.
         */
        fun reevaluateCurrentState() {
            instance?.reevaluateState()
        }

        /**
         * Llamado desde MainActivity al togglear "Modo global". Reconfigura
         * serviceInfo.packageNames en runtime para escuchar todas las apps
         * (null) o solo SC Pack.
         */
        fun applyGlobalMode(enabled: Boolean) {
            instance?.configurePackageFilter(enabled)
        }

        /** HU-59 — llamado desde MainActivity al iniciar/salir del modo kiosko. */
        fun applyKioskoMode(enabled: Boolean) {
            instance?.onKioskoModeChanged(enabled)
        }

        /** Modo exploración — llamado desde MainActivity al togglear la captura
         *  de estructura de SC Pack. El estado se lee del repo en cada evento;
         *  esto sólo cancela capturas pendientes al desactivar y loguea. */
        fun applyCaptureMode(enabled: Boolean) {
            instance?.onCaptureModeChanged(enabled)
        }

        /**
         * Fix I-28 — llamado desde MainActivity.onResume(). Si el usuario está
         * viendo NUESTRA propia app, ningún overlay de bloqueo del work-app debe
         * quedar pegado encima. El filtrado por evento de accesibilidad trata a
         * nuestro propio package como "transient" y NO disparaba resetState(),
         * así que un overlay mostrado para Envíos SC Pack quedaba "congelando"
         * CogniPilot al volver a ella. Esto lo limpia de forma determinística.
         * No toca el overlay kiosko (ese tiene su propio ciclo de vida).
         */
        fun clearOverlaysForOwnApp() {
            instance?.resetState()
        }
    }

    private lateinit var overlayManager: OverlayManager
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var globalModeRepository: GlobalModeRepository
    private lateinit var kioskoModeRepository: KioskoModeRepository
    private lateinit var captureModeRepository: CaptureModeRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    // Modo exploración (piloto): captura la estructura de las pantallas de SC
    // Pack con dedup por huella. Debounce para esperar a que la pantalla se
    // estabilice (listas que cargan async) antes de recorrer el árbol.
    private val screenCapturer = ScreenStructureCapturer()
    private val CAPTURE_DEBOUNCE_MS = 1_200L
    @Volatile private var lastTargetActivity: String? = null
    private val captureRunnable = Runnable { runScreenCapture() }

    // HU-04/HU-42 — refresco de reglas al entrar/navegar la app de trabajo. Sin
    // esto, el GeofenceCache/AccesoOperativoEnforcer quedaban con las reglas de
    // la última vez que CogniPilot estuvo en foreground: si el supervisor cambia
    // una regla desde el panel mientras el repartidor está en Envíos, no se
    // aplicaba hasta volver a abrir CogniPilot (ver I-33).
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meRepository by lazy { MeRepository(this) }
    @Volatile private var lastRuleSyncAt = 0L
    @Volatile private var ruleSyncInFlight = false
    private val RULE_SYNC_DEBOUNCE_MS = 8_000L

    private var warningShown = false
    private var blockingShown = false
    private var targetAppActive = false

    // Debounce de global_app_opened por package (evita spam en navegación interna)
    private val lastGlobalOpenPerPkg = mutableMapOf<String, Long>()
    private val GLOBAL_OPEN_DEBOUNCE_MS = 2_000L
    private val MAX_GLOBAL_TEXTS = 8

    // Debounce del par (app_opened + warning_shown) — Android emite múltiples
    // WINDOW_STATE_CHANGED al abrir SC Pack (activity main, dialog, splash, etc),
    // y entre transiciones el resetState() puede borrar el flag y disparar otra
    // vez. Con este debounce, no emitimos los mismos eventos por <X seg.
    private var lastTargetOpenedAt: Long = 0L
    private val TARGET_OPENED_DEBOUNCE_MS = 5_000L

    // HU-54 — enforcement continuo de reglas acceso_operativo (modo app_trabajo).
    // Un ticker re-evalúa cada ENFORCE_INTERVAL mientras la app de trabajo está
    // en primer plano (cubre que el repartidor salga de la zona o cruce el
    // horario sin reabrir la app). El overlay/reporte se debouncen para no
    // spamear, pero el envío a Home se hace en cada detección.
    private val ENFORCE_INTERVAL_MS = 15_000L
    private val ACCESO_BLOCK_DEBOUNCE_MS = 8_000L
    private var lastAccesoBlockAt: Long = 0L
    private val enforceRunnable = object : Runnable {
        override fun run() {
            try {
                val active = rootInActiveWindow?.packageName?.toString()
                if (active == TARGET_PACKAGE) enforceAccesoOperativo()
            } catch (e: Exception) {
                Log.w(TAG, "ticker acceso_operativo: ${e.message}")
            } finally {
                mainHandler.postDelayed(this, ENFORCE_INTERVAL_MS)
            }
        }
    }

    // HU-59 — ticker del modo kiosko: re-evalúa zona/horario/permisos cada
    // KIOSKO_INTERVAL mientras el modo está activo, y muestra/quita el overlay
    // full-screen de bloqueo. Corre independiente de qué app esté en foreground.
    private val KIOSKO_INTERVAL_MS = 5_000L
    @Volatile private var kioskoEnabled = false
    private val kioskoRunnable = object : Runnable {
        override fun run() {
            try {
                evaluateKioskoAndRender()
            } catch (e: Exception) {
                Log.w(TAG, "ticker kiosko: ${e.message}")
            } finally {
                if (kioskoEnabled) mainHandler.postDelayed(this, KIOSKO_INTERVAL_MS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
        scheduleRepository = ScheduleRepository(this)
        globalModeRepository = GlobalModeRepository(this)
        kioskoModeRepository = KioskoModeRepository(this)
        captureModeRepository = CaptureModeRepository(this)
        kioskoEnabled = kioskoModeRepository.isEnabled()
        isServiceConnected = true
        instance = this

        Log.i(TAG, "✅ Servicio de accesibilidad CONECTADO")
        mainHandler.post {
            Toast.makeText(this, "🟢 Monitor Logística ACTIVO", Toast.LENGTH_SHORT).show()
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                // Modo exploración: cambios de contenido (listas que cargan,
                // scroll) para capturar la ruta completa. El filtrado por
                // package + debounce + dedup evita el flood.
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            // Siempre null: el OS nos manda eventos de TODAS las apps. Filtramos
            // por package en código (`onAccessibilityEvent`). Esto evita la
            // quirk de Android donde cambiar packageNames en runtime requiere
            // desactivar/reactivar el servicio para tomar efecto.
            packageNames = null
        }
        serviceInfo = info
        Log.i(TAG, "📡 packageNames=null (filtro en código). Modo global: ${globalModeRepository.isEnabled()}")

        // HU-54 — arrancar el ticker de enforcement de acceso operativo.
        mainHandler.postDelayed(enforceRunnable, ENFORCE_INTERVAL_MS)
        // HU-59 — si la jornada kiosko quedó activa, retomar el bloqueo.
        if (kioskoEnabled) {
            mainHandler.post(kioskoRunnable)
        }
    }

    /**
     * Llamado al togglear "Modo global". Como el filtrado ahora es por código,
     * no hay que reconfigurar serviceInfo — el cambio se aplica al próximo
     * evento. Solo logueamos.
     */
    private fun configurePackageFilter(enabled: Boolean) {
        Log.i(TAG, "🌐 Modo global ${if (enabled) "ACTIVADO (todas las apps)" else "DESACTIVADO (solo SC Pack)"}")
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Servicio interrumpido")
        isServiceConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(enforceRunnable)
        mainHandler.removeCallbacks(kioskoRunnable)
        mainHandler.removeCallbacks(captureRunnable)
        if (::overlayManager.isInitialized) {
            overlayManager.removeAllOverlays()
            overlayManager.removeKioskoOverlay()
        }
        serviceScope.cancel()
        isServiceConnected = false
        instance = null
        Log.i(TAG, "🔴 Servicio destruido")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HU-59 — Modo kiosko de jornada
    // ─────────────────────────────────────────────────────────────────────────

    /** Llamado vía companion cuando el usuario inicia/sale del modo kiosko. */
    private fun onKioskoModeChanged(enabled: Boolean) {
        kioskoEnabled = enabled
        mainHandler.removeCallbacks(kioskoRunnable)
        if (enabled) {
            Log.i(TAG, "🔒 Modo kiosko ACTIVADO")
            mainHandler.post(kioskoRunnable)   // evalúa y muestra ya mismo
        } else {
            Log.i(TAG, "🔓 Modo kiosko DESACTIVADO")
            mainHandler.post { overlayManager.removeKioskoOverlay() }
        }
    }

    /**
     * Evalúa zona + horario (reglas kiosko) + permisos y muestra/actualiza o
     * quita el overlay de bloqueo. El desbloqueo requiere las tres cosas.
     */
    private fun evaluateKioskoAndRender() {
        if (!kioskoEnabled) {
            mainHandler.post { overlayManager.removeKioskoOverlay() }
            return
        }
        val ev = AccesoOperativoEnforcer.evaluateKiosko()
        if (!ev.hasRule) {
            // La empresa no tiene reglas kiosko (o se desactivaron) → sin bloqueo.
            mainHandler.post { overlayManager.removeKioskoOverlay() }
            return
        }
        val permisosOk = LocationReporter.hasPermission(this) && LogisticsMonitoringService.isRunning
        val unlocked = ev.zonaOk && ev.horarioOk && permisosOk

        val detail = buildString {
            if (ev.detail.isNotBlank()) append(ev.detail)
            if (!permisosOk) {
                if (isNotEmpty()) append(" · ")
                append("activá ubicación y el monitor")
            }
        }

        mainHandler.post {
            if (unlocked) {
                if (overlayManager.isKioskoShowing()) {
                    overlayManager.removeKioskoOverlay()
                    Toast.makeText(this, "✅ Jornada habilitada — ya podés trabajar", Toast.LENGTH_LONG).show()
                }
            } else {
                overlayManager.showOrUpdateKioskoOverlay(
                    zonaOk = ev.zonaOk,
                    horarioOk = ev.horarioOk,
                    permisosOk = permisosOk,
                    detail = detail,
                    onExit = { disableKioskoFromOverlay() },
                )
            }
        }
    }

    /**
     * I-33 — Refresca reglas (y ruta) desde el back al entrar/navegar la app de
     * trabajo, para que el supervisor pueda cambiar una regla en el panel y se
     * aplique sin que el repartidor reabra CogniPilot. `syncFromBackend` repuebla
     * GeofenceCache + AccesoOperativoEnforcer + ParadaProximityWatcher. Con
     * debounce para no spamear en la navegación interna de la app de trabajo;
     * tras un sync exitoso re-evalúa acceso_operativo (por si una regla nueva
     * debe echar al repartidor ya mismo).
     */
    private fun syncRulesOnWorkAppEnter() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (ruleSyncInFlight || now - lastRuleSyncAt < RULE_SYNC_DEBOUNCE_MS) return
        ruleSyncInFlight = true
        lastRuleSyncAt = now
        serviceScope.launch {
            try {
                val res = meRepository.syncFromBackend()
                if (res.reglasOk) {
                    Log.i(TAG, "🔄 Reglas refrescadas al entrar a la app de trabajo")
                    // Re-evaluar acceso_operativo con las reglas frescas (geofence
                    // de escaneo se evalúa en el próximo click, no hace falta acá).
                    mainHandler.post {
                        if (rootInActiveWindow?.packageName?.toString() == TARGET_PACKAGE) {
                            enforceAccesoOperativo()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync de reglas falló: ${e.message}")
            } finally {
                ruleSyncInFlight = false
            }
        }
    }

    /** Salida manual desde el botón del overlay kiosko. */
    private fun disableKioskoFromOverlay() {
        kioskoModeRepository.setEnabled(false)
        kioskoEnabled = false
        mainHandler.removeCallbacks(kioskoRunnable)
        overlayManager.removeKioskoOverlay()
        Toast.makeText(this, "Saliste del modo jornada", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evento principal
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // Modo global: si la app es DISTINTA a SC Pack y el toggle está activo,
        // reportamos el evento "en gris" sin tocar overlays ni lógica de bloqueo.
        if (pkg != TARGET_PACKAGE) {
            if (globalModeRepository.isEnabled()) {
                handleGlobalEvent(event, pkg)
            }
            // Solo consideramos "SC Pack quedó atrás" si fue un cambio de ventana
            // REAL Y la ventana activa ya no es SC Pack. Sin esto, nuestro propio
            // overlay (cuando se muestra) o eventos de la status bar / IME
            // resetean el estado y rompen el flujo del cartel rojo.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && targetAppActive) {
                val activePkg = rootInActiveWindow?.packageName?.toString()
                val isTransient = activePkg == null ||
                    activePkg == TARGET_PACKAGE ||
                    activePkg == packageName ||
                    activePkg == "com.android.systemui" ||
                    activePkg.contains("inputmethod")
                if (!isTransient) {
                    Log.d(TAG, "SC Pack en segundo plano (active=$activePkg) — reseteando")
                    resetState()
                }
            }
            return
        }

        // ── Desde acá: pkg == TARGET_PACKAGE ──

        val et = event.eventType

        // Modo exploración: capturar la estructura de la pantalla (debounced +
        // dedup) en cambios de pantalla o de contenido. Recordamos el nombre de
        // la Activity del último WINDOW_STATE_CHANGED para etiquetar la captura
        // (el árbol raíz no lo expone de forma confiable).
        if (et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.substringAfterLast('.')?.let { lastTargetActivity = it }
        }
        if (captureModeRepository.isEnabled() &&
            (et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            scheduleScreenCapture()
        }
        // Los cambios de contenido no participan del flujo legacy de
        // reglas/overlays (ese se maneja en STATE_CHANGED y VIEW_CLICKED).
        if (et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // I-33 — al entrar/navegar la app de trabajo, refrescar reglas desde el
        // back (con debounce) para aplicar cambios hechos en el panel sin reabrir
        // CogniPilot. Es async: la primera evaluación puede usar cache, pero el
        // sync llega en <1s y el click de escaneo / el ticker posterior ya usan
        // las reglas frescas.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            syncRulesOnWorkAppEnter()
        }

        // HU-54 — el enforcement de acceso_operativo (modo app_trabajo) tiene
        // prioridad: si la regla de acceso falla (fuera de geocerca/horario),
        // echamos al repartidor a Home + overlay y cortamos acá, sin entrar al
        // flujo legacy de horario/escaneo.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (enforceAccesoOperativo()) return
        }

        // HU-42 — Los clics de escaneo se evalúan SIEMPRE, independientemente del
        // horario. La geocerca es una regla espacial: escanear fuera de la zona se
        // bloquea aunque el horario sea permitido (y la ventana horaria bloquea
        // aunque estés dentro de la zona). Son reglas independientes; quien las
        // combina para decidir es onQRScanDetected.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            checkClickedNode(event)
            return
        }

        // ── TYPE_WINDOW_STATE_CHANGED → cartel de horario (regla ventana_horaria) ──
        val snapshot = scheduleRepository.load()
        // Si hay restricción remota (enabled=true), bloquea fuera de horario; si
        // no, manda el switch local del monitor.
        val outOfSchedule = if (snapshot.enabled) {
            !snapshot.isNowInPermittedRange()
        } else {
            LogisticsMonitoringService.isRunning
        }

        if (!outOfSchedule) {
            if (targetAppActive || warningShown || blockingShown) resetState()
            return
        }

        if (!targetAppActive) {
            targetAppActive = true
            onTargetAppOpened(event)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modo global: eventos de apps externas a SC Pack
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleGlobalEvent(event: AccessibilityEvent, pkg: String) {
        // No reportar nuestro propio app ni systemui/IME — ruido sin valor para la demo
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod")) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = System.currentTimeMillis()
                val last = lastGlobalOpenPerPkg[pkg] ?: 0L
                if (now - last < GLOBAL_OPEN_DEBOUNCE_MS) return
                lastGlobalOpenPerPkg[pkg] = now

                val screenName = event.className?.toString()?.substringAfterLast('.')
                val texts = mutableListOf<String>()
                rootInActiveWindow?.let { collectVisibleTexts(it, texts, MAX_GLOBAL_TEXTS) }

                Log.i(TAG, "🌐 [GLOBAL] App abierta: $pkg ($screenName) — ${texts.size} textos")
                EventReporter.report(
                    this,
                    EventReporter.TYPE_GLOBAL_APP_OPENED,
                    appPackage = pkg,
                    screenName = screenName,
                    screenText = texts.takeIf { it.isNotEmpty() },
                )
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val texts = mutableListOf<String>()
                event.text?.forEach { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() }?.let(texts::add) }
                event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
                event.source?.let { node ->
                    collectClickTexts(node, texts)
                    node.recycle()
                }
                Log.i(TAG, "🌐 [GLOBAL] Click en $pkg — textos=$texts")
                EventReporter.report(
                    this,
                    EventReporter.TYPE_GLOBAL_CLICKED,
                    appPackage = pkg,
                    screenText = texts.distinct().take(MAX_GLOBAL_TEXTS).takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo, out: MutableList<String>, max: Int) {
        if (out.size >= max) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?.let { if (!out.contains(it)) out.add(it) }
        if (out.size >= max) return
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?.let { if (!out.contains(it)) out.add(it) }

        for (i in 0 until node.childCount) {
            if (out.size >= max) return
            node.getChild(i)?.let { child ->
                collectVisibleTexts(child, out, max)
                child.recycle()
            }
        }
    }

    private fun collectClickTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { if (!out.contains(it)) out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { if (!out.contains(it)) out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectClickTexts(child, out)
                child.recycle()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cartel naranja: app abierta
    // ─────────────────────────────────────────────────────────────────────────

    private fun onTargetAppOpened(event: AccessibilityEvent) {
        if (warningShown) return
        // Debounce: si el AAS ya disparó el par app_opened+warning_shown hace
        // <X segundos, no lo volvemos a disparar aunque el state se haya
        // resetado por una transición intermedia. Evita los duplicados que
        // aparecían con 1-2 seg de diferencia al abrir SC Pack.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTargetOpenedAt < TARGET_OPENED_DEBOUNCE_MS) {
            warningShown = true
            return
        }
        lastTargetOpenedAt = now
        warningShown = true

        val screenName = event.className?.toString()?.substringAfterLast('.') ?: "App"
        Log.i(TAG, "🚚 ENVÍOS SC PACK ABIERTO — pantalla: $screenName")

        val snap = scheduleRepository.load()
        val inSchedule = if (snap.enabled) snap.isNowInPermittedRange() else null
        EventReporter.report(this, EventReporter.TYPE_APP_OPENED, screenName = screenName, inSchedule = inSchedule)
        EventReporter.report(this, EventReporter.TYPE_WARNING_SHOWN, screenName = screenName, inSchedule = inSchedule)

        mainHandler.post {
            overlayManager.showWarningOverlay(
                title = "🚚 ENVÍOS SC PACK ABIERTO",
                message = "Pantalla: $screenName\n\nRecordá respetar los horarios programados.\nEsperá la hora correcta antes de escanear.",
                onDismiss = {
                    Log.i(TAG, "Overlay advertencia cerrado por usuario")
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cartel rojo: click en botón de escaneo
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkClickedNode(event: AccessibilityEvent) {
        if (blockingShown) return

        val texts = mutableListOf<String>()

        // Texto que el evento ya trae (lo más confiable)
        event.text?.forEach { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() }?.let(texts::add) }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)

        // Inspeccionar el nodo clickeado (a veces el click es en un container y
        // el texto vive en un hijo, ej: LinearLayout con TextView "Escanear")
        event.source?.let { node ->
            collectTexts(node, texts)
            node.recycle()
        }

        if (texts.isEmpty()) return

        // ── Colecta por parada (SC Pack) ──
        // El botón real de colecta se llama exactamente "Colectar" (distinto de
        // "No pude colectar"). Es la acción a controlar: no colectar fuera de la
        // ventana horaria de ESA parada (el problema de "llegar antes"). Tiene
        // prioridad sobre el detector genérico de escaneo.
        val isColectar = texts.any { it.trim().trimEnd('.').equals("Colectar", ignoreCase = true) }
        if (isColectar) {
            onColectarClicked()
            return
        }

        val matched = texts.flatMap { text ->
            QR_KEYWORDS.filter { kw -> text.contains(kw, ignoreCase = true) }
        }.distinct()

        if (matched.isNotEmpty()) {
            Log.i(TAG, "🚨 Click con keywords QR=$matched, textos=$texts")
            onQRScanDetected(texts, matched)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bloqueo por parada: colectar fuera de la ventana horaria
    // ─────────────────────────────────────────────────────────────────────────

    private data class StopWindow(
        val parada: String?,     // "Parada 1"
        val direccion: String?,  // "Calle Bulnes 1776"
        val desde: LocalTime,
        val hasta: LocalTime,
        val ventanaText: String, // "14:35 – 15:05"
    )

    // "14:35hs a 15:05hs" | "14:35 - 15:05" | "14:35 a 15:05"
    private val ventanaRegex =
        Regex("""(\d{1,2}):(\d{2})\s*(?:hs)?\s*(?:a|-|–|—)\s*(\d{1,2}):(\d{2})""")
    private val paradaRegex = Regex("""^Parada\s+\d+""", RegexOption.IGNORE_CASE)

    /**
     * Al tocar "Colectar" en la pantalla de una parada, lee de la MISMA pantalla
     * el número de parada y su ventana horaria, y bloquea si la hora actual está
     * fuera de la ventana (antes o después). No depende de la ruta importada: la
     * ventana viene en pantalla (`flux_components_row_paragraph_text`).
     */
    private fun onColectarClicked() {
        val info = try {
            rootInActiveWindow?.let { root ->
                try { extractStopWindow(root) } finally { root.recycle() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lectura de ventana de parada falló: ${e.message}"); null
        }

        if (info == null) {
            // Sin ventana legible en pantalla → fail-open (no bloqueamos a ciegas).
            Log.i(TAG, "Colectar sin ventana legible — no se bloquea")
            return
        }

        val now = LocalTime.now()
        val outside = now.isBefore(info.desde) || now.isAfter(info.hasta)
        val nowTxt = "%02d:%02d".format(now.hour, now.minute)
        Log.i(TAG, "🧾 Colectar ${info.parada} ventana=${info.ventanaText} ahora=$nowTxt outside=$outside")

        EventReporter.report(
            this,
            EventReporter.TYPE_SCAN_DETECTED,
            screenName = "${info.parada ?: "Parada"} · colecta ${info.ventanaText}",
            inSchedule = !outside,
        )

        if (!outside) return  // dentro de la ventana → permitido

        blockingShown = true
        val cuando = if (now.isBefore(info.desde)) "todavía no abrió" else "ya cerró"
        val paradaLbl = info.parada ?: "esta parada"
        val dir = info.direccion?.let { "\n$it" } ?: ""
        mainHandler.post {
            overlayManager.showBlockingOverlay(
                title = "🚫 COLECTA FUERA DE HORARIO",
                message = "$paradaLbl$dir\nVentana ${info.ventanaText} — son las $nowTxt ($cuando).\n\nNo deberías colectar fuera del horario de la parada.",
                onContinue = {
                    Log.w(TAG, "⚠️ Colecta fuera de ventana — usuario CONTINUÓ")
                    blockingShown = false
                    EventReporter.report(
                        this, EventReporter.TYPE_USER_CONTINUED,
                        screenName = "${info.parada ?: "Parada"} · colecta fuera de ventana",
                        inSchedule = false,
                    )
                    mainHandler.post {
                        Toast.makeText(this, "⚠️ Colecta permitida por el usuario", Toast.LENGTH_LONG).show()
                    }
                },
                onCancel = {
                    Log.i(TAG, "✅ Colecta fuera de ventana — usuario ACEPTÓ el bloqueo → Home")
                    blockingShown = false
                    EventReporter.report(
                        this, EventReporter.TYPE_USER_CANCELLED,
                        screenName = "${info.parada ?: "Parada"} · colecta fuera de ventana",
                        inSchedule = false,
                    )
                    mainHandler.post {
                        Toast.makeText(this, "✅ Colecta cancelada", Toast.LENGTH_SHORT).show()
                    }
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
            )
        }
    }

    /** Recorre la pantalla y extrae "Parada N" + su ventana horaria + dirección. */
    private fun extractStopWindow(root: AccessibilityNodeInfo): StopWindow? {
        val nodes = ArrayList<Pair<String, String>>(80)  // (viewId, texto)
        collectIdTexts(root, nodes, 0)

        var parada: String? = null
        var direccion: String? = null
        var match: MatchResult? = null
        for ((vid, t) in nodes) {
            if (parada == null && (vid.endsWith("toolbar_title") && paradaRegex.containsMatchIn(t)))
                parada = t.trim()
            if (direccion == null && (vid == "components_row_address_title" || vid == "listing_stops_row_title"))
                direccion = t.trim()
            if (match == null) ventanaRegex.find(t)?.let { match = it }
        }
        // Respaldo para "Parada N" si no vino por el viewId del toolbar.
        if (parada == null) parada = nodes.firstOrNull { paradaRegex.containsMatchIn(it.second) }?.second?.trim()
        val m = match ?: return null

        return try {
            val (h1, m1, h2, m2) = m.destructured
            val desde = LocalTime.of(h1.toInt(), m1.toInt())
            val hasta = LocalTime.of(h2.toInt(), m2.toInt())
            StopWindow(parada, direccion, desde, hasta,
                "%02d:%02d – %02d:%02d".format(desde.hour, desde.minute, hasta.hour, hasta.minute))
        } catch (_: Exception) { null }
    }

    private fun collectIdTexts(node: AccessibilityNodeInfo, out: MutableList<Pair<String, String>>, depth: Int) {
        if (out.size >= 160 || depth > 16) return
        val vid = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(vid to it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(vid to it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectIdTexts(child, out, depth + 1)
                child.recycle()
            }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
        node.hintText?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
        node.viewIdResourceName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }
            ?.let(result::add)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTexts(child, result)
                child.recycle()
            }
        }
    }

    /**
     * HU-42 — evaluar reglas geofence activas. Si el repartidor está fuera
     * del radio permitido, el overlay muestra distancia + radio. Si la
     * accion es "bloquear" y está fuera, el mensaje resalta que NO debe
     * continuar (la decisión final sigue siendo del repartidor: HU-08
     * dejó el flujo "Continuar igual / Cancelar" como UX no-coercitiva).
     */
    private fun buildScanOverlayContent(geo: GeofenceEvaluator.Result): Pair<String, String> {
        return when (geo) {
            is GeofenceEvaluator.Result.Outside -> {
                val verbo = if (geo.accion == "bloquear") "🚫 ESCANEO FUERA DE ZONA"
                            else "⚠️ ESCANEO FUERA DE ZONA"
                val nota = if (geo.accion == "bloquear")
                    "\n\n🚷 Regla \"${geo.ruleName}\" exige escanear cerca de la parada."
                else
                    "\n\n📍 Regla \"${geo.ruleName}\": estás escaneando lejos de la parada."
                verbo to "📡 Estás a ${"%.0f".format(geo.distanceM)}m de la parada más cercana (radio permitido: ${"%.0f".format(geo.radiusM)}m).$nota"
            }
            is GeofenceEvaluator.Result.Inside -> {
                "🚫 ESCANEO QR DETECTADO" to "✅ Estás dentro de la zona permitida por la regla \"${geo.ruleName}\".\nVerificá el horario antes de continuar."
            }
            else -> {
                "🚫 ESCANEO QR DETECTADO" to "⚠️ Verificá que el horario sea el correcto antes de escanear."
            }
        }
    }

    private fun onQRScanDetected(allTexts: List<String>, detectedKeywords: List<String>) {
        if (blockingShown) return

        // HU-42 — Decisión INDEPENDIENTE: la geocerca (espacial) y la ventana
        // horaria (temporal) son reglas separadas; cualquiera de las dos puede
        // bloquear el escaneo. Solo se permite si estás DENTRO de la zona Y en
        // horario permitido.
        val geo = GeofenceEvaluator.evaluateForScan()
        val geoOutside = geo is GeofenceEvaluator.Result.Outside
        val snap = scheduleRepository.load()
        val inSchedule = if (snap.enabled) snap.isNowInPermittedRange() else null
        val outOfSchedule = if (snap.enabled) {
            !snap.isNowInPermittedRange()
        } else {
            LogisticsMonitoringService.isRunning
        }

        // Telemetría: el intento de escaneo se reporta siempre.
        EventReporter.report(
            this,
            EventReporter.TYPE_SCAN_DETECTED,
            keywords = detectedKeywords,
            inSchedule = inSchedule,
        )

        if (!geoOutside && !outOfSchedule) {
            Log.i(TAG, "✅ Escaneo permitido — dentro de zona y en horario")
            return
        }

        blockingShown = true
        Log.i(TAG, "🚫 BLOQUEO escaneo — geoOutside=$geoOutside outOfSchedule=$outOfSchedule keywords=$detectedKeywords")

        val (title, message) = buildScanOverlayContent(geo)

        mainHandler.post {
            overlayManager.showBlockingOverlay(
                title = title,
                message = message,
                onContinue = {
                    Log.w(TAG, "⚠️ Usuario eligió CONTINUAR con el escaneo")
                    blockingShown = false
                    EventReporter.report(
                        this,
                        EventReporter.TYPE_USER_CONTINUED,
                        keywords = detectedKeywords,
                        inSchedule = inSchedule,
                    )
                    mainHandler.post {
                        Toast.makeText(this, "⚠️ Escaneo permitido por el usuario", Toast.LENGTH_LONG).show()
                    }
                },
                onCancel = {
                    Log.i(TAG, "✅ Usuario aceptó el bloqueo — mandando la app de trabajo a Home")
                    blockingShown = false
                    EventReporter.report(
                        this,
                        EventReporter.TYPE_USER_CANCELLED,
                        keywords = detectedKeywords,
                        inSchedule = inSchedule,
                    )
                    mainHandler.post {
                        Toast.makeText(this, "✅ Escaneo cancelado correctamente", Toast.LENGTH_SHORT).show()
                    }
                    // "Aceptar" acata el bloqueo: minimiza la app de trabajo mandando
                    // al launcher (mismo mecanismo que el enforcement de HU-54).
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun resetState() {
        targetAppActive = false
        warningShown = false
        blockingShown = false
        mainHandler.post { overlayManager.removeAllOverlays() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modo exploración — captura de estructura de SC Pack
    // ─────────────────────────────────────────────────────────────────────────

    private fun onCaptureModeChanged(enabled: Boolean) {
        if (!enabled) mainHandler.removeCallbacks(captureRunnable)
        Log.i(TAG, "🔎 Modo exploración ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
    }

    /** Re-agenda la captura; cada nuevo evento reinicia el debounce para que el
     *  árbol se recorra una vez que la pantalla dejó de cambiar. */
    private fun scheduleScreenCapture() {
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.postDelayed(captureRunnable, CAPTURE_DEBOUNCE_MS)
    }

    /** Recorre la pantalla actual y, si es novedosa, la reporta. Corre en el
     *  hilo principal (acceso a nodos de accesibilidad); el trabajo está acotado
     *  por los topes de nodos/líneas del capturador. La red la hace EventReporter
     *  fuera del main, con cola offline. */
    private fun runScreenCapture() {
        try {
            val root = rootInActiveWindow ?: return
            try {
                if (root.packageName?.toString() != TARGET_PACKAGE) return
                val cap = screenCapturer.capture(root, lastTargetActivity) ?: return
                Log.i(TAG, "🔎 [EXPLORA] ${cap.screenName} — ${cap.lines.size} líneas")
                EventReporter.report(
                    this,
                    EventReporter.TYPE_GLOBAL_APP_OPENED,
                    appPackage = TARGET_PACKAGE,
                    screenName = cap.screenName,
                    screenText = cap.lines,
                )
            } finally {
                root.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "captura de estructura falló: ${e.message}")
        }
    }

    /**
     * HU-54 — Enforcement estándar de las reglas `acceso_operativo` (modo
     * `app_trabajo`). Si el repartidor está fuera de la geocerca u horario
     * permitido, lo mandamos a Home (cierra la app de trabajo) y mostramos un
     * overlay explicando por qué. Devuelve true si tomó la acción de bloqueo.
     *
     * El envío a Home se hace en cada detección (para que no pueda quedarse en
     * la app); el overlay + el reporte de evento se debouncen para no spamear.
     */
    private fun enforceAccesoOperativo(): Boolean {
        val result = AccesoOperativoEnforcer.evaluate()
        if (result !is AccesoOperativoEnforcer.Result.Denied) return false

        Log.w(TAG, "🔒 ACCESO DENEGADO — regla='${result.ruleName}' (${result.reason}) → Home")

        // Limpiar los flags del flujo legacy SIN borrar overlays (el overlay de
        // acceso debe quedar visible sobre Home; resetState() los removería).
        targetAppActive = false
        warningShown = false
        blockingShown = false

        // Cerrar la app de trabajo mandando al launcher.
        performGlobalAction(GLOBAL_ACTION_HOME)

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAccesoBlockAt > ACCESO_BLOCK_DEBOUNCE_MS) {
            lastAccesoBlockAt = now
            EventReporter.report(
                this,
                EventReporter.TYPE_WARNING_SHOWN,
                screenName = "Acceso bloqueado: ${result.reason}",
                inSchedule = false,
            )
            mainHandler.post {
                overlayManager.showWarningOverlay(
                    title = "🔒 Acceso no permitido",
                    message = "No podés usar la app de trabajo en este momento.\n\n${result.reason}.\n\nRegla: \"${result.ruleName}\".",
                    onDismiss = { Log.i(TAG, "Overlay de acceso cerrado") },
                )
            }
        }
        return true
    }

    /** HU-09 — invocado por ParadaProximityWatcher cuando el repartidor entra
     *  a la geocerca de una parada. No interfiere con los overlays de horario
     *  (warning/blocking) — usa la variante `showNudgeOverlay` que vive
     *  arriba en la pantalla y se cierra solo. */
    fun showNudgeFromWatcher(title: String, message: String) {
        if (!::overlayManager.isInitialized) return
        mainHandler.post {
            overlayManager.showNudgeOverlay(title, message)
        }
    }

    private fun reevaluateState() {
        mainHandler.post {
            val root = rootInActiveWindow
            if (root?.packageName?.toString() == TARGET_PACKAGE) {
                val snapshot = scheduleRepository.load()
                val shouldBlock = if (snapshot.enabled) {
                    !snapshot.isNowInPermittedRange()
                } else {
                    LogisticsMonitoringService.isRunning
                }

                if (shouldBlock) {
                    targetAppActive = true
                    if (!warningShown && !blockingShown) {
                        warningShown = true
                        overlayManager.showWarningOverlay(
                            title = "🚚 ENVÍOS SC PACK ABIERTO",
                            message = "Restricción horaria actualizada. Esperá la hora correcta antes de escanear.",
                            onDismiss = { Log.i(TAG, "Overlay cerrado") }
                        )
                    }
                } else {
                    resetState()
                }
            }
        }
    }
}
