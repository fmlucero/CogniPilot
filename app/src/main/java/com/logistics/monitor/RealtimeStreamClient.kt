package com.logistics.monitor

import android.content.Context
import android.util.Log
import com.logistics.monitor.auth.AuthRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente Server-Sent Events para HU-18 fase 4.
 *
 * Mantiene una conexión HTTP de larga duración con GET /api/realtime/stream
 * del back. Recibe eventos `schedule_updated` con el snapshot completo y los
 * aplica directamente vía ScheduleSyncWorker.applySnapshot (sin re-fetch).
 *
 * Lifecycle: MainActivity llama `connect()` en onResume y `disconnect()` en
 * onPause. El polling cada 30s queda como redundancia (si el SSE cae y no
 * reconecta, el polling cubre el caso).
 *
 * Reconexión: no implementada — si la conexión se cae el polling de
 * MainActivity (30s) y el WorkManager (15 min) siguen sincronizando.
 */
class RealtimeStreamClient(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // sin timeout para streams largos
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    private val factory = EventSources.createFactory(client)
    private var eventSource: EventSource? = null

    fun connect() {
        if (eventSource != null) return  // ya conectado
        val appCtx = context.applicationContext
        val token = AuthRepository.get(appCtx).tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "⏸️ Sin sesión activa — no conectamos SSE")
            return
        }
        val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/api/realtime/stream")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $token")
            .build()

        eventSource = factory.newEventSource(request, listener)
        Log.i(TAG, "🔌 SSE connect → $baseUrl/api/realtime/stream")
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        Log.i(TAG, "❌ SSE disconnect")
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.i(TAG, "✅ SSE conectado (HTTP ${response.code})")
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            Log.d(TAG, "📨 SSE event: type=$type, data=$data")
            if (type == "schedule_updated") {
                handleScheduleUpdated(data)
            }
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            Log.w(TAG, "⚠️ SSE error: ${t?.message ?: response?.code}")
            this@RealtimeStreamClient.eventSource = null
            // No reconectamos automáticamente; el polling cubre el gap.
        }

        override fun onClosed(eventSource: EventSource) {
            Log.i(TAG, "🔒 SSE closed by server")
            this@RealtimeStreamClient.eventSource = null
        }
    }

    private fun handleScheduleUpdated(json: String) {
        val snapshot = parseSnapshot(json) ?: return
        // applySnapshot es sync (no toca red), seguro de llamar desde el callback.
        val changed = ScheduleSyncWorker.applySnapshot(context.applicationContext, snapshot)
        if (changed) {
            Log.i(TAG, "📡 SSE aplicó cambio de schedule")
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
        Log.w(TAG, "❌ JSON inválido de SSE: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "RealtimeSSE"
    }
}
