package com.logistics.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Dispatcher de notificaciones locales que reemplaza a ScheduleMessagingService
 * (que recibía pushes de FCM). El polling — sea de WorkManager o foreground —
 * llama a [notifyScheduleChanged] cuando detecta que el snapshot remoto difiere
 * del local. La notif aparece en la tray del sistema con el mismo aspecto que
 * tenía con FCM.
 *
 * Canal "schedule_updates_channel" (reusado del antiguo ScheduleMessagingService).
 */
object ScheduleNotifier {

    private const val CHANNEL_ID = "schedule_updates_channel"
    private const val CHANNEL_NAME = "Actualizaciones de horario"
    private const val NOTIFICATION_ID = 2001

    fun notifyScheduleChanged(context: Context, snapshot: ScheduleSnapshot) {
        ensureChannel(context)

        val body = if (snapshot.enabled && snapshot.from != null && snapshot.to != null) {
            "Nuevo rango permitido: ${snapshot.from} – ${snapshot.to}"
        } else {
            "Restricción horaria desactivada"
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📢 Horario actualizado por supervisor")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
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
