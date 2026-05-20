package com.logistics.monitor.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ruta")
data class RutaEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val fecha: String,      // ISO YYYY-MM-DD
    val empresaId: String,
    val syncedAt: Long,     // epoch ms del fetch
)
