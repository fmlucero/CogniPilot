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
    }

    private lateinit var overlayManager: OverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var warningShown = false
    private var blockingShown = false
    private var targetAppActive = false

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
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
        }
        serviceInfo = info
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

        // Si el monitor está desactivado desde la app principal, no mostramos
        // overlays — el servicio sigue escuchando pero en modo silencioso.
        if (!LogisticsMonitoringService.isRunning) {
            if (targetAppActive || warningShown || blockingShown) resetState()
            return
        }

        // El target app dejó de estar en primer plano → limpiar todo
        if (pkg != TARGET_PACKAGE) {
            if (targetAppActive) {
                Log.d(TAG, "App target en segundo plano (pkg actual=$pkg) — reseteando")
                resetState()
            }
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
    // Cartel naranja: app abierta
    // ─────────────────────────────────────────────────────────────────────────

    private fun onTargetAppOpened(event: AccessibilityEvent) {
        if (warningShown) return
        warningShown = true

        val screenName = event.className?.toString()?.substringAfterLast('.') ?: "App"
        Log.i(TAG, "🚚 ENVÍOS SC PACK ABIERTO — pantalla: $screenName")

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

        mainHandler.post {
            overlayManager.showBlockingOverlay(
                title = "🚫 ESCANEO QR DETECTADO",
                message = "Se detectaron elementos de escaneo:\n$contextStr\n\n⚠️ Verificá que el horario sea el correcto antes de escanear.",
                onContinue = {
                    Log.w(TAG, "⚠️ Usuario eligió CONTINUAR con el escaneo")
                    blockingShown = false
                    mainHandler.post {
                        Toast.makeText(this, "⚠️ Escaneo permitido por el usuario", Toast.LENGTH_LONG).show()
                    }
                },
                onCancel = {
                    Log.i(TAG, "✅ Usuario canceló el escaneo — disparando back global")
                    blockingShown = false
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
}
