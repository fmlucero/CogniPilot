package com.logistics.monitor.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * HU-10 — Evento de la app que no pudo enviarse al back por falta de red.
 *
 * El payload se serializa como JSON string para no acoplar el esquema Room
 * al payload concreto de cada tipo. Cuando hay red, el [OfflineDrainWorker]
 * los lee, hace un POST batch a /api/events/bulk, y los elimina si el back
 * acepta (HTTP 2xx).
 */
@Entity(tableName = "evento_offline")
data class EventoOfflineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: String,
    val payloadJson: String,
    val ts: Long,           // ms epoch al momento de generarse
    val intentos: Int = 0,
)
