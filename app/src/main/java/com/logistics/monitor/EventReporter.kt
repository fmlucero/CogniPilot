package com.logistics.monitor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Envía eventos del usuario al backend CogniPilotRemote para mostrar en el panel
 * web en tiempo real (POST /api/events).
 *
 * - Fire-and-forget: si falla la red el evento se descarta (uso personal,
 *   no necesitamos reintentos persistentes por ahora).
 * - Endpoint público sin auth — acordado con el usuario para minimizar config.
 * - device_id se genera la primera vez y se guarda en SharedPreferences.
 *
 * URL base configurada en res/values/strings.xml → @string/backend_base_url
 */
object EventReporter {

    private const val TAG = "EventReporter"
    private const val PREFS = "event_reporter_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val PATH = "/api/events"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    // Tipos válidos en el backend (Backend → lib/events.ts)
    const val TYPE_APP_OPENED = "app_opened"
    const val TYPE_WARNING_SHOWN = "warning_shown"
    const val TYPE_SCAN_DETECTED = "scan_detected"
    const val TYPE_USER_CONTINUED = "user_continued"
    const val TYPE_USER_CANCELLED = "user_cancelled"

    private val executor = Executors.newSingleThreadExecutor()

    fun report(
        context: Context,
        type: String,
        screenName: String? = null,
        keywords: List<String>? = null,
        inSchedule: Boolean? = null,
    ) {
        val appCtx = context.applicationContext
        val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')
        val deviceId = ensureDeviceId(appCtx)

        val payload = JSONObject().apply {
            put("type", type)
            put("deviceId", deviceId)
            screenName?.takeIf { it.isNotBlank() }?.let { put("screenName", it) }
            inSchedule?.let { put("inSchedule", it) }
            keywords?.takeIf { it.isNotEmpty() }?.let { put("keywords", JSONArray(it)) }
        }.toString()

        executor.execute {
            postJson("$baseUrl$PATH", payload)
        }
    }

    private fun ensureDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun postJson(url: String, body: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
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
