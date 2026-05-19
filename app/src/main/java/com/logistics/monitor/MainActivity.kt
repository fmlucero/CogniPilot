package com.logistics.monitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pantalla principal del Monitor de Logística.
 *
 * Responsabilidades:
 *  - Verificar y solicitar permiso de overlay (SYSTEM_ALERT_WINDOW)
 *  - Redirigir al usuario a la configuración de Accesibilidad
 *  - Mostrar estado actual del servicio (activo/inactivo)
 *  - Iniciar/detener el ForegroundService de notificación
 *
 * HU-18: programa el ScheduleSyncWorker (WorkManager, cada 15 min) y corre un
 * polling de schedule cada 30s mientras la activity está en foreground.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY = 1001
        private const val FOREGROUND_POLL_INTERVAL_MS = 30_000L
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var btnConfigureOverlay: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnToggleGlobal: Button
    private lateinit var globalModeRepository: GlobalModeRepository

    private var foregroundPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnConfigureOverlay = findViewById(R.id.btnConfigureOverlay)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnToggleGlobal = findViewById(R.id.btnToggleGlobal)
        globalModeRepository = GlobalModeRepository(this)

        setupButtons()
        updateGlobalButtonLabel()

        // HU-18: schedule del worker periódico (background sync cada 15 min)
        ScheduleSyncWorker.schedulePeriodic(this)

        // Cuando el servicio cambia su estado (onCreate/onDestroy), refrescamos
        // la UI sin esperar a onResume.
        LogisticsMonitoringService.onStateChange = {
            runOnUiThread { updateStatusDisplay() }
        }

        Log.i(TAG, "MainActivity creado")
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
        startForegroundPolling()
    }

    override fun onPause() {
        super.onPause()
        stopForegroundPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogisticsMonitoringService.onStateChange = null
    }

    /**
     * HU-18: polling de schedule mientras la activity está visible.
     * Cuando detecta cambio respecto al snapshot local, dispara notif y
     * fuerza re-evaluación del AccessibilityService.
     */
    private fun startForegroundPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = lifecycleScope.launch {
            // Primer poll inmediato al volver a foreground.
            tickPoll()
            while (isActive) {
                delay(FOREGROUND_POLL_INTERVAL_MS)
                tickPoll()
            }
        }
    }

    private fun stopForegroundPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = null
    }

    private suspend fun tickPoll() {
        val changed = ScheduleSyncWorker.syncOnce(applicationContext)
        if (changed) {
            Log.i(TAG, "📡 Polling detectó cambio de schedule")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnConfigureOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            } else {
                Toast.makeText(this, "✅ Permiso overlay ya concedido", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Buscá \"Monitor Logística\" y actívalo",
                Toast.LENGTH_LONG
            ).show()
        }

        btnToggleMonitor.setOnClickListener {
            if (LogisticsMonitoringService.isRunning) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        btnToggleGlobal.setOnClickListener {
            val newState = !globalModeRepository.isEnabled()
            globalModeRepository.setEnabled(newState)
            LogisticsAccessibilityService.applyGlobalMode(newState)
            updateGlobalButtonLabel()
            Toast.makeText(
                this,
                if (newState) "🌐 Modo global ACTIVADO — todas las apps reportan"
                else "🌐 Modo global desactivado — solo SC Pack",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateGlobalButtonLabel() {
        val enabled = globalModeRepository.isEnabled()
        btnToggleGlobal.text = if (enabled) "🌐 Modo global: ON" else "🌐 Modo global: OFF"
        btnToggleGlobal.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (enabled) R.color.cp_success else R.color.cp_bg_elev_2
        )
        btnToggleGlobal.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.cp_accent_text else R.color.cp_text,
            )
        )
    }

    private fun startMonitoringService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "⚠️ Primero otorgá el permiso de overlay", Toast.LENGTH_LONG).show()
            return
        }
        if (!LogisticsAccessibilityService.isServiceConnected) {
            Toast.makeText(this, "⚠️ Primero activá el servicio de accesibilidad", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, LogisticsMonitoringService::class.java)
        startForegroundService(intent)
        updateStatusDisplay()
        Log.i(TAG, "Servicio foreground iniciado")
    }

    private fun stopMonitoringService() {
        stopService(Intent(this, LogisticsMonitoringService::class.java))
        updateStatusDisplay()
        Log.i(TAG, "Servicio foreground detenido")
    }

    private fun updateStatusDisplay() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = LogisticsAccessibilityService.isServiceConnected
        val serviceRunning = LogisticsMonitoringService.isRunning

        val statusLines = buildString {
            appendLine(if (overlayOk) "✅ Permiso overlay: OK" else "❌ Permiso overlay: FALTA")
            appendLine(if (accessibilityOk) "✅ Accesibilidad: ACTIVA" else "❌ Accesibilidad: INACTIVA")
            appendLine(if (serviceRunning) "✅ Servicio: CORRIENDO" else "⏸️ Servicio: DETENIDO")

            if (overlayOk && accessibilityOk) {
                appendLine("\n🟢 LISTO — Monitor funcionando")
                appendLine("Monitoreando: com.mercadoenvios.logistics")
            } else {
                appendLine("\n⚠️ Configuración incompleta")
                appendLine("Completá los pasos 1 y 2")
            }
        }

        tvStatus.text = statusLines
        btnToggleMonitor.text = if (serviceRunning) "⏹ Desactivar monitor" else "▶️ Activar monitor"
        btnToggleMonitor.isEnabled = overlayOk && accessibilityOk
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            val granted = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (granted) "✅ Permiso overlay concedido" else "❌ Permiso overlay denegado",
                Toast.LENGTH_SHORT
            ).show()
            updateStatusDisplay()
        }
    }
}
