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
 *  2. showBlockingOverlay() → 2do cartel (bloqueo con opciones Continuar / Aceptar;
 *     Aceptar acata el bloqueo y minimiza la app de trabajo vía GLOBAL_ACTION_HOME)
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
    private var nudgeView: View? = null
    private var kioskoView: View? = null

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

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay 3: Nudge (HU-09) — informativo no bloqueante, top, auto-dismiss
    // ─────────────────────────────────────────────────────────────────────────

    fun showNudgeOverlay(title: String, message: String, onTap: () -> Unit = {}) {
        // Si ya hay un nudge visible, lo reemplazamos por el nuevo (caso poco
        // probable — dos paradas en 8s).
        removeNudgeOverlay()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_nudge, null)
        view.findViewById<TextView>(R.id.tvNudgeTitle).text = title
        view.findViewById<TextView>(R.id.tvNudgeMessage).text = message
        view.findViewById<View>(R.id.nudgeContainer).setOnClickListener {
            removeNudgeOverlay()
            onTap()
        }

        // Auto-cierre tras 8s — criterio de la HU.
        view.postDelayed({ removeNudgeOverlay() }, 8_000)

        addOverlay(view, gravity = Gravity.TOP)
        nudgeView = view
        Log.i(TAG, "📍 Nudge mostrado: $title")
    }

    private fun removeNudgeOverlay() {
        nudgeView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { /* ya removido */ }
            nudgeView = null
        }
    }

    fun removeAllOverlays() {
        removeWarningOverlay()
        removeBlockingOverlay()
        removeNudgeOverlay()
        // NOTA: el overlay kiosko (HU-59) NO se remueve acá — es persistente e
        // independiente del flujo de horario/escaneo. Se quita sólo cuando se
        // cumplen las condiciones o el usuario sale del modo jornada.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay 4: Kiosko de jornada (HU-59) — full-screen, persistente
    // ─────────────────────────────────────────────────────────────────────────

    /** True si el overlay kiosko está actualmente en pantalla. */
    fun isKioskoShowing(): Boolean = kioskoView != null

    /**
     * Muestra o actualiza el overlay kiosko. Si ya existe, sólo refresca el
     * checklist y el detalle (sin re-crearlo, para no parpadear). El callback
     * onExit se cablea una sola vez al crearlo.
     */
    fun showOrUpdateKioskoOverlay(
        zonaOk: Boolean,
        horarioOk: Boolean,
        permisosOk: Boolean,
        detail: String,
        onExit: () -> Unit,
    ) {
        val view = kioskoView ?: LayoutInflater.from(context).inflate(R.layout.overlay_kiosko, null).also {
            it.findViewById<Button>(R.id.btnKioskoExit).setOnClickListener { onExit() }
            addFullScreenOverlay(it)
            kioskoView = it
            Log.i(TAG, "🔒 Overlay kiosko mostrado")
        }
        setKioskoRow(view.findViewById(R.id.tvKioskoZona), "En la zona permitida", zonaOk)
        setKioskoRow(view.findViewById(R.id.tvKioskoHorario), "En el horario permitido", horarioOk)
        setKioskoRow(view.findViewById(R.id.tvKioskoPermisos), "Permisos y monitor activos", permisosOk)
        view.findViewById<TextView>(R.id.tvKioskoDetail).text = detail
    }

    private fun setKioskoRow(tv: TextView, label: String, ok: Boolean) {
        tv.text = if (ok) "✓  $label" else "✗  $label"
        tv.setTextColor(context.getColor(if (ok) R.color.cp_success else R.color.cp_error))
    }

    fun removeKioskoOverlay() {
        kioskoView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { /* ya removido */ }
            kioskoView = null
            Log.i(TAG, "🔓 Overlay kiosko removido")
        }
    }

    /** Overlay que cubre toda la pantalla y captura el toque (bloqueo kiosko-lite). */
    private fun addFullScreenOverlay(view: View) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Sin FLAG_NOT_FOCUSABLE: el overlay toma foco y captura el toque para
        // bloquear la interacción con lo que haya debajo (kiosko-lite, sin
        // device-owner). FLAG_FULLSCREEN para ocupar la pantalla completa.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error agregando overlay kiosko: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper interno: agrega una view al WindowManager
    // ─────────────────────────────────────────────────────────────────────────

    private fun addOverlay(view: View, gravity: Int = Gravity.CENTER) {
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
            this.gravity = gravity
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error agregando overlay: ${e.message}")
        }
    }
}
