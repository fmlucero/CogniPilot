package com.logistics.monitor

import android.content.Context

/**
 * HU-59 — Persiste el opt-in del "modo kiosko de jornada".
 *
 * Cuando está activo Y la empresa tiene una regla `acceso_operativo` en modo
 * `kiosko`, el AccessibilityService mantiene un overlay full-screen que bloquea
 * el teléfono hasta que se cumplan zona + horario + permisos. Es opcional: lo
 * enciende el repartidor al iniciar la jornada y puede salir desde el overlay.
 */
class KioskoModeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS = "kiosko_mode_prefs"
        private const val KEY_ENABLED = "enabled"
    }
}
