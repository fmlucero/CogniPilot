package com.logistics.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.logistics.monitor.auth.AuthRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * HU-41 — Reporta la posición GPS del repartidor al back cada N segundos.
 *
 * Diseño:
 *   - FusedLocationProviderClient pide updates con prioridad BALANCED (red celular
 *     + GPS asistido) para no quemar batería; interval 30s, displacement mínimo 10m.
 *   - Cada callback emite POST /api/positions con deviceUuid + lat + lng + ts.
 *   - Reuse del HttpClient singleton (con AuthInterceptor → Bearer automático).
 *   - Sin sesión activa o sin permission → log local y no hace nada (graceful).
 *
 * Se monta desde LogisticsMonitoringService (foreground type=dataSync|location)
 * para que siga reportando aunque la app esté en background. Si el servicio se
 * detiene (usuario apaga el monitor), el reporter se detiene también.
 */
object LocationReporter {

    private const val TAG = "LocationReporter"
    private const val PATH = "/api/positions"
    private const val INTERVAL_MS = 30_000L         // 30s entre lecturas
    private const val FASTEST_INTERVAL_MS = 15_000L // no más rápido que 15s
    private const val MIN_DISPLACEMENT_M = 0f       // 0 = reportar SIEMPRE que llegue un fix, sin importar movimiento
                                                    // (originalmente 10m, pero eso silenciaba la app cuando el
                                                    // usuario estaba quieto — el interval de 30s ya regula)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private val executor = Executors.newSingleThreadExecutor()

    private var fusedClient: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var running = false

    // Diagnóstico — para que MainActivity pueda mostrar feedback real al usuario.
    @Volatile private var lastFixAt: Long = 0L         // ms desde epoch del último fix recibido
    @Volatile private var lastPostStatus: Int = -1     // último HTTP status del POST (-1 si nunca)
    fun lastFixAtMs(): Long = lastFixAt
    fun lastPostStatusCode(): Int = lastPostStatus

    /** True si hay permission FINE o COARSE concedido. */
    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** True si el reporter está pidiendo updates activamente. */
    fun isRunning(): Boolean = running

    /** Empieza a pedir updates de ubicación. Idempotente. */
    @SuppressLint("MissingPermission") // chequeamos arriba con hasPermission()
    fun start(context: Context) {
        val appCtx = context.applicationContext
        if (running) {
            Log.d(TAG, "ya está corriendo, no reinicio")
            return
        }
        if (!hasPermission(appCtx)) {
            Log.w(TAG, "⏸️ sin permiso de ubicación — no se reportará GPS")
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(appCtx)
        fusedClient = client

        // HIGH_ACCURACY fuerza GPS hardware además de red — necesario para que
        // se dispare el callback cuando el usuario no está movilizándose
        // (Balanced en quietud puede no entregar nada). Más drain de batería
        // pero aceptable para la jornada del repartidor (ya está la pantalla
        // encendida con el monitor + foreground service).
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastFixAt = System.currentTimeMillis()
                Log.i(TAG, "📍 GPS fix: lat=${loc.latitude}, lng=${loc.longitude}, accuracy=${loc.accuracy}m")
                postPosition(appCtx, loc.latitude, loc.longitude, loc.time)
            }
        }
        callback = cb

        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            running = true
            Log.i(TAG, "▶️ LocationReporter iniciado — interval=${INTERVAL_MS}ms, displacement=${MIN_DISPLACEMENT_M}m")
            // Disparar un primer POST inmediato con la última posición conocida
            // (cache del sistema), si hay. Útil para que el supervisor vea el
            // pin en el mapa enseguida sin esperar al primer fix completo.
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastFixAt = System.currentTimeMillis()
                    Log.i(TAG, "📍 last known fix: lat=${loc.latitude}, lng=${loc.longitude}, accuracy=${loc.accuracy}m")
                    postPosition(appCtx, loc.latitude, loc.longitude, loc.time)
                } else {
                    Log.i(TAG, "ℹ️ sin lastLocation cacheada — esperando primer fix del callback")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "❌ SecurityException al pedir updates: ${e.message}")
            running = false
        }
    }

    /** Detiene los updates. Idempotente. */
    fun stop() {
        val client = fusedClient
        val cb = callback
        if (client != null && cb != null) {
            client.removeLocationUpdates(cb)
            Log.i(TAG, "⏹ LocationReporter detenido")
        }
        fusedClient = null
        callback = null
        running = false
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun postPosition(context: Context, lat: Double, lng: Double, ts: Long) {
        val token = AuthRepository.get(context).tokens.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "⏸️ sin sesión — descartando posición")
            return
        }
        val baseUrl = context.getString(R.string.backend_base_url).trimEnd('/')
        val deviceUuid = DeviceIdProvider.get(context)

        val payload = JSONObject().apply {
            put("deviceUuid", deviceUuid)
            put("lat", lat)
            put("lng", lng)
            put("ts", ts)
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
            lastPostStatus = code
            if (code in 200..299) {
                Log.i(TAG, "✅ POST /api/positions OK ($code)")
            } else {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
                Log.w(TAG, "⚠️ back respondió HTTP $code para position update: ${errBody.take(200)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ falló envío de posición: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
