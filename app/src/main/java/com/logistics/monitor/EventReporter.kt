package com.logistics.monitor

import android.content.Context
import android.util.Log
import com.logistics.monitor.auth.AuthRepository
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Envía eventos del usuario al backend para mostrar en el panel web en tiempo
 * real (POST /api/events).
 *
 * HU-03: ahora requiere Bearer token. Si no hay sesión, el evento se descarta
 * silenciosamente (logger.debug). El AAS sigue corriendo aunque no haya login;
 * el primer login después poblará los eventos correctos.
 *
 * - Fire-and-forget: si falla la red el evento se descarta.
 * - device_id se genera la primera vez y se guarda en SharedPreferences (mismo
 *   que usa DeviceIdProvider, compartido con AuthRepository).
 *
 * URL base configurada en res/values/strings.xml → @string/backend_base_url
 */
object EventReporter {

    private const val TAG = "EventReporter"
    private const val PATH = "/api/events"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    // Tipos válidos en el backend
    const val TYPE_APP_OPENED = "app_opened"
    const val TYPE_WARNING_SHOWN = "warning_shown"
    const val TYPE_SCAN_DETECTED = "scan_detected"
    const val TYPE_USER_CONTINUED = "user_continued"
    const val TYPE_USER_CANCELLED = "user_cancelled"
    // Modo global (apps externas a SC Pack)
    const val TYPE_GLOBAL_APP_OPENED = "global_app_opened"
    const val TYPE_GLOBAL_CLICKED = "global_clicked"

    private val executor = Executors.newSingleThreadExecutor()

    fun report(
        context: Context,
        type: String,
        screenName: String? = null,
        keywords: List<String>? = null,
        inSchedule: Boolean? = null,
        appPackage: String? = null,
        screenText: List<String>? = null,
    ) {
        val appCtx = context.applicationContext
        val token = AuthRepository.get(appCtx).tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "⏸️ Sin sesión activa — descartando evento type=$type")
            return
        }

        val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')
        val deviceUuid = DeviceIdProvider.get(appCtx)

        val payload = JSONObject().apply {
            put("type", type)
            put("deviceUuid", deviceUuid)
            screenName?.takeIf { it.isNotBlank() }?.let { put("screenName", it) }
            inSchedule?.let { put("inSchedule", it) }
            keywords?.takeIf { it.isNotEmpty() }?.let { put("keywords", JSONArray(it)) }
            appPackage?.takeIf { it.isNotBlank() }?.let { put("appPackage", it) }
            screenText?.takeIf { it.isNotEmpty() }?.let { put("screenText", JSONArray(it)) }
        }.toString()

        executor.execute {
            postJson("$baseUrl$PATH", payload, token)
        }
    }

    private fun postJson(url: String, body: String, token: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
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
                Log.d(TAG, "✅ Evento enviado (HTTP $code) — $body")
            } else {
                Log.w(TAG, "⚠️ Backend respondió HTTP $code — $body")
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ Falló envío de evento: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
