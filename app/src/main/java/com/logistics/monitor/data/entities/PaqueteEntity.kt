package com.logistics.monitor.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "paquete",
    foreignKeys = [
        ForeignKey(
            entity = ParadaEntity::class,
            parentColumns = ["id"],
            childColumns = ["paradaId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("paradaId"), Index("codigoMl")],
)
data class PaqueteEntity(
    @PrimaryKey val id: String,
    val paradaId: String,
    val codigoMl: String,
    val descripcion: String?,
)
