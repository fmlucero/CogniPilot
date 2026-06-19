package com.logistics.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Diagnóstico de cuelgues del hilo principal (ANR) — instrumentación para
 * cazar el "se congela CogniPilot al cargar la ruta" sin adb (ver I-31 en
 * investigación, §11).
 *
 * Cómo funciona: un thread daemon le postea al main thread un "tick" cada
 * [CHECK_INTERVAL_MS]. Si el main no procesa el tick en [STALL_THRESHOLD_MS]
 * (está colgado), el watchdog captura el **stacktrace del hilo principal** —que
 * muestra exactamente en qué línea quedó trabado— y lo manda al backend vía
 * [EventReporter] (el proceso sigue vivo durante un ANR, así que el POST sale en
 * un thread aparte). Después se lee en la DB para diagnosticar.
 *
 * Reusa el tipo de evento válido `global_app_opened` (el back valida el enum
 * `TipoEvento`), marcándolo con `appPackage = "watchdog.anr.main"` para filtrar.
 */
object MainThreadWatchdog {
    private const val TAG = "MainWatchdog"
    private const val CHECK_INTERVAL_MS = 1_500L
    private const val STALL_THRESHOLD_MS = 5_000L
    private const val MARKER_PACKAGE = "watchdog.anr.main"
    private const val MAX_FRAMES = 28

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastMainTickAt = 0L
    @Volatile private var running = false
    @Volatile private var alreadyReported = false
    private var worker: Thread? = null

    fun start(context: Context) {
        if (running) return
        running = true
        alreadyReported = false
        val appCtx = context.applicationContext
        lastMainTickAt = SystemClock.elapsedRealtime()
        worker = Thread {
            while (running) {
                mainHandler.post { lastMainTickAt = SystemClock.elapsedRealtime() }
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val stalledMs = SystemClock.elapsedRealtime() - lastMainTickAt
                if (stalledMs > STALL_THRESHOLD_MS) {
                    if (!alreadyReported) {
                        alreadyReported = true
                        reportStall(appCtx, stalledMs)
                    }
                } else {
                    alreadyReported = false
                }
            }
        }.apply {
            isDaemon = true
            name = "main-thread-watchdog"
            start()
        }
        Log.i(TAG, "watchdog del hilo principal iniciado")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun reportStall(context: Context, stalledMs: Long) {
        val main = Looper.getMainLooper().thread
        val frames = main.stackTrace.take(MAX_FRAMES).map { compact(it) }
        Log.e(TAG, "🚨 HILO PRINCIPAL COLGADO ${stalledMs}ms:\n${frames.joinToString("\n")}")
        try {
            EventReporter.report(
                context,
                EventReporter.TYPE_GLOBAL_APP_OPENED,
                screenName = "ANR_MAIN_STALL ${stalledMs}ms",
                appPackage = MARKER_PACKAGE,
                screenText = frames,
            )
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo reportar el stall: ${e.message}")
        }
    }

    private fun compact(e: StackTraceElement): String {
        val cls = e.className.substringAfterLast('.')
        return "$cls.${e.methodName}(${e.fileName}:${e.lineNumber})"
    }
}
