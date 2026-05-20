package com.logistics.monitor.data

import android.content.Context
import android.util.Log
import com.logistics.monitor.data.entities.ParadaEntity
import com.logistics.monitor.data.entities.PaqueteEntity
import com.logistics.monitor.data.entities.ReglaEntity
import com.logistics.monitor.data.entities.RutaEntity

/**
 * HU-03 — Orquesta la descarga de ruta del día + reglas activas, y las cachea
 * en Room para acceso offline.
 *
 * Modelo "remote-first con fallback local":
 *   - syncFromBackend()  → fetch del back y replace en Room. Llamado al login y por demanda.
 *   - getRutaCached()    → lee solo de Room (offline-safe).
 *   - getReglasCached()  → idem.
 */
class MeRepository(private val context: Context) {

    private val appCtx = context.applicationContext
    private val api = MeApi(appCtx)
    private val db get() = AppDatabase.get(appCtx)

    data class SyncResult(val rutaOk: Boolean, val reglasOk: Boolean)

    /**
     * Descarga ruta + reglas del back y persiste en Room.
     * Devuelve qué partes se sincronizaron OK. No tira excepción: si falla por red,
     * los flags quedan en false y el caller decide cómo presentar el error.
     */
    suspend fun syncFromBackend(): SyncResult {
        var rutaOk = false
        var reglasOk = false

        // Ruta (puede no haber asignación → MeApi devuelve null, no error)
        try {
            val miRuta = api.getMiRuta()
            if (miRuta != null) {
                val now = System.currentTimeMillis()
                val rutaEnt = RutaEntity(
                    id = miRuta.ruta.id,
                    nombre = miRuta.ruta.nombre,
                    fecha = miRuta.ruta.fecha,
                    empresaId = miRuta.ruta.empresaId,
                    syncedAt = now,
                )
                val paradaEnts = miRuta.paradas.map {
                    ParadaEntity(
                        id = it.id, rutaId = miRuta.ruta.id, orden = it.orden,
                        lat = it.lat, lng = it.lng, direccion = it.direccion,
                        ventanaDesde = it.ventanaDesde, ventanaHasta = it.ventanaHasta,
                    )
                }
                val paqueteEnts = miRuta.paradas.flatMap { pa ->
                    pa.paquetes.map {
                        PaqueteEntity(
                            id = it.id, paradaId = pa.id,
                            codigoMl = it.codigoMl, descripcion = it.descripcion,
                        )
                    }
                }
                db.rutaDao().replaceRutaCompleta(rutaEnt, paradaEnts, paqueteEnts)
            } else {
                // Sin asignación → limpiar local
                db.rutaDao().deleteAllRutas()
            }
            rutaOk = true
        } catch (e: Exception) {
            Log.w(TAG, "❌ Sync ruta falló: ${e.message}")
        }

        // Reglas (siempre devuelve lista, vacía si la empresa no tiene)
        try {
            val misReglas = api.getMisReglas()
            val now = System.currentTimeMillis()
            val reglaEnts = misReglas.reglas.map {
                ReglaEntity(
                    id = it.id, nombre = it.nombre, tipo = it.tipo, accion = it.accion,
                    condicionJson = api.serializeCondicion(it.condicion),
                    activa = it.activa, rutaId = it.rutaId, syncedAt = now,
                )
            }
            db.reglaDao().replaceReglas(reglaEnts)
            reglasOk = true
        } catch (e: Exception) {
            Log.w(TAG, "❌ Sync reglas falló: ${e.message}")
        }

        return SyncResult(rutaOk = rutaOk, reglasOk = reglasOk)
    }

    suspend fun getRutaCached(): RutaEntity? = db.rutaDao().getLastRuta()
    suspend fun getParadasCached(rutaId: String) = db.rutaDao().getParadas(rutaId)
    suspend fun getReglasCachedActivas() = db.reglaDao().getActivas()

    companion object {
        private const val TAG = "MeRepository"
    }
}
