package com.logistics.monitor

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Worker periódico de WorkManager que sincroniza el horario en background.
 *
 * Frecuencia mínima de WorkManager periódico: 15 minutos (límite de Android,
 * no negociable). Para sync más fino en foreground existe el polling de
 * MainActivity (cada 30s).
 *
 * Estrategia:
 *   1. Hace GET /api/schedule
 *   2. Compara el snapshot con el local (ScheduleRepository)
 *   3. Si difiere → persiste el nuevo + dispara notificación local + fuerza
 *      re-evaluación del AccessibilityService
 *
 * Reemplazo de FCM tras HU-18.
 */
class ScheduleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "▶️ ScheduleSyncWorker tick")
        val changed = syncOnce(applicationContext)
        Log.i(TAG, "✅ ScheduleSyncWorker done (cambio detectado: $changed)")
        return Result.success()
    }

    companion object {
        private const val TAG = "ScheduleSyncWorker"
        private const val WORK_NAME = "cognipilot_schedule_sync"
        private const val PERIOD_MIN = 15L  // mínimo permitido por WorkManager

        /**
         * Hace una sincronización puntual: fetch /api/schedule + apply.
         * Retorna true si detectó cambio. Usado por el worker y el polling.
         */
        suspend fun syncOnce(context: Context): Boolean {
            val remote = ScheduleApi.fetchSchedule(context) ?: return false
            return applySnapshot(context, remote)
        }

        /**
         * Aplica un snapshot remoto recibido por cualquier canal (polling o SSE).
         * Compara con el local y si difiere persiste + notifica + re-evalúa.
         * Retorna true si hubo cambio efectivo.
         */
        fun applySnapshot(context: Context, remote: ScheduleSnapshot): Boolean {
            val repo = ScheduleRepository(context)
            val local = repo.load()

            // Si el remote.updatedAt es menor o igual al guardado, no cambió.
            if (remote.updatedAt > 0 && remote.updatedAt <= local.updatedAt) {
                return false
            }
            // Sanity check adicional: si todos los campos coinciden, evitamos
            // notif espurea (caso donde el back devuelve updatedAt=0).
            if (snapshotsAreEqual(local, remote)) {
                return false
            }

            repo.save(remote)
            ScheduleNotifier.notifyScheduleChanged(context, remote)
            // Forzar re-evaluación de carteles overlay si la app de envíos
            // está abierta justo ahora.
            LogisticsAccessibilityService.reevaluateCurrentState()
            return true
        }

        private fun snapshotsAreEqual(a: ScheduleSnapshot, b: ScheduleSnapshot): Boolean {
            return a.enabled == b.enabled
                && a.from == b.from
                && a.to == b.to
                && a.tz == b.tz
        }

        /**
         * Programa el worker periódico. Llamado desde MainActivity.onCreate.
         * Idempotente: usa KEEP policy para no reemplazar si ya estaba programado.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                PERIOD_MIN, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "📅 Worker periódico programado (cada $PERIOD_MIN min)")
        }

        /**
         * HU-03 — Cancelar el worker periódico al cerrar sesión.
         * Sin sesión no hay token para autenticar el GET /api/schedule.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "🛑 Worker periódico cancelado (logout)")
        }
    }
}
