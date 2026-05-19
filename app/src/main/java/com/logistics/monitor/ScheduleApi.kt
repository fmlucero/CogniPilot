package com.logistics.monitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP minimalista para consultar el endpoint GET /api/schedule del back.
 *
 * Reemplazo de Firebase Cloud Messaging (HU-18): en vez de recibir push, la app
 * pollea este endpoint en foreground (cada 30s desde MainActivity) y en
 * background (cada 15 min desde ScheduleSyncWorker).
 *
 * Endpoint público (sin auth) — alineado con el patrón actual de EventReporter.
 */
object ScheduleApi {

    private const val TAG = "ScheduleApi"
    private const val PATH = "/api/schedule"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    /**
     * Consulta el back y devuelve el snapshot actual del schedule, o null si
     * la llamada falla por cualquier razón (sin red, 5xx, JSON inválido).
     */
    suspend fun fetchSchedule(context: Context): ScheduleSnapshot? = withContext(Dispatchers.IO) {
        val baseUrl = context.applicationContext.getString(R.string.backend_base_url).trimEnd('/')
        val url = "$baseUrl$PATH"

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
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
