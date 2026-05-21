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

        mainHandler.post {
            overlayManager.showBlockingOverlay(
                title = "🚫 ESCANEO QR DETECTADO",
                message = "Se detectaron elementos de escaneo:\n$contextStr\n\n⚠️ Verificá que el horario sea el correcto antes de escanear.",
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
