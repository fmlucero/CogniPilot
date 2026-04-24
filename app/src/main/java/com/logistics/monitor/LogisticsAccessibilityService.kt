package com.logistics.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Núcleo del Monitor de Logística.
 *
 * Cómo funciona:
 *  1. Android notifica cada cambio de ventana/contenido via onAccessibilityEvent().
 *  2. Filtramos solo eventos del package com.mercadoenvios.logistics.
 *  3. Cuando el package se abre → Overlay de ADVERTENCIA (1er cartel).
 *  4. Cuando detectamos texto/botón relacionado con escaneo QR en la jerarquía
 *     de vistas → Overlay de BLOQUEO (2do cartel).
 *  5. Traversal recursivo de AccessibilityNodeInfo para leer TODOS los textos
 *     visibles en pantalla (sin OCR, sin ML Kit: es la propia API de Android).
 */
class LogisticsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LogisticsAccessSvc"
        const val TARGET_PACKAGE = "com.mercadoenvios.logistics"

        /** Palabras clave que indican pantalla/botón de escaneo QR */
        private val QR_KEYWORDS = listOf(
            "escanear", "scan", "qr", "código", "codigo", "scanner",
            "cámara", "camara", "capturar", "leer", "barcode"
        )

        /** Publicamos el estado para que MainActivity pueda leerlo */
        var isServiceConnected = false
            private set
    }

    private lateinit var overlayManager: OverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Control de estado para no spamear overlays
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

        Log.i(TAG, "✅ Servicio de accesibilidad CONECTADO")
        mainHandler.post {
            Toast.makeText(this, "🟢 Monitor Logística ACTIVO", Toast.LENGTH_SHORT).show()
        }

        // Configuración dinámica por si el XML no aplica (backup)
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
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
        overlayManager.removeAllOverlays()
        isServiceConnected = false
        Log.i(TAG, "🔴 Servicio destruido")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evento principal — aquí entra TODO lo que detecta Android
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // ── Detectar cuando el target app deja de estar en primer plano ──────
        if (pkg != TARGET_PACKAGE && targetAppActive) {
            Log.d(TAG, "App target cerrada o en segundo plano")
            targetAppActive = false
            warningShown = false
            blockingShown = false
            overlayManager.removeAllOverlays()
            return
        }

        if (pkg != TARGET_PACKAGE) return

        // ── El package objetivo está activo ───────────────────────────────────
        Log.d(TAG, "Evento [${eventTypeName(event.eventType)}] de $pkg | class=${event.className}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!targetAppActive) {
                    targetAppActive = true
                    onTargetAppOpened(event)
                }
                // También escaneamos la jerarquía de vistas con cada cambio de pantalla
                scanWindowHierarchy()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (targetAppActive) {
                    scanWindowHierarchy()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1er cartel: App abierta
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
    // Lectura de la jerarquía de vistas (sin OCR — pura API de Android)
    // ─────────────────────────────────────────────────────────────────────────

    private fun scanWindowHierarchy() {
        if (blockingShown) return // ya mostramos el bloqueo, no repetir

        val root: AccessibilityNodeInfo = rootInActiveWindow ?: run {
            Log.d(TAG, "rootInActiveWindow nulo, sin datos de UI")
            return
        }

        val allTexts = mutableListOf<String>()
        collectTexts(root, allTexts)

        if (allTexts.isEmpty()) return

        Log.d(TAG, "📋 Textos leídos en pantalla (${allTexts.size}): ${allTexts.take(10)}")

        val detectedKeywords = allTexts.flatMap { text ->
            QR_KEYWORDS.filter { kw -> text.contains(kw, ignoreCase = true) }
        }.distinct()

        if (detectedKeywords.isNotEmpty()) {
            Log.i(TAG, "🚨 KEYWORDS QR DETECTADAS: $detectedKeywords")
            onQRScanDetected(allTexts, detectedKeywords)
        }

        root.recycle()
    }

    /**
     * Traversal recursivo de la jerarquía de vistas.
     * Lee texto visible, contentDescription y hint de TODOS los nodos.
     * Esto es lo equivalente al "OCR nativo" sin cámara.
     */
    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        // Texto del elemento
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        // Descripción de accesibilidad (lo que lee el lector de pantalla)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        // Hint del campo (texto gris de placeholder)
        node.hintText?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        // ID de recurso: a veces el nombre del ID da contexto (ej: "btn_scan_qr")
        node.viewIdResourceName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }?.let {
            result.add(it)
        }

        // Recursión en hijos
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTexts(child, result)
                child.recycle()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2do cartel: Botón de escaneo detectado
    // ─────────────────────────────────────────────────────────────────────────

    private fun onQRScanDetected(allTexts: List<String>, detectedKeywords: List<String>) {
        if (blockingShown) return
        blockingShown = true

        val keywordStr = detectedKeywords.joinToString(", ") { "\"$it\"" }
        // Muestra hasta 5 textos del contexto para debug
        val contextStr = allTexts.filter { text ->
            detectedKeywords.any { kw -> text.contains(kw, ignoreCase = true) }
        }.take(5).joinToString("\n• ", prefix = "• ")

        Log.i(TAG, "🚫 BLOQUEO QR — keywords: $keywordStr")

        mainHandler.post {
            overlayManager.showBlockingOverlay(
                title = "🚫 ESCANEO QR DETECTADO",
                message = "Se detectaron elementos de escaneo en pantalla:\n$contextStr\n\n⚠️ Verificá que el horario sea el correcto antes de escanear.",
                onContinue = {
                    Log.w(TAG, "⚠️ Usuario eligió CONTINUAR con el escaneo")
                    blockingShown = false
                    mainHandler.post {
                        Toast.makeText(this, "⚠️ Escaneo permitido por el usuario", Toast.LENGTH_LONG).show()
                    }
                },
                onCancel = {
                    Log.i(TAG, "✅ Usuario canceló el escaneo")
                    blockingShown = false
                    mainHandler.post {
                        Toast.makeText(this, "✅ Escaneo cancelado correctamente", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        else -> "type=$type"
    }
}
