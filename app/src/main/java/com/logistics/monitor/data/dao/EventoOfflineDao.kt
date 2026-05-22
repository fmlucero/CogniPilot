package com.logistics.monitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.logistics.monitor.data.entities.EventoOfflineEntity

@Dao
interface EventoOfflineDao {

    @Insert
    suspend fun insert(evento: EventoOfflineEntity): Long

    @Query("SELECT * FROM evento_offline ORDER BY ts ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 500): List<EventoOfflineEntity>

    @Query("SELECT COUNT(*) FROM evento_offline")
    suspend fun count(): Int

    @Query("DELETE FROM evento_offline WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE evento_offline SET intentos = intentos + 1 WHERE id IN (:ids)")
    suspend fun bumpIntentos(ids: List<Long>)

    @Query("DELETE FROM evento_offline")
    suspend fun clear()
}
