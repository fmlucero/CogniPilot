package com.logistics.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.logistics.monitor.data.entities.ParadaEntity
import com.logistics.monitor.data.entities.PaqueteEntity

/**
 * HU-09 — Watcher proactivo de proximidad a las paradas de la jornada.
 *
 * Cuando el GPS recibe un nuevo fix, [evaluate] revisa cada parada cacheada
 * y, si el repartidor cruzó de "fuera" a "dentro" del radio configurable
 * (default 50m), dispara un nudge con info del paquete a entregar.
 *
 * Diseño:
 *  - Cache en memoria poblado por [MeRepository] después de cada sync de ruta
 *    (idéntico patrón a [GeofenceCache]).
 *  - Estado `wasInside` por paradaId: solo dispara en TRANSICIÓN, no
 *    continuamente mientras el repartidor está dentro del radio.
 *  - Dispatch al main thread vía Handler (el GPS callback puede llegar en
 *    cualquier thread).
 *  - Si el [OverlayManager] singleton no está accesible (el AAS aún no
 *    arrancó), el nudge se descarta — no es bloqueante para el negocio.
 */
object ParadaProximityWatcher {
    private const val TAG = "ParadaProximityWatcher"
    private const val DEFAULT_RADIUS_M = 50.0

    data class ParadaConPaquetes(
        val parada: ParadaEntity,
        val paquetes: List<PaqueteEntity>,
    )

    @Volatile private var paradas: List<ParadaConPaquetes> = emptyList()
    private val wasInside = mutableMapOf<String, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Llamado por MeRepository.syncFromBackend tras `replaceRutaCompleta`. */
    fun updateCache(paradas: List<ParadaEntity>, paquetes: List<PaqueteEntity>) {
        val byParada: Map<String, List<PaqueteEntity>> = paquetes.groupBy { it.paradaId }
        val sorted = paradas.sortedBy { it.orden }.map { p ->
            ParadaConPaquetes(p, byParada[p.id] ?: emptyList())
        }
        this.paradas = sorted
        // Si la ruta cambió, limpiamos el estado para que vuelva a disparar nudges
        // de las nuevas paradas. Sin esto, una parada con mismo id que ya marcó
        // wasInside=true nunca volvería a notificar.
        val validIds = sorted.map { it.parada.id }.toSet()
        synchronized(wasInside) {
            val toRemove = wasInside.keys.filterNot { it in validIds }
            toRemove.forEach { wasInside.remove(it) }
        }
        Log.i(TAG, "cache actualizado: ${sorted.size} paradas")
    }

    /**
     * Evalúa el fix GPS contra cada parada cacheada. Si detecta una transición
     * fuera→dentro, dispara un nudge en main thread.
     */
    fun evaluate(context: Context, lat: Double, lng: Double, radiusM: Double = DEFAULT_RADIUS_M) {
        val snapshot = paradas
        if (snapshot.isEmpty()) return

        for (p in snapshot) {
            val dist = haversineMeters(lat, lng, p.parada.lat, p.parada.lng)
            val isInsideNow = dist <= radiusM
            val wasIn: Boolean
            synchronized(wasInside) {
                wasIn = wasInside[p.parada.id] ?: false
                wasInside[p.parada.id] = isInsideNow
            }
            if (isInsideNow && !wasIn) {
                Log.i(TAG, "🎯 ENTRA en parada ${p.parada.orden} (${"%.1f".format(dist)}m, radio ${radiusM}m)")
                mainHandler.post { showNudgeFor(context, p) }
            }
        }
    }

    private fun showNudgeFor(context: Context, p: ParadaConPaquetes) {
        // Llamamos al AAS si está instanciado — usa su OverlayManager privado.
        // Si no, fallback a un Toast (la app puede no estar en foreground del
        // AccessibilityService pero el reporter sí estar corriendo).
        val service = LogisticsAccessibilityService.currentInstance()
        if (service == null) {
            Log.w(TAG, "AAS no instanciado — nudge descartado")
            return
        }
        val title = "📦 Parada ${p.parada.orden}"
        val message = buildString {
            if (!p.parada.direccion.isNullOrBlank()) {
                appendLine(p.parada.direccion)
            }
            val codigos = p.paquetes.take(3).map { it.codigoMl }
            if (codigos.isNotEmpty()) {
                append("Paquete${if (codigos.size > 1) "s" else ""}: ")
                append(codigos.joinToString(", "))
                if (p.paquetes.size > codigos.size) {
                    append(" (+${p.paquetes.size - codigos.size} más)")
                }
            } else {
                append("Sin paquetes asociados")
            }
        }.trim()
        service.showNudgeFromWatcher(title, message)
    }

    /** Reset del estado in-memory — útil en logout. */
    fun reset() {
        paradas = emptyList()
        synchronized(wasInside) { wasInside.clear() }
    }

    // Haversine en metros — misma fórmula que GeofenceEvaluator (no se reusa por
    // mantener cada modulo self-contained).
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
