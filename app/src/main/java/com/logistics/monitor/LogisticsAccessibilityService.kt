package com.logistics.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

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
    }

    private lateinit var overlayManager: OverlayManager
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var globalModeRepository: GlobalModeRepository
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
        scheduleRepository = ScheduleRepository(this)
        globalModeRepository = GlobalModeRepository(this)
        isServiceConnected = true
        instance = this

        Log.i(TAG, "✅ Servicio de accesibilidad CONECTADO")
        mainHandler.post {
            Toast.makeText(this, "🟢 Monitor Logística ACTIVO", Toast.LENGTH_SHORT).show()
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
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
        if (::overlayManager.isInitialized) overlayManager.removeAllOverlays()
        isServiceConnected = false
        instance = null
        Log.i(TAG, "🔴 Servicio destruido")
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

        // HU-54 — el enforcement de acceso_operativo (modo app_trabajo) tiene
        // prioridad: si la regla de acceso falla (fuera de geocerca/horario),
        // echamos al repartidor a Home + overlay y cortamos acá, sin entrar al
        // flujo legacy de horario/escaneo.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (enforceAccesoOperativo()) return
        }

        val snapshot = scheduleRepository.load()

        // Lógica de Prioridad:
        // 1. Si hay restricción remota (enabled=true), ella manda (bloquea fuera de horario, permite dentro).
        // 2. Si NO hay restricción remota, manda el switch local.
        val shouldBlock = if (snapshot.enabled) {
            !snapshot.isNowInPermittedRange()
        } else {
            LogisticsMonitoringService.isRunning
        }

        if (!shouldBlock) {
            if (targetAppActive || warningShown || blockingShown) resetState()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!targetAppActive) {
                    targetAppActive = true
                    onTargetAppOpened(event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (targetAppActive) {
                    checkClickedNode(event)
                }
            }
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

        val matched = texts.flatMap { text ->
            QR_KEYWORDS.filter { kw -> text.contains(kw, ignoreCase = true) }
        }.distinct()

        if (matched.isNotEmpty()) {
            Log.i(TAG, "🚨 Click con keywords QR=$matched, textos=$texts")
            onQRScanDetected(texts, matched)
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
    private fun buildScanOverlayContent(contextStr: String): Pair<String, String> {
        val geo = GeofenceEvaluator.evaluateForScan()
        return when (geo) {
            is GeofenceEvaluator.Result.Outside -> {
                val verbo = if (geo.accion == "bloquear") "🚫 ESCANEO FUERA DE ZONA"
                            else "⚠️ ESCANEO FUERA DE ZONA"
                val nota = if (geo.accion == "bloquear")
                    "\n\n🚷 Regla \"${geo.ruleName}\" exige escanear DENTRO del radio."
                else
                    "\n\n📍 Regla \"${geo.ruleName}\": estás escaneando fuera del radio recomendado."
                verbo to "Se detectaron elementos de escaneo:\n$contextStr\n\n📡 Estás a ${"%.0f".format(geo.distanceM)}m del centro (radio: ${"%.0f".format(geo.radiusM)}m).$nota"
            }
            is GeofenceEvaluator.Result.Inside -> {
                "🚫 ESCANEO QR DETECTADO" to "Se detectaron elementos de escaneo:\n$contextStr\n\n✅ Estás dentro de la zona permitida por la regla \"${geo.ruleName}\".\nVerificá el horario antes de continuar."
            }
            else -> {
                "🚫 ESCANEO QR DETECTADO" to "Se detectaron elementos de escaneo:\n$contextStr\n\n⚠️ Verificá que el horario sea el correcto antes de escanear."
            }
        }
    }

    private fun onQRScanDetected(allTexts: List<String>, detectedKeywords: List<String>) {
        if (blockingShown) return
        blockingShown = true

        val contextStr = allTexts.filter { text ->
            detectedKeywords.any { kw -> text.contains(kw, ignoreCase = true) }
        }.take(5).joinToString("\n• ", prefix = "• ")

        Log.i(TAG, "🚫 BLOQUEO QR — keywords: $detectedKeywords")

        val snap = scheduleRepository.load()
        val inSchedule = if (snap.enabled) snap.isNowInPermittedRange() else null
        EventReporter.report(
            this,
            EventReporter.TYPE_SCAN_DETECTED,
            keywords = detectedKeywords,
            inSchedule = inSchedule,
        )

        val (title, message) = buildScanOverlayContent(contextStr)

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
                    Log.i(TAG, "✅ Usuario canceló el escaneo — disparando back global")
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
                    // Simula el gesto "atrás": en pantalla principal minimiza, en
                    // pantalla secundaria retrocede. Es lo que pidió el usuario.
                    performGlobalAction(GLOBAL_ACTION_BACK)
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
