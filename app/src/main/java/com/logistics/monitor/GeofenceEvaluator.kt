package com.logistics.monitor

import android.util.Log
import com.logistics.monitor.data.entities.ReglaEntity
import org.json.JSONObject

/**
 * HU-42 — Evaluador de reglas geofence al detectar un escaneo.
 *
 * El AAS llama [evaluateForScan] cuando detecta `scan_detected`. La función:
 *  1. Lee reglas activas de tipo "geofence" desde un cache en memoria
 *     (poblado por MeRepository en cada sync).
 *  2. Obtiene la última posición GPS conocida de [LocationReporter].
 *  3. Para cada regla, parsea condicionJson como {radius_m, lat, lng} y
 *     calcula haversine entre el GPS y el target.
 *  4. Devuelve el primer fallo encontrado (Outside) o Inside si todas pasan.
 *
 * Si no hay reglas o no hay GPS, devuelve un Result.NoCheck explícito
 * (graceful: no bloquea, solo loguea). Aplica únicamente al sub-tipo más
 * simple del modelo de la card HU-42: `target=lat_lng` (radio respecto a
 * un punto fijo). `target=parada` y `target=empresa` quedan para una
 * iteración futura — requieren contexto del paquete escaneado / lat-lng
 * de la empresa que aún no están en el modelo.
 */
object GeofenceEvaluator {
    private const val TAG = "GeofenceEvaluator"

    sealed class Result {
        data object NoRule : Result()
        data object NoLocation : Result()
        data class Inside(val ruleName: String, val accion: String) : Result()
        data class Outside(
            val ruleName: String,
            val accion: String,
            val distanceM: Double,
            val radiusM: Double,
        ) : Result()
    }

    /**
     * Devuelve el primer fallo encontrado entre las reglas geofence cacheadas,
     * o Inside si todas pasan, o NoRule/NoLocation si no se puede evaluar.
     */
    fun evaluateForScan(): Result {
        val rules = GeofenceCache.rules
        if (rules.isEmpty()) return Result.NoRule
        val pos = LocationReporter.lastLatLng() ?: return Result.NoLocation
        val (lat, lng) = pos
        for (r in rules) {
            val (tLat, tLng, radius) = parseCondicion(r) ?: continue
            val dist = haversineMeters(lat, lng, tLat, tLng)
            if (dist > radius) {
                Log.i(TAG, "🚷 Geofence FALLA — regla='${r.nombre}' dist=${"%.1f".format(dist)}m radio=${radius}m")
                return Result.Outside(r.nombre, r.accion, dist, radius)
            }
        }
        // Si pasó todas las reglas, devolvemos un "Inside" referenciando la primera.
        val first = rules.first()
        return Result.Inside(first.nombre, first.accion)
    }

    private fun parseCondicion(r: ReglaEntity): Triple<Double, Double, Double>? {
        return try {
            val o = JSONObject(r.condicionJson)
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            val radius = o.optDouble("radius_m", Double.NaN)
            if (lat.isNaN() || lng.isNaN() || radius.isNaN() || radius <= 0) {
                Log.w(TAG, "⚠️ regla '${r.nombre}' tiene condicion invalida: ${r.condicionJson}")
                null
            } else {
                Triple(lat, lng, radius)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ regla '${r.nombre}' condicion no es JSON valido: ${e.message}")
            null
        }
    }

    /** Distancia Haversine en metros entre dos puntos lat/lng. */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δφ = Math.toRadians(lat2 - lat1)
        val Δλ = Math.toRadians(lng2 - lng1)
        val a = Math.sin(Δφ / 2) * Math.sin(Δφ / 2) +
                Math.cos(φ1) * Math.cos(φ2) *
                Math.sin(Δλ / 2) * Math.sin(Δλ / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}

/**
 * Cache en memoria de reglas geofence activas. Poblado por [MeRepository]
 * después de cada sync exitoso desde el back. Acceso sincrónico para que
 * el AAS (UI thread sensitive) no tenga que abrir Room en el callback.
 */
object GeofenceCache {
    @Volatile var rules: List<ReglaEntity> = emptyList()
        private set

    fun update(allActivas: List<ReglaEntity>) {
        rules = allActivas.filter { it.tipo == "geofence" }
    }
}
