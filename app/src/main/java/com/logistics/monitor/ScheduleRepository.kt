package com.logistics.monitor

import android.content.Context

/**
 * Cache local del horario permitido para escanear, sincronizado vía FCM
 * desde el panel remoto (CogniPilotRemote en Vercel).
 *
 * Si no hay datos en cache (primera ejecución, sin red), enabled=false →
 * el monitor se comporta como si no hubiera restricción horaria.
 */
class ScheduleRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: ScheduleSnapshot) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, snapshot.enabled)
            .putString(KEY_FROM, snapshot.from)
            .putString(KEY_TO, snapshot.to)
            .putString(KEY_TZ, snapshot.tz)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): ScheduleSnapshot = ScheduleSnapshot(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        from = prefs.getString(KEY_FROM, null),
        to = prefs.getString(KEY_TO, null),
        tz = prefs.getString(KEY_TZ, null),
        updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
    )

    companion object {
        private const val PREFS_NAME = "schedule_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"
        private const val KEY_TZ = "tz"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

data class ScheduleSnapshot(
    val enabled: Boolean,
    val from: String?,
    val to: String?,
    val tz: String?,
    val updatedAt: Long
)
