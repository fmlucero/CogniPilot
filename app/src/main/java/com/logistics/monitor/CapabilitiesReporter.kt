package com.logistics.monitor

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.logistics.monitor.auth.AuthRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * HU-43 — Reporta al back el estado actual de los permisos/servicios del
 * dispositivo Android (pre-flight check del repartidor).
 *
 * Flags reportados:
 *   - overlay_ok            Settings.canDrawOverlays
 *   - accessibility_ok      LogisticsAccessibilityService.isServiceConnected
 *   - location_perm         LocationReporter.hasPermission (FINE o COARSE)
 *   - notifications_perm    NotificationManagerCompat.areNotificationsEnabled
 *   - monitor_running       LogisticsMonitoringService.isRunning
 *
 * Endpoint: PATCH /api/devices/{deviceUuid}/capabilities con merge en el back
 * (las flags omitidas no pisan las anteriores).
 *
 * Para evitar spam: guardamos un snapshot del último report y sólo enviamos si
 * algún flag cambió o si pasaron más de RESEND_INTERVAL_MS desde el último envío
 * exitoso.
 */
object CapabilitiesReporter {

    private const val TAG = "CapabilitiesReporter"
    private const val PATH_PREFIX = "/api/devices/"
    private const val PATH_SUFFIX = "/capabilities"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    // Reenviar cada 6h aunque no haya cambios — así el back ve "fresh" y no
    // marca el device como unknown (capabilities_updated_at > 24h).
    private const val RESEND_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor()

    private var lastSnapshot: Map<String, Boolean>? = null
    @Volatile private var lastSentAt: Long = 0L

    /**
     * Mide el estado actual y, si difiere del último snapshot o pasó el
     * RESEND_INTERVAL_MS, postea PATCH /capabilities. Idempotente y barato
     * llamarlo en cada onResume.
     */
    fun reportNow(context: Context) {
        val appCtx = context.applicationContext
        val tokens = AuthRepository.get(appCtx).tokens
        val token = tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "sin sesión — descartando report")
            return
        }
        val deviceUuid = DeviceIdProvider.get(appCtx)

        val snapshot = measure(appCtx)
        val now = System.currentTimeMillis()
        val changed = snapshot != lastSnapshot
        val stale = now - lastSentAt > RESEND_INTERVAL_MS
        if (!changed && !stale) {
            Log.d(TAG, "sin cambios y dentro del intervalo — skip")
            return
        }

        val payload = JSONObject().apply {
            for ((k, v) in snapshot) put(k, v)
        }.toString()

        val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')
        val url = "$baseUrl$PATH_PREFIX$deviceUuid$PATH_SUFFIX"

        executor.execute {
            val ok = patchJson(url, payload, token)
            if (ok) {
                lastSnapshot = snapshot
                lastSentAt = now
                Log.i(TAG, "✅ capabilities reportadas: $snapshot")
            } else {
                Log.w(TAG, "⚠️ falló el reporte de capabilities — reintentamos en el próximo onResume")
            }
        }
    }

    private fun measure(context: Context): Map<String, Boolean> = mapOf(
        "overlay_ok" to Settings.canDrawOverlays(context),
        "accessibility_ok" to LogisticsAccessibilityService.isServiceConnected,
        "location_perm" to LocationReporter.hasPermission(context),
        "notifications_perm" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
        "monitor_running" to LogisticsMonitoringService.isRunning,
    )

    private fun patchJson(url: String, body: String, token: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                true
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
                Log.w(TAG, "PATCH /capabilities devolvió HTTP $code: ${err.take(200)}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ falló PATCH /capabilities: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
