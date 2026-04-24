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
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Pantalla principal del Monitor de Logística.
 *
 * Responsabilidades:
 *  - Verificar y solicitar permiso de overlay (SYSTEM_ALERT_WINDOW)
 *  - Redirigir al usuario a la configuración de Accesibilidad
 *  - Mostrar estado actual del servicio (activo/inactivo)
 *  - Iniciar/detener el ForegroundService de notificación
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY = 1001
        private const val SCHEDULE_TOPIC = "schedule-updates"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var btnConfigureOverlay: Button
    private lateinit var btnOpenAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnConfigureOverlay = findViewById(R.id.btnConfigureOverlay)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)

        setupButtons()
        subscribeToScheduleTopic()
        Log.i(TAG, "MainActivity creado")
    }

    private fun subscribeToScheduleTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic(SCHEDULE_TOPIC)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.i(TAG, "✅ Suscripto a topic $SCHEDULE_TOPIC")
                } else {
                    Log.w(TAG, "❌ No se pudo suscribir al topic", task.exception)
                }
            }
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
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
