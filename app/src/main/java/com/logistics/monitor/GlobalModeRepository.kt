package com.logistics.monitor

import android.content.Context

/**
 * Persiste el estado del "Modo global" (toggle de la pantalla principal).
 * Cuando está activo, el AccessibilityService deja de filtrar por package y
 * reporta eventos de todas las apps del celular (en gris en el panel web).
 */
class GlobalModeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS = "global_mode_prefs"
        private const val KEY_ENABLED = "enabled"
    }
}
