package com.logistics.monitor

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * HU-10 — Drena los eventos persistidos por EventReporter cuando no había red.
 *
 * Tres canales de drain:
 *  1. Worker periódico cada 15 min (mínimo de WorkManager) con constraint
 *     NetworkType.CONNECTED — solo corre si hay red.
 *  2. Worker one-time disparado desde MainActivity.onResume — mismo constraint;
 *     si no hay red, queda pending hasta que aparezca.
 *  3. Llamada inline desde EventReporter.drainOffline() en cualquier acción
 *     que detecte recuperación de red (futuro: BroadcastReceiver).
 *
 * El [EventReporter.drainOffline] internamente usa un Mutex para que aunque
 * los tres canales disparen simultáneamente, no haya doble envío.
 */
class OfflineDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "▶️ OfflineDrainWorker tick")
        return try {
            val (sent, failed) = EventReporter.drainOffline(applicationContext)
            Log.i(TAG, "✅ OfflineDrainWorker done — sent=$sent, failed=$failed")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "❌ OfflineDrainWorker error: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OfflineDrainWorker"
        private const val PERIODIC_WORK_NAME = "cognipilot_offline_drain_periodic"
        private const val ONESHOT_WORK_NAME = "cognipilot_offline_drain_oneshot"
        private const val PERIOD_MIN = 15L

        /** Programa el periódico. Idempotente (KEEP). Llamar en MainActivity.onCreate. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineDrainWorker>(
                PERIOD_MIN, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "📅 Drainer periódico programado (cada $PERIOD_MIN min, requiere red)")
        }

        /** Dispara un drain inmediato (espera red si no la hay). */
        fun triggerOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineDrainWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "🔁 Drainer one-shot enqueued")
        }

        /** Cancelar al logout. Sin sesión no hay para qué intentar. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONESHOT_WORK_NAME)
            Log.i(TAG, "🛑 Drainer cancelado (logout)")
        }
    }
}
