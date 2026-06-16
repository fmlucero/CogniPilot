package com.logistics.monitor

import android.content.Context
import android.util.Log
import com.logistics.monitor.auth.AuthRepository
import com.logistics.monitor.data.AppDatabase
import com.logistics.monitor.data.entities.EventoOfflineEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Envía eventos del usuario al backend (POST /api/events) y, si la red falla
 * (HU-10), los persiste en Room para drenarlos cuando vuelva la conexión.
 *
 * HU-03: requiere Bearer token. Si no hay sesión, el evento se descarta (no
 * persistimos eventos huérfanos sin user — los re-enviaríamos a la sesión
 * equivocada después).
 *
 * URL base configurada en res/values/strings.xml → @string/backend_base_url.
 */
object EventReporter {

    private const val TAG = "EventReporter"
    private const val PATH = "/api/events"
    private const val PATH_BULK = "/api/events/bulk"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val DRAIN_BATCH = 200

    // Tipos válidos en el backend
    const val TYPE_APP_OPENED = "app_opened"
    const val TYPE_WARNING_SHOWN = "warning_shown"
    const val TYPE_SCAN_DETECTED = "scan_detected"
    const val TYPE_USER_CONTINUED = "user_continued"
    const val TYPE_USER_CANCELLED = "user_cancelled"
    const val TYPE_GLOBAL_APP_OPENED = "global_app_opened"
    const val TYPE_GLOBAL_CLICKED = "global_clicked"

    private val executor = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()  // evita que dos drains corran a la vez

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
        val ts = System.currentTimeMillis()

        executor.execute {
            val ok = postJson("$baseUrl$PATH", payload, token)
            if (!ok) {
                // HU-10 — sin red o back caído → persistir para drainer.
                persistOffline(appCtx, type, payload, ts)
            }
        }
    }

    /**
     * HU-10 — drena los eventos pendientes en Room enviándolos en bulk al back.
     * Llamado por OfflineDrainWorker (periódico + on-network-available) y por
     * MainActivity al volver al foreground.
     *
     * Returns: (sent, failed) — útil para logs y tests.
     */
    suspend fun drainOffline(context: Context): Pair<Int, Int> {
        val appCtx = context.applicationContext
        val token = AuthRepository.get(appCtx).tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "drainOffline: sin sesión — skip")
            return 0 to 0
        }

        return drainMutex.withLock {
            val dao = AppDatabase.get(appCtx).eventoOfflineDao()
            val pending = dao.getPending(DRAIN_BATCH)
            if (pending.isEmpty()) return@withLock 0 to 0

            Log.i(TAG, "drainOffline: ${pending.size} eventos pendientes")
            val baseUrl = appCtx.getString(R.string.backend_base_url).trimEnd('/')

            // Armar el JSON bulk: { "events": [ ...payloads parseados... ] }
            val events = JSONArray()
            pending.forEach { e ->
                try {
                    events.put(JSONObject(e.payloadJson))
                } catch (_: Exception) {
                    Log.w(TAG, "evento ${e.id} con payload inválido — se descarta")
                }
            }
            val body = JSONObject().put("events", events).toString()
            val ok = postJson("$baseUrl$PATH_BULK", body, token)
            if (ok) {
                dao.deleteByIds(pending.map { it.id })
                Log.i(TAG, "drainOffline: ${pending.size} enviados y borrados")
                pending.size to 0
            } else {
                dao.bumpIntentos(pending.map { it.id })
                Log.w(TAG, "drainOffline: falló — los eventos siguen en cola")
                0 to pending.size
            }
        }
    }

    /**
     * Cuántos eventos esperan offline — para diagnóstico en MainActivity.
     * `suspend` + `withContext(IO)` (NO `runBlocking`): se llama desde el hilo
     * principal y `runBlocking` lo bloqueaba hasta que la query de Room terminara,
     * lo que colgaba la UI cuando había contención de DB (sync de ruta/reglas +
     * drenado de eventos offline). Ver I-30.
     */
    suspend fun pendingCount(context: Context): Int = try {
        withContext(Dispatchers.IO) {
            AppDatabase.get(context.applicationContext).eventoOfflineDao().count()
        }
    } catch (_: Exception) { 0 }

    private fun persistOffline(context: Context, type: String, payloadJson: String, ts: Long) {
        ioScope.launch {
            try {
                AppDatabase.get(context).eventoOfflineDao().insert(
                    EventoOfflineEntity(type = type, payloadJson = payloadJson, ts = ts)
                )
                Log.i(TAG, "💾 evento type=$type persistido offline (drainer lo procesa cuando vuelva la red)")
            } catch (e: Exception) {
                Log.w(TAG, "❌ persistOffline falló: ${e.message}")
            }
        }
    }

    /** Devuelve true si la red aceptó (HTTP 2xx). */
    private fun postJson(url: String, body: String, token: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
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
                Log.d(TAG, "✅ Evento(s) enviado(s) (HTTP $code)")
                true
            } else {
                Log.w(TAG, "⚠️ Backend respondió HTTP $code — body length=${body.length}")
                // Tratar todo non-2xx como fallo: el caller persiste y el drainer
                // reintenta. Un 4xx persistente acumularía basura, pero es preferible
                // a perder un evento por un 5xx transitorio. El campo `intentos` del
                // EventoOfflineEntity sirve para que un futuro purger limpie los
                // crónicamente fallados.
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ Falló envío de evento (red): ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
