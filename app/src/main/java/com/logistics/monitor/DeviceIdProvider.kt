package com.logistics.monitor

import android.content.Context
import java.util.UUID

/**
 * UUID estable del dispositivo, persistido en SharedPreferences NORMAL (no cifrado).
 *
 * Convive con EventReporter que lo usa con la misma key. NO se borra al logout
 * porque representa identidad del hardware, no de la sesión.
 *
 * El back lo recibe en /api/auth/login → /api/devices/register (upsert).
 */
object DeviceIdProvider {

    private const val PREFS = "event_reporter_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
