package com.logistics.monitor

import android.content.Context

/**
 * Persiste el estado del "Modo exploración" (toggle de la pantalla principal).
 * Cuando está activo, el AccessibilityService captura la estructura de las
 * pantallas de SC Pack (con dedup por huella) para poder estudiar después cómo
 * organiza las rutas. Es una feature de investigación (piloto), OFF por default.
 */
class CaptureModeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Default TRUE en este build: se lo pasamos a Nico con la captura de
    // exploración ya activada, para seguir juntando datos útiles (estructura de
    // pantallas + códigos de paquete) sin que tenga que prenderla a mano.
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS = "capture_mode_prefs"
        private const val KEY_ENABLED = "enabled"
    }
}
