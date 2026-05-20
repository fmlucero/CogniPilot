package com.logistics.monitor.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "regla")
data class ReglaEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val tipo: String,             // ventana_horaria | paquete_fuera_parada | app_bloqueada_en_horario
    val accion: String,           // bloquear | alertar
    val condicionJson: String,    // JSON serializado del campo condicion
    val activa: Boolean,
    val rutaId: String?,
    val syncedAt: Long,
)
