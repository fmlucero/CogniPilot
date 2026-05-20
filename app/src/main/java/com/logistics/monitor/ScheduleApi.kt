package com.logistics.monitor

import android.content.Context
import android.util.Log
import com.logistics.monitor.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP minimalista para consultar el endpoint GET /api/schedule del back.
 *
 * HU-18: polling en lugar de FCM (foreground 30s, background 15 min via WorkManager).
 * HU-03: agrega Authorization Bearer. Si no hay sesión, devuelve null (la app
 * en ese caso no debería estar pidiendo schedule — pero protegemos por las dudas).
 */
object ScheduleApi {

    private const val TAG = "ScheduleApi"
    private const val PATH = "/api/schedule"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    /**
     * Consulta el back y devuelve el snapshot actual del schedule, o null si
     * la llamada falla por cualquier razón (sin red, sin sesión, 5xx, JSON inválido).
     */
    suspend fun fetchSchedule(context: Context): ScheduleSnapshot? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val token = AuthRepository.get(appCtx).tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "⏸️ Sin sesión activa — saltando fetch de schedule")
            return@withContext null
        }

        val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')
        val url = "$baseUrl$PATH"

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "⚠️ GET $url devolvió HTTP $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseSnapshot(body)
        } catch (e: Exception) {
            Log.w(TAG, "❌ Falló fetch de schedule: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseSnapshot(json: String): ScheduleSnapshot? = try {
        val obj = JSONObject(json)
        ScheduleSnapshot(
            enabled = obj.optBoolean("enabled", false),
            from = obj.optString("from", null).takeUnless { it.isNullOrEmpty() || it == "null" },
            to = obj.optString("to", null).takeUnless { it.isNullOrEmpty() || it == "null" },
            tz = obj.optString("tz", null).takeUnless { it.isNullOrEmpty() || it == "null" },
            updatedAt = obj.optLong("updatedAt", 0L),
        )
    } catch (e: Exception) {
        Log.w(TAG, "❌ JSON inválido de /api/schedule: ${e.message}")
        null
    }
}
