package com.logistics.monitor.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "parada",
    foreignKeys = [
        ForeignKey(
            entity = RutaEntity::class,
            parentColumns = ["id"],
            childColumns = ["rutaId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("rutaId")],
)
data class ParadaEntity(
    @PrimaryKey val id: String,
    val rutaId: String,
    val orden: Int,
    val lat: Double,
    val lng: Double,
    val direccion: String?,
    val ventanaDesde: String?,
    val ventanaHasta: String?,
)
