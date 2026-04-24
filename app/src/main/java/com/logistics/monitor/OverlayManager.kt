package com.logistics.monitor

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Maneja los dos overlays del sistema:
 *  1. showWarningOverlay()  → 1er cartel (informativo, se cierra solo o con tap)
 *  2. showBlockingOverlay() → 2do cartel (bloqueo con opciones Continuar / Cancelar)
 *
 * Usa TYPE_APPLICATION_OVERLAY que requiere el permiso SYSTEM_ALERT_WINDOW.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var warningView: View? = null
    private var blockingView: View? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay 1: Advertencia (informativo)
    // ─────────────────────────────────────────────────────────────────────────

    fun showWarningOverlay(title: String, message: String, onDismiss: () -> Unit) {
        if (warningView != null) return // ya hay uno visible

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_warning, null)
        view.findViewById<TextView>(R.id.tvWarningTitle).text = title
        view.findViewById<TextView>(R.id.tvWarningMessage).text = message
        view.findViewById<Button>(R.id.btnDismiss).setOnClickListener {
            removeWarningOverlay()
            onDismiss()
        }

        // Auto-cierre después de 8 segundos si el usuario no interactúa
        view.postDelayed({
            if (warningView != null) {
                removeWarningOverlay()
                onDismiss()
            }
        }, 8_000)

        addOverlay(view)
        warningView = view
        Log.i(TAG, "✅ Overlay advertencia mostrado")
    }

    private fun removeWarningOverlay() {
        warningView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* ya removido */ }
            warningView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay 2: Bloqueo (con opciones)
    // ─────────────────────────────────────────────────────────────────────────

    fun showBlockingOverlay(
        title: String,
        message: String,
        onContinue: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (blockingView != null) return

        // El 2do cartel reemplaza el 1ro si sigue visible
        removeWarningOverlay()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_blocking, null)
        view.findViewById<TextView>(R.id.tvBlockingTitle).text = title
        view.findViewById<TextView>(R.id.tvBlockingMessage).text = message

        view.findViewById<Button>(R.id.btnContinue).setOnClickListener {
            removeBlockingOverlay()
            onContinue()
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            removeBlockingOverlay()
            onCancel()
        }

        addOverlay(view)
        blockingView = view
        Log.i(TAG, "🚫 Overlay bloqueo mostrado")
    }

    private fun removeBlockingOverlay() {
        blockingView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* ya removido */ }
            blockingView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun removeAllOverlays() {
        removeWarningOverlay()
        removeBlockingOverlay()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper interno: agrega una view al WindowManager
    // ─────────────────────────────────────────────────────────────────────────

    private fun addOverlay(view: View) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error agregando overlay: ${e.message}")
        }
    }
}
