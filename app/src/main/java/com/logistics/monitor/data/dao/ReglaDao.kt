package com.logistics.monitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.logistics.monitor.data.entities.ReglaEntity

@Dao
interface ReglaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReglas(reglas: List<ReglaEntity>)

    @Transaction
    suspend fun replaceReglas(reglas: List<ReglaEntity>) {
        deleteAll()
        upsertReglas(reglas)
    }

    @Query("SELECT * FROM regla WHERE activa = 1")
    suspend fun getActivas(): List<ReglaEntity>

    @Query("SELECT * FROM regla WHERE tipo = :tipo AND activa = 1")
    suspend fun getActivasByTipo(tipo: String): List<ReglaEntity>

    @Query("DELETE FROM regla")
    suspend fun deleteAll()
}
