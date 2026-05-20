package com.logistics.monitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.logistics.monitor.data.entities.ParadaEntity
import com.logistics.monitor.data.entities.PaqueteEntity
import com.logistics.monitor.data.entities.RutaEntity

@Dao
interface RutaDao {

    // ── Inserts ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuta(ruta: RutaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParadas(paradas: List<ParadaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPaquetes(paquetes: List<PaqueteEntity>)

    @Transaction
    suspend fun replaceRutaCompleta(
        ruta: RutaEntity,
        paradas: List<ParadaEntity>,
        paquetes: List<PaqueteEntity>,
    ) {
        // Delete cascade limpia paradas/paquetes viejos al borrar la ruta.
        deleteAllRutas()
        upsertRuta(ruta)
        upsertParadas(paradas)
        upsertPaquetes(paquetes)
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM ruta WHERE fecha = :fecha LIMIT 1")
    suspend fun getRutaByFecha(fecha: String): RutaEntity?

    @Query("SELECT * FROM ruta ORDER BY syncedAt DESC LIMIT 1")
    suspend fun getLastRuta(): RutaEntity?

    @Query("SELECT * FROM parada WHERE rutaId = :rutaId ORDER BY orden ASC")
    suspend fun getParadas(rutaId: String): List<ParadaEntity>

    @Query("SELECT * FROM paquete WHERE paradaId IN (:paradaIds)")
    suspend fun getPaquetes(paradaIds: List<String>): List<PaqueteEntity>

    @Query("SELECT * FROM paquete WHERE codigoMl = :codigoMl LIMIT 1")
    suspend fun getPaqueteByCodigo(codigoMl: String): PaqueteEntity?

    // ── Mantenimiento ───────────────────────────────────────────────────────

    @Query("DELETE FROM ruta")
    suspend fun deleteAllRutas()
}
