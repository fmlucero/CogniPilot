package com.logistics.monitor.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * HU-03 — Storage cifrado de tokens JWT.
 *
 * Usa EncryptedSharedPreferences (Jetpack Security) con MasterKey del Keystore
 * Android. Los tokens quedan cifrados en disco; un atacante con acceso físico
 * al device necesita romper el Keystore para leerlos.
 *
 * El UUID de dispositivo NO va acá — vive en `event_reporter_prefs` y se preserva
 * incluso después de logout (identidad del hardware, no de la sesión).
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val ctx = context.applicationContext
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            ctx,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    fun saveUser(userId: String, email: String, nombre: String, rol: String, empresaId: String?) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NOMBRE, nombre)
            .putString(KEY_ROL, rol)
            .putString(KEY_EMPRESA_ID, empresaId)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)
    fun getNombre(): String? = prefs.getString(KEY_NOMBRE, null)
    fun getRol(): String? = prefs.getString(KEY_ROL, null)
    fun getEmpresaId(): String? = prefs.getString(KEY_EMPRESA_ID, null)

    fun hasSession(): Boolean = !prefs.getString(KEY_ACCESS, null).isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "cp_auth_prefs"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_NOMBRE = "nombre"
        private const val KEY_ROL = "rol"
        private const val KEY_EMPRESA_ID = "empresa_id"
    }
}
