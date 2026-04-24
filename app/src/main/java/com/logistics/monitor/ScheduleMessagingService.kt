package com.logistics.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe pushes desde el backend remoto (CogniPilotRemote).
 *
 * Payload esperado (data message):
 *  - type: "schedule_update"
 *  - enabled: "true" | "false"
 *  - from: "HH:mm"   (ej: "08:00")
 *  - to: "HH:mm"     (ej: "18:00")
 *  - tz: IANA tz name (ej: "America/Argentina/Buenos_Aires")
 *
 * Acción: persiste el snapshot en SharedPreferences y dispara una notificación
 * "Horario actualizado por supervisor".
 */
class ScheduleMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "ScheduleMsgSvc"
        private const val CHANNEL_ID = "schedule_updates_channel"
        private const val CHANNEL_NAME = "Actualizaciones de horario"
        private const val NOTIFICATION_ID = 2001
        private const val TYPE_SCHEDULE_UPDATE = "schedule_update"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "🔑 Nuevo FCM token: $token")
        // Como nos suscribimos por topic ("schedule-updates") no hace falta
        // enviar el token al backend. Si en el futuro queremos targeting por
        // dispositivo, acá lo postearíamos.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.i(TAG, "📩 Push recibido: $data")

        if (data["type"] != TYPE_SCHEDULE_UPDATE) {
            Log.w(TAG, "Tipo desconocido, ignorado: ${data["type"]}")
            return
        }

        val snapshot = ScheduleSnapshot(
            enabled = data["enabled"]?.toBooleanStrictOrNull() ?: false,
            from = data["from"],
            to = data["to"],
            tz = data["tz"],
            updatedAt = System.currentTimeMillis()
        )
        ScheduleRepository(this).save(snapshot)

        showUpdateNotification(snapshot)
    }

    private fun showUpdateNotification(snapshot: ScheduleSnapshot) {
        ensureChannel()

        val body = if (snapshot.enabled && snapshot.from != null && snapshot.to != null) {
            "Nuevo rango permitido: ${snapshot.from} – ${snapshot.to}"
        } else {
            "Restricción horaria desactivada"
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📢 Horario actualizado por supervisor")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos cuando el supervisor cambia el rango horario"
            }
        )
    }
}
