package com.logistics.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Diagnóstico de cuelgues del hilo principal (ANR) — instrumentación para cazar
 * el "se congela CogniPilot al cargar la ruta" sin adb (ver I-31, §11).
 *
 * v2 (tras descubrir que la v1 solo capturaba el main en `nativePollOnce` idle,
 * que resultó ser ruido de congelamiento del proceso en background/Doze):
 *  - Solo mide cuando la Activity está en FOREGROUND ([foreground]); así no
 *    reporta los freezes del cgroup-freezer cuando la app está en background.
 *  - Captura el stack de TODOS los threads (no solo el main): si el main está
 *    idle pero hay un deadlock o un thread girando, queda a la vista. Filtra los
 *    threads claramente ociosos (WAITING/TIMED_WAITING en pollОnce/park).
 *  - Adjunta el último [breadcrumb] del arranque (hasta dónde llegó antes de
 *    colgarse): onCreate → onResume → render de ruta, etc.
 *
 * Reusa el tipo de evento válido `global_app_opened` (el back valida el enum
 * `TipoEvento`), marcado con `appPackage = "watchdog.anr.main"` para filtrar.
 */
object MainThreadWatchdog {
    private const val TAG = "MainWatchdog"
    private const val CHECK_INTERVAL_MS = 1_500L
    private const val STALL_THRESHOLD_MS = 5_000L
    private const val MARKER_PACKAGE = "watchdog.anr.main"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastMainTickAt = 0L
    @Volatile private var running = false
    @Volatile private var alreadyReported = false
    @Volatile private var foreground = false
    @Volatile private var breadcrumb = "init"
    private var worker: Thread? = null

    /** Marca el último hito del ciclo de vida/arranque (para saber dónde se colgó). */
    fun breadcrumb(step: String) {
        breadcrumb = step
    }

    /** La Activity informa si está en foreground; solo medimos ahí (descarta Doze). */
    fun setForeground(fg: Boolean) {
        foreground = fg
        // Al volver a foreground reseteamos el reloj para no contar el tiempo que
        // el proceso estuvo congelado en background como si fuera un stall.
        if (fg) {
            lastMainTickAt = SystemClock.elapsedRealtime()
            alreadyReported = false
        }
    }

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
                if (!foreground) continue // ignorar background (freezer/Doze = falso positivo)
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
        Log.i(TAG, "watchdog v2 iniciado")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun reportStall(context: Context, stalledMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val lines = ArrayList<String>()
        // 1) Main thread (siempre), top 14 frames.
        lines.add("MAIN[${mainThread.state}]: " + frames(mainThread, 14))
        // 2) Demás threads que NO estén claramente ociosos (para ver deadlock/spin).
        Thread.getAllStackTraces().forEach { (t, st) ->
            if (t === mainThread || t.name == "main-thread-watchdog") return@forEach
            if (isIdle(st)) return@forEach
            lines.add("${t.name}[${t.state}]: " + frames(t, 8))
        }
        Log.e(TAG, "🚨 STALL ${stalledMs}ms @${breadcrumb}\n${lines.joinToString("\n")}")
        try {
            EventReporter.report(
                context,
                EventReporter.TYPE_GLOBAL_APP_OPENED,
                screenName = "ANR ${stalledMs}ms @${breadcrumb}",
                appPackage = MARKER_PACKAGE,
                screenText = lines.take(12),
            )
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo reportar el stall: ${e.message}")
        }
    }

    /** Un thread "ocioso" típico: dormido en pollOnce / park / wait sin hacer nada. */
    private fun isIdle(st: Array<StackTraceElement>): Boolean {
        val top = st.firstOrNull()?.methodName ?: return true
        return top == "nativePollOnce" || top == "park" || top == "wait" ||
            top == "epollWait" || top == "read" || st.isEmpty()
    }

    private fun frames(t: Thread, n: Int): String =
        t.stackTrace.take(n).joinToString(" ← ") { compact(it) }

    private fun compact(e: StackTraceElement): String {
        val cls = e.className.substringAfterLast('.')
        return "$cls.${e.methodName}(${e.fileName}:${e.lineNumber})"
    }
}
