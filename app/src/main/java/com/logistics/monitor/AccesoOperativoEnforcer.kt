package com.logistics.monitor

import android.util.Log
import com.logistics.monitor.data.entities.ReglaEntity
import org.json.JSONObject
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * HU-54 — Evaluador + cache de las reglas `acceso_operativo` en modo `app_trabajo`.
 *
 * Una regla de acceso operativo (HU-53) combina geocerca y/o horario:
 *   condicion = { geo?: {lat,lng,radius_m}, horario?: {desde,hasta "HH:MM"}, modo }
 *
 * Este enforcer cachea únicamente las reglas con `modo == "app_trabajo"` (el
 * modo `kiosko` lo maneja HU-59 por otra vía) y, dado el último fix GPS de
 * [LocationReporter] y la hora local del dispositivo, decide si el repartidor
 * tiene permitido usar la app de trabajo en este momento.
 *
 * Decisión de diseño (no-coercitiva con el GPS): si una regla tiene geocerca
 * pero todavía no hay fix GPS, NO se bloquea por geo (evita echar al repartidor
 * por un lag del GPS); el horario, en cambio, siempre es evaluable y se aplica.
 * Cada regla es una compuerta: el acceso se deniega ante el primer fallo.
 *
 * Cache en memoria poblado por [MeRepository] tras cada sync, igual patrón que
 * [GeofenceCache] — acceso sincrónico para que el AccessibilityService no abra
 * Room en su callback.
 */
object AccesoOperativoEnforcer {
    private const val TAG = "AccesoOperativo"

    sealed class Result {
        /** No hay reglas app_trabajo activas → no se aplica enforcement. */
        data object NoRule : Result()
        /** Todas las condiciones evaluables pasan. */
        data object Allowed : Result()
        /** Una regla falla: hay que echar al usuario de la app de trabajo. */
        data class Denied(val ruleName: String, val reason: String) : Result()
    }

    private data class Geo(val lat: Double, val lng: Double, val radiusM: Double)
    private data class Horario(val desde: LocalTime, val hasta: LocalTime)
    private data class Parsed(val nombre: String, val geo: Geo?, val horario: Horario?)

    @Volatile private var rules: List<Parsed> = emptyList()

    /** Llamado por MeRepository tras `replaceReglas`. Recibe las reglas activas. */
    fun update(activas: List<ReglaEntity>) {
        rules = activas
            .filter { it.tipo == "acceso_operativo" && it.activa }
            .mapNotNull { parse(it) }
        Log.i(TAG, "cache actualizado: ${rules.size} reglas app_trabajo")
    }

    fun reset() { rules = emptyList() }

    /** True si hay al menos una regla app_trabajo cacheada (para diagnóstico/UI). */
    fun hasRules(): Boolean = rules.isNotEmpty()

    /**
     * Evalúa las reglas contra la posición y la hora actuales. Devuelve el
     * primer fallo (Denied) o Allowed si todas pasan, o NoRule si no hay reglas.
     */
    fun evaluate(): Result {
        val snapshot = rules
        if (snapshot.isEmpty()) return Result.NoRule

        val pos = LocationReporter.lastLatLng()
        val now = LocalTime.now()

        for (r in snapshot) {
            // Horario: siempre evaluable.
            r.horario?.let { h ->
                if (!isWithin(now, h)) {
                    val reason = "fuera del horario permitido (${h.desde}–${h.hasta})"
                    Log.i(TAG, "🔒 DENIED regla='${r.nombre}' — $reason")
                    return Result.Denied(r.nombre, reason)
                }
            }
            // Geocerca: solo si hay fix GPS (sin fix no bloqueamos por geo).
            r.geo?.let { g ->
                if (pos != null) {
                    val dist = haversineMeters(pos.first, pos.second, g.lat, g.lng)
                    if (dist > g.radiusM) {
                        val reason = "fuera de la zona permitida (${dist.roundToInt()}m > ${g.radiusM.roundToInt()}m del centro)"
                        Log.i(TAG, "🔒 DENIED regla='${r.nombre}' — $reason")
                        return Result.Denied(r.nombre, reason)
                    }
                }
            }
        }
        return Result.Allowed
    }

    /** Hora dentro de la ventana, soportando ventanas que cruzan medianoche. */
    private fun isWithin(now: LocalTime, h: Horario): Boolean {
        return if (!h.desde.isAfter(h.hasta)) {
            // Ventana normal (ej. 08:00–18:00): desde <= now <= hasta.
            !now.isBefore(h.desde) && !now.isAfter(h.hasta)
        } else {
            // Ventana nocturna (ej. 22:00–06:00): now >= desde || now <= hasta.
            !now.isBefore(h.desde) || !now.isAfter(h.hasta)
        }
    }

    private fun parse(r: ReglaEntity): Parsed? {
        return try {
            val o = JSONObject(r.condicionJson)
            // Solo enforcement estándar (HU-54). Kiosko (HU-59) es otra vía.
            if (o.optString("modo", "app_trabajo") != "app_trabajo") return null

            val geo = o.optJSONObject("geo")?.let { g ->
                val lat = g.optDouble("lat", Double.NaN)
                val lng = g.optDouble("lng", Double.NaN)
                val rad = g.optDouble("radius_m", Double.NaN)
                if (lat.isNaN() || lng.isNaN() || rad.isNaN() || rad <= 0) null
                else Geo(lat, lng, rad)
            }
            val horario = o.optJSONObject("horario")?.let { hr ->
                val desde = hr.optString("desde", "")
                val hasta = hr.optString("hasta", "")
                if (desde.isBlank() || hasta.isBlank()) null
                else Horario(LocalTime.parse(desde), LocalTime.parse(hasta))
            }

            if (geo == null && horario == null) {
                Log.w(TAG, "⚠️ regla '${r.nombre}' sin geo ni horario evaluables: ${r.condicionJson}")
                null
            } else {
                Parsed(r.nombre, geo, horario)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ regla '${r.nombre}' condicion inválida: ${e.message}")
            null
        }
    }

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
