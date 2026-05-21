package com.logistics.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Servicio foreground que mantiene una notificación persistente
 * indicando que el monitor está activo. Esto evita que Android
 * mate el proceso por ahorro de batería.
 */
class LogisticsMonitoringService : Service() {

    companion object {
        private const val TAG = "LogisticsMonSvc"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "logistics_monitor_channel"
        private const val CHANNEL_NAME = "Monitor de Logística"

        var isRunning = false
            private set

        /**
         * Callback opcional invocado cuando isRunning cambia.
         * MainActivity lo registra para refrescar la UI en tiempo real
         * (sin tener que esperar a onResume).
         */
        var onStateChange: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        onStateChange?.invoke()
        Log.i(TAG, "✅ LogisticsMonitoringService creado")
        createNotificationChannel()
        // Reset del accessibility para que el cartel naranja vuelva a aparecer
        // la próxima vez que el usuario entre a Envíos SC Pack.
        LogisticsAccessibilityService.resetMonitorState()
        // HU-41 — empezar a reportar GPS (si hay permission; de lo contrario log y nada).
        LocationReporter.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "▶️ Servicio foreground iniciado")
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY // Android lo reinicia si lo mata
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // HU-41 — detener reporte GPS
        LocationReporter.stop()
        onStateChange?.invoke()
        Log.i(TAG, "🔴 LogisticsMonitoringService destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Sin sonido, discreta
            ).apply {
                description = "Monitoreo activo de Envíos SC Pack"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🟢 Monitor Logística activo")
            .setContentText("Vigilando Envíos SC Pack...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true) // No se puede descartar con swipe
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
